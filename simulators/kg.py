"""
KG — 开关驱动源模拟器

职责：控制电光开关（Pockels cell）充电与触发（B10/B11）。

状态序列：
  STANDBY → CHARGING → CHARGED → TRIGGERED → STANDBY
"""
import asyncio
import logging
import random

from .base import BaseSimulator, SimState

logger = logging.getLogger(__name__)


class KGSimulator(BaseSimulator):
    """开关驱动源模拟器。"""

    def __init__(self, sim_speed: float = 1.0):
        super().__init__("KG", sim_speed)
        self._charge_voltage: float = 0.0   # kV
        self._trigger_delay: float = 0.0    # ns，相对于时序基准的触发延迟

    @property
    def charge_voltage(self) -> float:
        return self._charge_voltage

    @property
    def trigger_delay(self) -> float:
        return self._trigger_delay

    async def charge(self, target_voltage: float = 8.0, trigger_delay: float = 0.0):
        """
        B10: 充电至目标电压。
        target_voltage: kV
        trigger_delay: ns
        """
        if self._state == SimState.FAULT:
            raise RuntimeError("KG 处于故障状态，请先 reset")
        self._trigger_delay = trigger_delay
        await self._transition(SimState.RUNNING, f"充电中，目标: {target_voltage} kV")

        steps = 5
        for i in range(1, steps + 1):
            await self._delay(0.3)
            self._charge_voltage = target_voltage * i / steps

        await self._transition(SimState.READY, f"充电完成: {self._charge_voltage:.1f} kV")

    async def trigger(self):
        """
        B11: 触发电光开关，向主放（DCF）输出触发脉冲。
        """
        if self._state != SimState.READY:
            raise RuntimeError(f"KG 未充电完成，无法触发，当前状态: {self._state}")
        await self._transition(SimState.RUNNING, "触发脉冲输出")
        await self._delay(0.02)
        self._charge_voltage = 0.0
        await self._transition(SimState.STANDBY, "触发完成")
        await self._emit("triggered", self.name)

    async def reset(self):
        self._charge_voltage = 0.0
        await super().reset()
