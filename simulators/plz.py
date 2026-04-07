"""
PLZ — 频率转换模拟器

职责：基频 → 三倍频（紫外），晶体位姿调整（B12），采样接入（B13）。

状态序列：
  STANDBY → MOVING（晶体调整）→ READY → SAMPLING → DATA_READY
"""
import asyncio
import logging
import random

from .base import BaseSimulator, SimState

logger = logging.getLogger(__name__)


class PLZSimulator(BaseSimulator):
    """频率转换子系统模拟器。"""

    def __init__(self, sim_speed: float = 1.0):
        super().__init__("PLZ", sim_speed)
        self._crystal_pitch: float = 0.0    # mrad
        self._crystal_yaw: float = 0.0      # mrad
        self._conversion_efficiency: float = 0.0   # 0–1
        self._uv_energy: float = 0.0        # J，三倍频输出能量
        self._sampling_data: dict = {}

    # ── 属性 ────────────────────────────────────────────────

    @property
    def crystal_pitch(self) -> float:
        return self._crystal_pitch

    @property
    def crystal_yaw(self) -> float:
        return self._crystal_yaw

    @property
    def conversion_efficiency(self) -> float:
        return self._conversion_efficiency

    @property
    def uv_energy(self) -> float:
        return self._uv_energy

    # ── 命令 ────────────────────────────────────────────────

    async def set_crystal_pose(self, pitch: float, yaw: float):
        """
        B12: 调整晶体位姿（角度匹配相位匹配条件）。
        pitch, yaw 单位：mrad
        """
        await self._transition(SimState.MOVING, f"调整晶体位姿: pitch={pitch}, yaw={yaw}")
        await self._delay(1.5)
        self._crystal_pitch = pitch
        self._crystal_yaw = yaw
        # 简化模型：偏离最优位姿（0,0）越远，效率越低
        deviation = (pitch ** 2 + yaw ** 2) ** 0.5
        self._conversion_efficiency = max(0.0, 0.85 - deviation * 0.05)
        await self._transition(SimState.READY, f"晶体就位，预估效率: {self._conversion_efficiency:.2%}")

    async def optimize_pose(self):
        """自动寻优到最优位姿（用于发射后调整）。"""
        await self._transition(SimState.MOVING, "晶体位姿自动寻优")
        await self._delay(3.0)
        self._crystal_pitch = random.uniform(-0.1, 0.1)
        self._crystal_yaw = random.uniform(-0.1, 0.1)
        self._conversion_efficiency = random.uniform(0.80, 0.88)
        await self._transition(SimState.READY, f"寻优完成，效率: {self._conversion_efficiency:.2%}")

    async def receive_fundamental(self, input_energy: float):
        """
        B05（时序触发后）: 接收基频激光，完成三倍频转换。
        """
        await self._transition(SimState.RUNNING, "频率转换中")
        await self._delay(0.05)
        self._uv_energy = input_energy * self._conversion_efficiency * random.uniform(0.97, 1.03)
        await self._transition(SimState.READY, f"三倍频输出: {self._uv_energy:.2f} J")
        await self._emit("uv_ready", self.name, self._uv_energy)

    async def receive_sampling(self, sampling_config: dict):
        """B13: 接收测量取样系统的采样触发。"""
        self._sampling_data = {
            "uv_energy": self._uv_energy,
            "efficiency": self._conversion_efficiency,
            "config": sampling_config,
        }
        logger.info("PLZ: 采样数据已记录")

    async def reset(self):
        self._uv_energy = 0.0
        self._conversion_efficiency = 0.0
        await super().reset()
