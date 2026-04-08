"""
LDB — 液氘靶分系统 Tango Device。

职责：液态氘（Liquid Deuterium）靶丸的开罩与就位支持。
B 阶段开罩，靶丸暴露在激光焦点处。
"""

import logging

from tango import AttrWriteType, DevState
from tango.server import attribute, command

from ._base import SubsystemDevice

logger = logging.getLogger(__name__)


class LDBDevice(SubsystemDevice):
    """LD 靶分系统。"""

    coverOpen = attribute(
        dtype=bool,
        access=AttrWriteType.READ,
        doc="True = 靶丸罩盖已开启",
    )
    targetTemperature = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="K",
        doc="LD 靶温度（液氘约 23 K）",
    )

    async def init_device(self):
        await super().init_device()
        self._cover_open: bool = False
        self._target_temperature: float = 23.5  # K，液氘工作温度

    async def read_coverOpen(self) -> bool:
        return self._cover_open

    async def read_targetTemperature(self) -> float:
        return self._target_temperature

    @command
    async def OpenCover(self):
        """B08: LD 靶开罩，靶丸就位。"""
        if self.get_state() == DevState.FAULT:
            raise Exception("LDB 处于故障状态，请先 Reset")
        self.set_state(DevState.MOVING)
        self.set_status("LD 靶开罩中")
        await self._delay(1.5)
        self._cover_open = True
        self.set_state(DevState.ON)
        self.set_status(f"靶丸已就位，温度 {self._target_temperature:.1f} K")
        logger.info("LDB: LD 靶开罩完成，温度 %.1f K", self._target_temperature)

    @command
    async def CloseCover(self):
        """C: LD 靶收靶（复位）。"""
        self.set_state(DevState.MOVING)
        self.set_status("LD 靶收靶中")
        await self._delay(1.0)
        self._cover_open = False
        self.set_state(DevState.STANDBY)
        self.set_status("LD 靶已收回，待机")
        logger.info("LDB: LD 靶收靶完成")

    @command
    async def SimulateOpenCover(self):
        """A/B阶段：模拟 LD 靶开罩（仿真模式）。
        在仿真测试模式下模拟 LD 靶开罩动作，跳过实体低温靶的
        制冷等待和机械动作，直接将状态置为"已开罩"，
        用于控制系统集成调试和无液氘条件下的流程测试。
        """
        self._cover_open = True
        self.set_state(DevState.ON)
        self.set_status("LD 靶开罩（仿真）完成")
        logger.info("LDB: 模拟 LD 靶开罩完成")

    @command
    async def Reset(self):
        """A阶段：LD 靶分系统复位。
        将 LD 靶分系统复位至初始状态：
        关闭靶罩、停止制冷循环监控、清除故障标志，
        为下一发次的液氘靶装载做准备。
        """
        self._cover_open = False
        await super().Reset()
        logger.info("LDB: LD 靶复位完成")
