"""
LK — 冷空系统 Tango Device。

职责：为片状放大器提供冷气吹扫（C 阶段后处理），
      防止闪光灯发射后玻璃片过热。
"""

import logging

from tango import AttrWriteType, DevState
from tango.server import attribute, command

from ._base import SubsystemDevice

logger = logging.getLogger(__name__)


class LKDevice(SubsystemDevice):
    """冷空（片放吹扫）系统。"""

    purging = attribute(
        dtype=bool,
        access=AttrWriteType.READ,
        doc="True = 吹扫气流正在运行",
    )
    flowRate = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="L/min",
        doc="冷气流量",
    )

    async def init_device(self):
        await super().init_device()
        self._purging: bool = False
        self._flow_rate: float = 0.0
        self.set_change_event("purging", True, False)

    async def read_purging(self) -> bool:
        return self._purging

    async def read_flowRate(self) -> float:
        return self._flow_rate

    @command
    async def StartPurge(self):
        """C10: 开启片放吹扫气流。"""
        if self.get_state() == DevState.FAULT:
            raise Exception("LK 处于故障状态，请先 Reset")
        self._purging = True
        self._flow_rate = 150.0  # L/min
        self.set_state(DevState.RUNNING)
        self.set_status(f"吹扫气流已开启，流量 {self._flow_rate:.0f} L/min")
        self.push_change_event("purging", True)
        logger.info("LK: 吹扫气流开启")

    @command
    async def StopPurge(self):
        """C10: 关闭片放吹扫气流。"""
        self._purging = False
        self._flow_rate = 0.0
        self.set_state(DevState.STANDBY)
        self.set_status("吹扫气流已关闭")
        self.push_change_event("purging", False)
        logger.info("LK: 吹扫气流关闭")

    @command(dtype_in=str, doc_in="吹扫控制指令：'开始'/'结束'/'关机'")
    async def PurgeControl(self, action: str):
        """片放吹扫控制。
        通过统一接口控制冷空吹扫系统的运行状态：
        - '开始'：开启吹扫气流，驱动冷空对片状放大介质进行冷却，
                  等效于 StartPurge()；
        - '结束'：关闭吹扫气流，停止冷空流动，
                  等效于 StopPurge()；
        - '关机'：将冷空系统完全关机，停止压缩机和风机，
                  用于系统计划停机或紧急停机后的安全关断。
        """
        if action == "开始":
            await self.StartPurge()
        elif action == "结束":
            await self.StopPurge()
        elif action == "关机":
            self._purging = False
            self._flow_rate = 0.0
            self.set_state(DevState.STANDBY)
            self.set_status("冷空系统已关机")
            self.push_change_event("purging", False)
            logger.info("LK: 冷空系统已关机")
        else:
            raise Exception(f"LK: 未知吹扫控制指令 '{action}'，支持: 开始/结束/关机")
