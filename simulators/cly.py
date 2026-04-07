"""
CLY — 测量取样模拟器

职责：束线采样，测量激光能量/波形/近远场（A12/B06/B13/C01）。
发射后将激光参数上报集中管控（C01）。

状态序列：
  STANDBY → CONFIGURED → SAMPLING → DATA_READY
"""
import asyncio
import logging
import random

from .base import BaseSimulator, SimState

logger = logging.getLogger(__name__)


class CLYSimulator(BaseSimulator):
    """测量取样子系统模拟器。"""

    def __init__(self, sim_speed: float = 1.0):
        super().__init__("CLY", sim_speed)
        self._sampling_config: dict = {}
        self._energy: float = 0.0           # J，测量能量
        self._waveform: list[float] = []    # 时域波形（简化为若干采样点）
        self._near_field: str = ""          # 近场图像路径（模拟）
        self._far_field: str = ""           # 远场图像路径（模拟）

    @property
    def energy(self) -> float:
        return self._energy

    @property
    def waveform(self) -> list[float]:
        return list(self._waveform)

    @property
    def near_field(self) -> str:
        return self._near_field

    @property
    def far_field(self) -> str:
        return self._far_field

    async def setup(self, config: dict):
        """
        A12: 靶瞄准备（测量系统侧），配置采样参数。
        config 可含 gate_width, trigger_delay, channels 等。
        """
        if self._state == SimState.FAULT:
            raise RuntimeError("CLY 处于故障状态，请先 reset")
        await self._transition(SimState.RUNNING, "配置采样参数")
        await self._delay(0.5)
        self._sampling_config = config
        await self._transition(SimState.READY, "采样已配置")

    async def start_sampling(self):
        """触发采样（由时序 B06 触发后调用）。"""
        if self._state not in (SimState.READY, SimState.RUNNING):
            raise RuntimeError(f"CLY 未配置，无法采样，当前状态: {self._state}")
        await self._transition(SimState.RUNNING, "采样中")
        await self._delay(0.3)

        # 模拟测量结果
        self._energy = random.uniform(1.5, 2.5)     # kJ
        self._waveform = [random.gauss(1.0, 0.05) for _ in range(100)]
        self._near_field = "sim://near_field_snapshot.png"
        self._far_field = "sim://far_field_snapshot.png"

        await self._transition(SimState.READY, f"采样完成，能量: {self._energy:.3f} kJ")
        await self._emit("data_ready", self.name, self._get_results())

    async def read_results(self) -> dict:
        """C01: 读取测量结果（集中管控调用）。"""
        return self._get_results()

    def _get_results(self) -> dict:
        return {
            "energy_kj": self._energy,
            "waveform_points": len(self._waveform),
            "near_field": self._near_field,
            "far_field": self._far_field,
        }

    async def reset(self):
        self._energy = 0.0
        self._waveform = []
        await super().reset()
