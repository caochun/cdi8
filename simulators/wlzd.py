"""
WLZD — 物理实验诊断 Tango Device。

职责：协调诊断设备（X 射线、中子、散射光等），配置采集参数，
      在发次后读取物理信号。DIM 机械手控制也在此范围内。
  A 阶段：配置探测器（SetupDetectors）
  B 阶段：探测器就绪（ArmDetectors）、触发采集（Acquire）
  C 阶段：读取结果（ReadResults）
"""

import json
import logging
import random

from tango import AttrWriteType, DevState
from tango.server import attribute, command

from ._base import SubsystemDevice

logger = logging.getLogger(__name__)


class WLZDDevice(SubsystemDevice):
    """物理实验诊断系统。"""

    detectorCount = attribute(
        dtype=int,
        access=AttrWriteType.READ,
        doc="已配置的探测器数量",
    )
    armed = attribute(
        dtype=bool,
        access=AttrWriteType.READ,
        doc="True = 探测器已就绪，等待触发",
    )
    lastResults = attribute(
        dtype=str,
        access=AttrWriteType.READ,
        doc="最近一次诊断结果（JSON 编码）",
    )

    async def init_device(self):
        await super().init_device()
        self._detector_count: int = 0
        self._armed: bool = False
        self._last_results: dict = {}
        self.set_change_event("armed", True, False)

    async def read_detectorCount(self) -> int:
        return self._detector_count

    async def read_armed(self) -> bool:
        return self._armed

    async def read_lastResults(self) -> str:
        return json.dumps(self._last_results, ensure_ascii=False)

    @command(dtype_in=str, doc_in="诊断配置（JSON 编码）")
    async def SetupDetectors(self, config_json: str):
        """A08: 诊断设备状态确认/参数配置（高压加载、DIM 就位）。"""
        if self.get_state() == DevState.FAULT:
            raise Exception("WLZD 处于故障状态，请先 Reset")
        try:
            config = json.loads(config_json)
        except json.JSONDecodeError:
            config = {}
        self.set_state(DevState.RUNNING)
        self.set_status("探测器配置中")
        await self._delay(0.5)  # 高压加载需要时间
        self._detector_count = config.get("detector_count", 8)
        self.set_state(DevState.ON)
        self.set_status(f"{self._detector_count} 台探测器配置完成")
        logger.info("WLZD: %d 台探测器配置完成", self._detector_count)

    @command
    async def ArmDetectors(self):
        """B06: 探测器就绪（等待触发信号）。"""
        if self.get_state() != DevState.ON:
            raise Exception(f"WLZD 未就绪（当前 {self.get_state()}）")
        self._armed = True
        self.set_status("探测器已就绪，等待触发")
        self.push_change_event("armed", True)
        logger.info("WLZD: 探测器已就绪（Arm）")

    @command
    async def Acquire(self):
        """C07: 触发采集（通知探测器从缓冲区读取本次触发数据）。"""
        self.set_state(DevState.RUNNING)
        self.set_status("触发后数据采集中")
        await self._delay(0.3)
        self._armed = False
        self.set_state(DevState.ON)
        self.set_status("采集完成，数据就绪")
        self.push_change_event("armed", False)
        logger.info("WLZD: 触发后采集完成")

    @command(dtype_out=str, doc_out="物理诊断结果（JSON 编码）")
    async def ReadResults(self) -> str:
        """C07: 读取物理信号（X 射线、中子产额、γ 信号）。"""
        self.set_state(DevState.RUNNING)
        self.set_status("读取诊断数据中")
        await self._delay(0.5)

        self._last_results = {
            "xray_signal": round(random.uniform(0.5, 5.0), 3),
            "neutron_count": int(random.uniform(1000, 10000)),
            "gamma_signal": round(random.uniform(0.1, 2.0), 3),
            "scattered_light_ratio": round(random.uniform(0.01, 0.05), 4),
            "dim_position_ok": True,
        }
        self.set_state(DevState.ON)
        self.set_status("诊断数据读取完成")
        result_json = json.dumps(self._last_results, ensure_ascii=False)
        logger.info("WLZD: 诊断完成，中子产额 %d", self._last_results["neutron_count"])
        return result_json

    @command(dtype_in=str, doc_in="动作名称：'收回'/'就位'/'试触发'/'上电'")
    async def ExecuteDeviceAction(self, action: str):
        """B阶段：诊断设备动作。
        控制物理诊断设备执行指定动作，支持以下操作：
        - '收回'：将 DIM 机械手等诊断仪器退出靶室光路；
        - '就位'：将诊断仪器推入靶室指定测量位置；
        - '试触发'：对探测器进行测试触发，验证采集链路；
        - '上电'：为高压探测器（MCP/PIN 等）施加工作高压。
        在 B 阶段根据诊断方案选择执行对应动作。
        """
        if self.get_state() == DevState.FAULT:
            raise Exception(f"WLZD 处于故障状态，无法执行动作 {action}")
        self.set_state(DevState.MOVING)
        self.set_status(f"执行诊断设备动作: {action}")
        await self._delay(0.5)
        self.set_state(DevState.ON)
        self.set_status(f"诊断设备动作完成: {action}")
        logger.info("WLZD: 诊断设备动作 '%s' 完成", action)

    @command
    async def PostShotProcessing(self):
        """C阶段：发射后处理。
        发次完成后对物理诊断系统执行后处理动作序列：
        1. 退出探测器高压（退高压）；
        2. 将 DIM 机械手等设备从靶室收回（收回）；
        3. 关闭探测器工作电源（下电）。
        确保靶室内诊断设备在后续操作前处于安全状态。
        """
        self.set_state(DevState.MOVING)
        self.set_status("发射后处理：退高压/收回/下电")
        await self._delay(1.0)
        self._armed = False
        self.set_state(DevState.STANDBY)
        self.set_status("发射后处理完成，设备已收回")
        self.push_change_event("armed", False)
        logger.info("WLZD: 发射后处理完成")
