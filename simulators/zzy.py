"""
ZZY — 光纤种子源 Tango Device。

职责：产生初始激光脉冲种子，控制出光/禁光。
A 阶段启动，C 阶段关断。
"""

import json
import logging
import random

from tango import AttrWriteType, DevState
from tango.server import attribute, command

from ._base import SubsystemDevice

logger = logging.getLogger(__name__)


class ZZYDevice(SubsystemDevice):
    """光纤种子源。"""

    outputPower = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="W",
        doc="当前输出光功率",
    )

    async def init_device(self):
        await super().init_device()
        self._output_power: float = 0.0
        self.set_change_event("outputPower", True, False)

    async def read_outputPower(self) -> float:
        return self._output_power

    @command
    async def EnableOutput(self):
        """A01: 种子源出光指令。"""
        if self.get_state() == DevState.FAULT:
            raise Exception("ZZY 处于故障状态，请先 Reset")
        self.set_state(DevState.RUNNING)
        self.set_status("种子光输出中")
        await self._delay(0.5)
        self._output_power = 50.0  # mW 级种子光
        self.set_state(DevState.ON)
        self.set_status(f"出光正常，功率: {self._output_power:.1f} mW")
        self.push_change_event("outputPower", self._output_power)
        logger.info("ZZY: 种子源出光，功率 %.1f mW", self._output_power)

    @command
    async def DisableOutput(self):
        """C01: 禁光/待机。"""
        self._output_power = 0.0
        self.set_state(DevState.STANDBY)
        self.set_status("已禁光，待机")
        self.push_change_event("outputPower", self._output_power)
        logger.info("ZZY: 种子源禁光")

    @command(dtype_out=str, doc_out="种子源激光参数（JSON 编码）")
    async def ReadLaserParameters(self) -> str:
        """C阶段：种子源激光参数采集。
        采集种子源当前输出的激光参数，包含功率、脉冲宽度、重频等，
        供发次后数据归档使用。
        """
        params = {
            "output_power_mw": round(self._output_power, 2),
            "pulse_width_ps": round(random.uniform(100.0, 200.0), 1),
            "repetition_rate_hz": 1.0,
            "wavelength_nm": 1053.0,
            "mode": "single",
        }
        logger.info("ZZY: 激光参数采集完成，功率 %.2f mW", self._output_power)
        return json.dumps(params, ensure_ascii=False)

    @command
    async def Standby(self):
        """C阶段：种子源待机。
        发次结束后将种子源切换至低功耗待机状态，
        关闭出光但保持泵浦电源工作以便快速重启。
        """
        self._output_power = 0.0
        self.set_state(DevState.STANDBY)
        self.set_status("种子源待机中")
        self.push_change_event("outputPower", self._output_power)
        logger.info("ZZY: 切换待机状态")
