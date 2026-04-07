"""
WLZD — 物理实验诊断模拟器

职责：探测打靶产生的 X 射线/中子/γ 射线（A11/B14/B15/C02）。
发射后将物理信号上报集中管控（C02）。

状态序列：
  STANDBY → CONFIGURING → ARMED → DATA_READY
"""
import asyncio
import logging
import random

from .base import BaseSimulator, SimState

logger = logging.getLogger(__name__)


class WLZDSimulator(BaseSimulator):
    """物理实验诊断子系统模拟器。"""

    def __init__(self, sim_speed: float = 1.0):
        super().__init__("WLZD", sim_speed)
        self._detector_config: dict = {}
        self._detectors_armed: bool = False
        self._xray_signal: float = 0.0       # 任意单位
        self._neutron_count: int = 0
        self._gamma_signal: float = 0.0

    @property
    def detector_status(self) -> str:
        if self._detectors_armed:
            return "ARMED"
        elif self._state == SimState.RUNNING:
            return "CONFIGURING"
        return "IDLE"

    @property
    def xray_signal(self) -> float:
        return self._xray_signal

    @property
    def neutron_count(self) -> int:
        return self._neutron_count

    @property
    def gamma_signal(self) -> float:
        return self._gamma_signal

    async def setup_detectors(self, config: dict):
        """
        A11: 状态确认（诊断系统侧）。配置探测器参数。
        """
        if self._state == SimState.FAULT:
            raise RuntimeError("WLZD 处于故障状态，请先 reset")
        await self._transition(SimState.RUNNING, "配置探测器")
        await self._delay(1.0)
        self._detector_config = config
        self._detectors_armed = False
        await self._transition(SimState.READY, "探测器已配置")

    async def arm_detectors(self):
        """
        B14: 采集设置/动作。探测器进入待触发状态（B15 就位）。
        """
        if self._state != SimState.READY:
            raise RuntimeError(f"WLZD 未配置，无法 Arm，当前状态: {self._state}")
        await self._delay(0.5)
        self._detectors_armed = True
        await self._transition(SimState.READY, "探测器已 Armed，等待触发")
        await self._emit("detectors_positioned", self.name)

    async def acquire(self):
        """打靶触发后，采集物理信号。由集中管控在发射后调用。"""
        if not self._detectors_armed:
            logger.warning("WLZD: 探测器未 Armed，采集可能无效")
        await self._transition(SimState.RUNNING, "采集物理信号")
        await self._delay(0.5)

        # 模拟：假设实验成功时有明显信号
        self._xray_signal = random.uniform(0.5, 10.0)
        self._neutron_count = random.randint(100, 10000)
        self._gamma_signal = random.uniform(0.1, 2.0)
        self._detectors_armed = False

        await self._transition(SimState.READY, (
            f"采集完成 | X-ray: {self._xray_signal:.2f} | "
            f"Neutron: {self._neutron_count} | γ: {self._gamma_signal:.2f}"
        ))
        await self._emit("data_ready", self.name, self._get_results())

    async def read_results(self) -> dict:
        """C02: 读取物理信号（集中管控调用）。"""
        return self._get_results()

    def _get_results(self) -> dict:
        return {
            "xray_signal": self._xray_signal,
            "neutron_count": self._neutron_count,
            "gamma_signal": self._gamma_signal,
        }

    async def reset(self):
        self._detectors_armed = False
        self._xray_signal = 0.0
        self._neutron_count = 0
        self._gamma_signal = 0.0
        await super().reset()
