"""
EPJ — 二倍频宽带注入 Tango Device。

职责：将种子光倍频后注入预放大链。
接口与 ZZY 对称（出光/禁光）。
"""

import json
import logging
import random

from tango import AttrWriteType, DevState
from tango.server import attribute, command

from ._base import SubsystemDevice

logger = logging.getLogger(__name__)


class EPJDevice(SubsystemDevice):
    """二倍频宽带注入源。"""

    outputPower = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="W",
        doc="倍频注入光功率",
    )
    wavelength = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="nm",
        doc="输出波长（二倍频 ~527 nm）",
    )

    async def init_device(self):
        await super().init_device()
        self._output_power: float = 0.0
        self._wavelength: float = 527.0  # nm，1053/2
        self.set_change_event("outputPower", True, False)

    async def read_outputPower(self) -> float:
        return self._output_power

    async def read_wavelength(self) -> float:
        return self._wavelength

    @command
    async def EnableOutput(self):
        """A02: 二倍频出光指令。"""
        if self.get_state() == DevState.FAULT:
            raise Exception("EPJ 处于故障状态，请先 Reset")
        self.set_state(DevState.RUNNING)
        self.set_status("2w injection active")
        await self._delay(0.5)
        self._output_power = 30.0  # mW 级
        self.set_state(DevState.ON)
        self.set_status(f"Injection OK, power: {self._output_power:.1f} mW")
        self.push_change_event("outputPower", self._output_power)
        logger.info("EPJ: 二倍频注入出光，功率 %.1f mW", self._output_power)

    @command
    async def DisableOutput(self):
        """C02: 禁光/待机。"""
        self._output_power = 0.0
        self.set_state(DevState.STANDBY)
        self.set_status("Output disabled, standby")
        self.push_change_event("outputPower", self._output_power)
        logger.info("EPJ: 二倍频注入禁光")

    @command(dtype_out=str, doc_out="二倍频激光参数（JSON 编码）")
    async def ReadLaserParameters(self) -> str:
        """C阶段：二倍频激光参数采集。
        采集二倍频宽带注入源的激光参数，包含功率、波长、带宽等，
        供发次后数据归档使用。
        """
        params = {
            "output_power_mw": round(self._output_power, 2),
            "wavelength_nm": round(self._wavelength, 2),
            "bandwidth_ghz": round(random.uniform(100.0, 300.0), 1),
            "pulse_width_ps": round(random.uniform(1000.0, 3000.0), 1),
            "injection_efficiency": round(random.uniform(0.85, 0.98), 3),
        }
        logger.info("EPJ: 激光参数采集完成，功率 %.2f mW，波长 %.1f nm",
                    self._output_power, self._wavelength)
        return json.dumps(params, ensure_ascii=False)

    @command
    async def Standby(self):
        """C阶段：二倍频待机。
        发次结束后将二倍频注入源切换至待机状态，
        关闭注入光输出，保留倍频晶体温度控制。
        """
        self._output_power = 0.0
        self.set_state(DevState.STANDBY)
        self.set_status("2w injection standby")
        self.push_change_event("outputPower", self._output_power)
        logger.info("EPJ: 切换待机状态")
