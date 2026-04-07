"""
DCF — 多程放大（主放）模拟器

职责：主放大，光路准直，打靶状态确认。
接收泵浦能量（BB B09）和开关触发（KG B11）。

状态序列：
  STANDBY → ALIGNING → ALIGNED → READY
"""
import asyncio
import logging
import random

from .base import BaseSimulator, SimState

logger = logging.getLogger(__name__)


class DCFSimulator(BaseSimulator):
    """多程放大（主放）模拟器。"""

    def __init__(self, sim_speed: float = 1.0):
        super().__init__("DCF", sim_speed)
        self._alignment_status: str = "未准直"
        self._alignment_error: float = 999.0   # μrad，对准误差
        self._shot_ready: bool = False
        self._output_energy: float = 0.0        # J

    # ── 属性 ────────────────────────────────────────────────

    @property
    def alignment_status(self) -> str:
        return self._alignment_status

    @property
    def alignment_error(self) -> float:
        return self._alignment_error

    @property
    def shot_ready(self) -> bool:
        return self._shot_ready

    @property
    def output_energy(self) -> float:
        return self._output_energy

    # ── 命令 ────────────────────────────────────────────────

    async def align(self):
        """
        A04: 光路准直。执行自动准直程序，收敛到目标对准误差 < 5 μrad。
        """
        if self._state == SimState.FAULT:
            raise RuntimeError("DCF 处于故障状态，请先 reset")

        await self._transition(SimState.MOVING, "光路准直中")
        self._shot_ready = False
        self._alignment_error = 999.0

        # 模拟迭代准直收敛
        for i in range(5):
            await self._delay(0.6)
            self._alignment_error = 100.0 / (2 ** i) * random.uniform(0.8, 1.2)
            logger.info("DCF: 准直迭代 %d，误差 %.1f μrad", i + 1, self._alignment_error)

        if self._alignment_error < 10.0:
            self._alignment_status = "准直完成"
            await self._transition(SimState.READY, f"准直完成，误差: {self._alignment_error:.2f} μrad")
        else:
            # 小概率准直失败
            self._alignment_status = "准直失败"
            await self._transition(SimState.FAULT, f"准直失败，误差 {self._alignment_error:.1f} μrad 超限（阈值 10 μrad）")

    async def confirm_ready(self):
        """
        A04（确认）: 操作员/系统确认主放就绪，可以进入发射阶段。
        """
        if self._state != SimState.READY:
            raise RuntimeError(f"DCF 未就绪，无法确认，当前状态: {self._state}")
        self._shot_ready = True
        logger.info("DCF: 打靶状态已确认")

    async def receive_pump_and_fire(self, pump_energy: float):
        """
        B09 + B11: 接受泵浦能量并触发电光开关，完成主放。
        实际由时序系统协调，软件层模拟结果。
        """
        if not self._shot_ready:
            raise RuntimeError("DCF 未确认就绪，无法放大")

        await self._transition(SimState.RUNNING, "主放激光放大中")
        await self._delay(0.1)
        # 主放增益远高于预放，简化模拟
        gain = 8.0
        self._output_energy = pump_energy * gain * random.uniform(0.96, 1.04)
        await self._transition(SimState.READY, f"主放完成，输出能量: {self._output_energy:.1f} J")
        await self._emit("energy_ready", self.name, self._output_energy)

    async def reset(self):
        self._shot_ready = False
        self._alignment_error = 999.0
        self._alignment_status = "未准直"
        self._output_energy = 0.0
        await super().reset()
