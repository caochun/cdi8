"""
JZT — 集中同步 Tango Device。

职责：全装置纳秒级时序同步，66 路触发信号。
  A 阶段：加载发次配方（LoadRecipe）
  B 阶段：加载单次触发配方（LoadSingleShot）、就绪（Arm）、广播触发（Fire）
  C 阶段：复位（Reset）

Fire() 触发后，各子系统在纳秒精度内同时出光（在仿真中以事件广播模拟）。
"""

import json
import logging

from tango import AttrWriteType, DevState
from tango.server import attribute, command

from ._base import SubsystemDevice

logger = logging.getLogger(__name__)


class JZTDevice(SubsystemDevice):
    """集中同步分系统。"""

    currentRecipe = attribute(
        dtype=str,
        access=AttrWriteType.READ,
        doc="当前加载的配方 ID",
    )
    armed = attribute(
        dtype=bool,
        access=AttrWriteType.READ,
        doc="True = 已就绪，等待 Fire 指令",
    )
    triggerCount = attribute(
        dtype=int,
        access=AttrWriteType.READ,
        doc="累计触发次数",
    )

    async def init_device(self):
        await super().init_device()
        self._current_recipe: str = ""
        self._timing_channels: dict = {}
        self._armed: bool = False
        self._trigger_count: int = 0
        self.set_change_event("armed", True, False)

    async def read_currentRecipe(self) -> str:
        return self._current_recipe

    async def read_armed(self) -> bool:
        return self._armed

    async def read_triggerCount(self) -> int:
        return self._trigger_count

    @command(dtype_in=str, doc_in="配方 ID")
    async def LoadRecipe(self, recipe_id: str):
        """A05: 切换测量/预放重频配方。"""
        if self.get_state() == DevState.FAULT:
            raise Exception("JZT 处于故障状态，请先 Reset")
        self.set_state(DevState.RUNNING)
        self.set_status(f"Loading recipe {recipe_id}")
        await self._delay(0.2)
        self._current_recipe = recipe_id
        self._armed = False
        self.set_state(DevState.ON)
        self.set_status(f"Recipe {recipe_id} ready")
        logger.info("JZT: 配方 %s 加载完成", recipe_id)

    @command(dtype_in=str, doc_in="各通道延迟配置（JSON 编码）")
    async def LoadSingleShot(self, channels_json: str):
        """B01: 切换单次触发配方，写入各通道纳秒级延迟。"""
        if self.get_state() == DevState.FAULT:
            raise Exception("JZT 处于故障状态，请先 Reset")
        self.set_state(DevState.RUNNING)
        try:
            self._timing_channels = json.loads(channels_json)
        except json.JSONDecodeError:
            self._timing_channels = {}
        await self._delay(0.1)
        self.set_state(DevState.ON)
        self.set_status(f"Single-shot ready ({len(self._timing_channels)} ch)")
        logger.info("JZT: 单次配方加载，%d 路通道", len(self._timing_channels))

    @command
    async def Arm(self):
        """B: 就绪（允许 Fire 指令）。"""
        if self.get_state() != DevState.ON:
            raise Exception(f"JZT 未就绪（当前 {self.get_state()}）")
        self._armed = True
        self.push_change_event("armed", True)
        self.set_status("Armed, awaiting Fire")
        logger.info("JZT: 已就绪（Arm）")

    @command
    async def Fire(self):
        """B: 广播纳秒级触发脉冲（同步触发全装置出光）。"""
        if not self._armed:
            raise Exception("JZT 未 Arm，无法 Fire")
        self.set_state(DevState.RUNNING)
        self.set_status("Trigger broadcasting")
        await self._delay(0.01)  # 触发序列约 10 ms
        self._trigger_count += 1
        self._armed = False
        self.set_state(DevState.ON)
        self.set_status(f"Trigger done (total {self._trigger_count})")
        self.push_change_event("armed", False)
        logger.info("JZT: 触发广播完成（第 %d 次）", self._trigger_count)

    @command
    async def Reset(self):
        """C04: 同步系统复位。"""
        self._armed = False
        self._current_recipe = ""
        self.set_state(DevState.STANDBY)
        self.set_status("Reset, standby")
        self.push_change_event("armed", False)
        logger.info("JZT: 复位完成")

    @command
    async def LoadShotReadyRecipe(self):
        """A阶段：加载发射准备配方。
        加载用于发射准备阶段的时序配方，配置各通道在预检查阶段
        所需的重频触发序列（如诊断仪器触发、能量监测采样等），
        使全装置进入发射准备时序模式。
        """
        if self.get_state() == DevState.FAULT:
            raise Exception("JZT 处于故障状态，请先 Reset")
        self.set_state(DevState.RUNNING)
        self.set_status("Loading shot-ready recipe")
        await self._delay(0.1)
        self._current_recipe = "SHOT_READY"
        self.set_state(DevState.ON)
        self.set_status("Shot-ready recipe loaded")
        logger.info("JZT: 发射准备配方加载完成")

    @command
    async def LoadMeasureRepRecipe(self):
        """A阶段：加载测量重频配方。
        加载测量系统重频时序配方，驱动测量仪器以重频模式工作，
        用于 A 阶段光束参数在线测量（近场、远场、能量等）。
        """
        if self.get_state() == DevState.FAULT:
            raise Exception("JZT 处于故障状态，请先 Reset")
        self.set_state(DevState.RUNNING)
        self.set_status("Loading measure-rep recipe")
        await self._delay(0.1)
        self._current_recipe = "MEASURE_REP"
        self.set_state(DevState.ON)
        self.set_status("Measure-rep recipe loaded")
        logger.info("JZT: 测量重频配方加载完成")

    @command
    async def LoadPreamplifierRepRecipe(self):
        """A阶段：加载预放重频配方。
        加载预放大链重频时序配方，使预放以重频模式出光，
        用于 A 阶段能量闭环调节和光路准直验证。
        """
        if self.get_state() == DevState.FAULT:
            raise Exception("JZT 处于故障状态，请先 Reset")
        self.set_state(DevState.RUNNING)
        self.set_status("Loading preamp-rep recipe")
        await self._delay(0.1)
        self._current_recipe = "PREAMPLIFIER_REP"
        self.set_state(DevState.ON)
        self.set_status("Preamp-rep recipe loaded")
        logger.info("JZT: 预放重频配方加载完成")

    @command
    async def LoadMeasureSingleRecipe(self):
        """B阶段：加载测量单次配方。
        加载测量系统单次时序配方，配置测量仪器在单次打靶模式下
        的触发延迟和门宽参数，确保采集到完整的打靶脉冲数据。
        """
        if self.get_state() == DevState.FAULT:
            raise Exception("JZT 处于故障状态，请先 Reset")
        self.set_state(DevState.RUNNING)
        self.set_status("Loading measure-single recipe")
        await self._delay(0.05)
        self._current_recipe = "MEASURE_SINGLE"
        self.set_state(DevState.ON)
        self.set_status("Measure-single recipe loaded")
        logger.info("JZT: 测量单次配方加载完成")

    @command
    async def LoadPreamplifierSingleRecipe(self):
        """B阶段：加载预放单次配方。
        加载预放大链单次时序配方，配置单次打靶时预放各级的
        触发延迟，确保预放输出与主放同步，满足打靶时序要求。
        """
        if self.get_state() == DevState.FAULT:
            raise Exception("JZT 处于故障状态，请先 Reset")
        self.set_state(DevState.RUNNING)
        self.set_status("Loading preamp-single recipe")
        await self._delay(0.05)
        self._current_recipe = "PREAMPLIFIER_SINGLE"
        self.set_state(DevState.ON)
        self.set_status("Preamp-single recipe loaded")
        logger.info("JZT: 预放单次配方加载完成")
