"""
BB — 泵浦分系统模拟器

职责：为预放（YF）和主放（DCF）提供泵浦能量，充电/触发，紧急停充上报（S2）。

状态序列：
  STANDBY → CHARGING → CHARGED → TRIGGERED → STANDBY

安全信号：
  S2 紧急停充 — 充电过程中检测到异常时上报
"""
import asyncio
import logging
import random

from .base import BaseSimulator, SimState

logger = logging.getLogger(__name__)


class BBSimulator(BaseSimulator):
    """泵浦分系统模拟器。"""

    def __init__(self, sim_speed: float = 1.0):
        super().__init__("BB", sim_speed)
        self._energy_setpoint: float = 0.0   # J
        self._charge_voltage: float = 0.0    # kV
        self._charge_progress: float = 0.0   # 0.0–1.0
        self._actual_energy: float = 0.0     # J，触发后测量值

    # ── 属性 ────────────────────────────────────────────────

    @property
    def energy_setpoint(self) -> float:
        return self._energy_setpoint

    @property
    def charge_voltage(self) -> float:
        return self._charge_voltage

    @property
    def charge_progress(self) -> float:
        return self._charge_progress

    @property
    def actual_energy(self) -> float:
        return self._actual_energy

    # ── 命令 ────────────────────────────────────────────────

    async def prepare(self, energy_setpoint: float):
        """
        A06: 泵浦准备。设置能量目标值，完成自检。
        energy_setpoint: 目标泵浦能量（J）
        """
        if self._state == SimState.FAULT:
            raise RuntimeError("BB 处于故障状态，请先 reset")
        self._energy_setpoint = energy_setpoint
        self._charge_progress = 0.0
        self._actual_energy = 0.0
        await self._transition(SimState.RUNNING, f"泵浦自检，能量目标: {energy_setpoint} J")
        await self._delay(1.0)
        await self._transition(SimState.STANDBY, f"泵浦就绪，能量目标: {energy_setpoint} J")

    async def charge(self):
        """
        B07: 充电。模拟电容组充电过程（约 5 秒）。
        充电中随机模拟小概率故障。
        """
        if self._state != SimState.STANDBY:
            raise RuntimeError(f"BB 无法充电，当前状态: {self._state}")

        await self._transition(SimState.RUNNING, "充电中")
        self._charge_voltage = 0.0
        self._charge_progress = 0.0
        target_voltage = self._energy_setpoint * 0.1  # 简化：能量→电压换算

        steps = 10
        for i in range(1, steps + 1):
            await self._delay(0.5)
            self._charge_progress = i / steps
            self._charge_voltage = target_voltage * self._charge_progress

            # 随机故障（0.2% 概率每步，10步 ≈ 2% 整体概率）
            if random.random() < 0.002:
                await self._transition(SimState.FAULT, "充电异常：电容组过压")
                await self._emit_safety("S2", "泵浦充电异常，紧急停充")
                return

        await self._transition(SimState.READY, f"充电完成，电压: {self._charge_voltage:.1f} kV")

    async def trigger(self):
        """
        B07（续）: 触发放电，向放大器输出泵浦能量。
        """
        if self._state != SimState.READY:
            raise RuntimeError(f"BB 未充电完成，无法触发，当前状态: {self._state}")

        await self._transition(SimState.RUNNING, "触发放电")
        await self._delay(0.1)
        # 模拟实际能量（设定值 ±3%）
        self._actual_energy = self._energy_setpoint * random.uniform(0.97, 1.03)
        self._charge_voltage = 0.0
        self._charge_progress = 0.0
        await self._transition(SimState.STANDBY, f"放电完成，实际能量: {self._actual_energy:.1f} J")
        await self._emit("triggered", self.name, self._actual_energy)

    async def emergency_stop(self, reason: str = "外部紧急停充"):
        """S2：紧急停充，中止充电过程。"""
        self._charge_voltage = 0.0
        self._charge_progress = 0.0
        await self._transition(SimState.FAULT, f"紧急停充: {reason}")
        await self._emit_safety("S2", reason)

    async def reset(self):
        self._charge_voltage = 0.0
        self._charge_progress = 0.0
        self._actual_energy = 0.0
        await super().reset()
