"""
YF — 再生与双程放大（预放）模拟器

职责：激光预放大，能量粗/精闭环控制。
接收泵浦能量（BB），接受集中同步时序（JZT B03），发射后接受冷空吹扫（LK C04）。

状态序列：
  STANDBY → WARMING_UP → READY → RUNNING → READY
"""
import asyncio
import logging
import random

from .base import BaseSimulator, SimState

logger = logging.getLogger(__name__)


class YFSimulator(BaseSimulator):
    """再生与双程放大（预放）模拟器。"""

    def __init__(self, sim_speed: float = 1.0):
        super().__init__("YF", sim_speed)
        self._energy_setpoint: float = 0.0   # J
        self._measured_energy: float = 0.0   # J
        self._loop_closed: bool = False
        self._temperature: float = 20.0      # °C，片放温度

    # ── 属性 ────────────────────────────────────────────────

    @property
    def energy_setpoint(self) -> float:
        return self._energy_setpoint

    @property
    def measured_energy(self) -> float:
        return self._measured_energy

    @property
    def loop_closed(self) -> bool:
        return self._loop_closed

    @property
    def temperature(self) -> float:
        return self._temperature

    # ── 命令 ────────────────────────────────────────────────

    async def wake_up(self):
        """
        A03: 唤醒/闭环。从待机状态激活预放，进行预热。
        """
        if self._state == SimState.FAULT:
            raise RuntimeError("YF 处于故障状态，请先 reset")
        await self._transition(SimState.RUNNING, "预放预热中")
        # 模拟预热（温度从室温升至工作温度）
        for t in range(0, 6):
            await self._delay(0.5)
            self._temperature = 20.0 + t * 3.5
        await self._transition(SimState.READY, f"预放就绪，温度: {self._temperature:.1f}°C")

    async def set_energy_setpoint(self, value: float):
        """设置能量目标值（J）。"""
        self._energy_setpoint = value
        logger.info("YF: 能量目标设为 %.2f J", value)

    async def start_closed_loop(self):
        """启动能量闭环控制。"""
        if self._state != SimState.READY:
            raise RuntimeError(f"YF 未就绪，无法启动闭环，当前状态: {self._state}")
        self._loop_closed = True
        logger.info("YF: 能量闭环已启动，目标 %.2f J", self._energy_setpoint)

    async def receive_pump_energy(self, pump_energy: float):
        """
        B08: 接受泵浦能量注入，更新测量能量。
        由 BB trigger 后调用（或由集中管控协调）。
        """
        await self._transition(SimState.RUNNING, "接受泵浦能量，放大中")
        await self._delay(0.1)
        # 模拟增益：输入泵浦能量 → 输出激光能量（简化线性模型 + 噪声）
        gain = 0.15
        self._measured_energy = pump_energy * gain * random.uniform(0.95, 1.05)
        if self._loop_closed and self._energy_setpoint > 0:
            # 闭环：校正到设定值
            self._measured_energy = self._energy_setpoint * random.uniform(0.98, 1.02)
        await self._transition(SimState.READY, f"放大完成，输出能量: {self._measured_energy:.3f} J")
        await self._emit("energy_ready", self.name, self._measured_energy)

    async def accept_purge(self):
        """C04: 接受冷空吹扫（发射后冷却）。"""
        await self._transition(SimState.RUNNING, "片放吹扫冷却中")
        await self._delay(2.0)
        self._temperature = max(20.0, self._temperature - 5.0)
        self._loop_closed = False
        await self._transition(SimState.STANDBY, f"吹扫完成，温度: {self._temperature:.1f}°C")

    async def reset(self):
        self._loop_closed = False
        self._measured_energy = 0.0
        await super().reset()
