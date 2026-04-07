"""
ZZY — 光纤种子源模拟器
EPJ — 二倍频宽带注入模拟器

两者接口相似（出光/禁光），合并在同一文件。
"""
import asyncio
import logging
import random

from .base import BaseSimulator, SimState

logger = logging.getLogger(__name__)


class ZZYSimulator(BaseSimulator):
    """光纤种子源模拟器。产生初始激光种子，控制出光/待机。"""

    def __init__(self, sim_speed: float = 1.0):
        super().__init__("ZZY", sim_speed)
        self._output_enabled: bool = False
        self._output_power: float = 0.0   # mW
        self._wavelength: float = 1053.0  # nm，Nd:Glass 基频

    @property
    def output_enabled(self) -> bool:
        return self._output_enabled

    @property
    def output_power(self) -> float:
        return self._output_power

    @property
    def wavelength(self) -> float:
        return self._wavelength

    async def enable_output(self):
        """A01: 出光指令。"""
        if self._state == SimState.FAULT:
            raise RuntimeError("ZZY 处于故障状态")
        await self._transition(SimState.RUNNING, "种子源启动中")
        await self._delay(1.0)
        self._output_enabled = True
        self._output_power = random.uniform(9.5, 10.5)   # mW
        await self._transition(SimState.READY, f"种子源出光，功率: {self._output_power:.2f} mW")

    async def disable_output(self):
        """禁光/待机。"""
        self._output_enabled = False
        self._output_power = 0.0
        await self._transition(SimState.STANDBY, "种子源待机")

    async def reset(self):
        self._output_enabled = False
        self._output_power = 0.0
        await super().reset()


class EPJSimulator(BaseSimulator):
    """二倍频宽带注入模拟器。种子光倍频后注入放大链。"""

    def __init__(self, sim_speed: float = 1.0):
        super().__init__("EPJ", sim_speed)
        self._output_enabled: bool = False
        self._injection_power: float = 0.0   # mW
        self._conversion_efficiency: float = 0.0

    @property
    def output_enabled(self) -> bool:
        return self._output_enabled

    @property
    def injection_power(self) -> float:
        return self._injection_power

    @property
    def conversion_efficiency(self) -> float:
        return self._conversion_efficiency

    async def enable_output(self):
        """A02: 出光指令（二倍频注入）。"""
        if self._state == SimState.FAULT:
            raise RuntimeError("EPJ 处于故障状态")
        await self._transition(SimState.RUNNING, "倍频注入启动中")
        await self._delay(0.8)
        self._output_enabled = True
        self._conversion_efficiency = random.uniform(0.45, 0.55)
        self._injection_power = 10.0 * self._conversion_efficiency  # 假设种子 10 mW
        await self._transition(SimState.READY, (
            f"注入就绪，效率: {self._conversion_efficiency:.2%}，功率: {self._injection_power:.2f} mW"
        ))

    async def disable_output(self):
        self._output_enabled = False
        self._injection_power = 0.0
        await self._transition(SimState.STANDBY, "倍频注入待机")

    async def reset(self):
        self._output_enabled = False
        self._injection_power = 0.0
        await super().reset()
