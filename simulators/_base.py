"""
SubsystemDevice — 所有子系统 Tango Device 的基类。

符合 GXLF 集中管控接口规范（需求分析报告）：
  - adminMode / controlMode    : 服务权限管理（预约→签到→控制→签退）
  - selfCheckState/Status      : 自检状态
  - healthState/Status         : 健康度上报（用于 JZGK 订阅）
  - simSpeed                   : 仿真速度倍率（运行时可写）

安全信号上报约定：
  子类如需发出 S1/S2/S3 安全信号，须：
    1. 声明 safetySignal 属性并在 init_device 中注册变更事件
    2. 调用 await self._emit_safety(signal_id, message)

Tango 状态映射（DevState）：
  STANDBY  → 初始化完成，等待指令
  RUNNING  → 正在执行操作（充电、运动、加热等）
  ON       → 就绪，可接收下一指令（Tango 惯例：ON = ready）
  FAULT    → 故障，需 Reset 后才能继续
  OFF      → 已关断
  MOVING   → 运动中（靶瞄/晶体调姿等机械动作）
"""

import asyncio
import logging

from tango import AttrWriteType, DevState, DevUShort, GreenMode
from tango.server import Device, attribute, command

logger = logging.getLogger(__name__)


class SubsystemDevice(Device):
    """GXLF 子系统 Tango Device 基类（GreenMode.Asyncio）。"""

    green_mode = GreenMode.Asyncio

    # ── 公共属性（接口规范定义）──────────────────────────────

    adminMode = attribute(
        dtype=bool,
        access=AttrWriteType.READ_WRITE,
        doc="True = 允许远程控制（服务权限签到后置 True）",
    )
    controlMode = attribute(
        dtype=str,
        access=AttrWriteType.READ_WRITE,
        doc="当前控制权持有者标识（集中管控写入自身 ID）",
    )
    selfCheckState = attribute(
        dtype=DevUShort,
        access=AttrWriteType.READ,
        doc="0=未自检 1=自检中 2=通过 3=失败",
    )
    selfCheckStatus = attribute(
        dtype=str,
        access=AttrWriteType.READ,
    )
    healthState = attribute(
        dtype=DevUShort,
        access=AttrWriteType.READ,
        doc="0=正常 1=降级 2=故障",
    )
    healthStatus = attribute(
        dtype=str,
        access=AttrWriteType.READ,
    )
    simSpeed = attribute(
        dtype=float,
        access=AttrWriteType.READ_WRITE,
        doc="仿真速度倍率（1.0=实时，10.0=10倍速）",
    )

    # ── 初始化 ───────────────────────────────────────────────

    async def init_device(self):
        await super().init_device()
        self._admin_mode: bool = False
        self._control_mode: str = ""
        self._self_check_state: int = 0
        self._self_check_status: str = "未自检"
        self._health_state: int = 0
        self._health_status: str = "正常"
        self._sim_speed: float = 1.0

        self.set_state(DevState.STANDBY)
        self.set_status("Initialized")

        # 注册变更事件（客户端可订阅）
        self.set_change_event("State", True, False)
        self.set_change_event("Status", True, False)
        self.set_change_event("healthState", True, False)
        self.set_change_event("healthStatus", True, False)

    # ── 属性读写 ─────────────────────────────────────────────

    async def read_adminMode(self) -> bool:
        return self._admin_mode

    async def write_adminMode(self, value: bool):
        self._admin_mode = value

    async def read_controlMode(self) -> str:
        return self._control_mode

    async def write_controlMode(self, value: str):
        self._control_mode = value

    async def read_selfCheckState(self) -> int:
        return self._self_check_state

    async def read_selfCheckStatus(self) -> str:
        return self._self_check_status

    async def read_healthState(self) -> int:
        return self._health_state

    async def read_healthStatus(self) -> str:
        return self._health_status

    async def read_simSpeed(self) -> float:
        return self._sim_speed

    async def write_simSpeed(self, value: float):
        self._sim_speed = max(0.01, float(value))
        logger.info("%s: simSpeed → %.2f", self.get_name(), self._sim_speed)

    # ── 通用命令 ─────────────────────────────────────────────

    @command
    async def Reset(self):
        """从 FAULT 状态复位到 STANDBY。"""
        if self.get_state() != DevState.FAULT:
            logger.debug("%s: Reset 忽略（非 FAULT，当前 %s）", self.get_name(), self.get_state())
            return
        self._health_state = 0
        self._health_status = "Reset done"
        self.set_state(DevState.STANDBY)
        self.set_status("Ready")
        self.push_change_event("healthState", self._health_state)
        self.push_change_event("healthStatus", self._health_status)
        self.push_change_event("State", DevState.STANDBY)
        logger.info("%s: FAULT → STANDBY（已复位）", self.get_name())

    @command(dtype_in=str, doc_in="故障原因")
    async def InjectFault(self, reason: str):
        """注入测试故障（仅供测试用途）。"""
        self._health_state = 2
        self._health_status = f"Fault injected: {reason}"
        self.set_state(DevState.FAULT)
        self.set_status(f"Fault: {reason}")
        self.push_change_event("healthState", self._health_state)
        self.push_change_event("healthStatus", self._health_status)
        self.push_change_event("State", DevState.FAULT)
        logger.warning("%s: 故障注入 — %s", self.get_name(), reason)

    @command
    async def SelfCheck(self):
        """执行自检流程（约 1 秒/仿真速度）。"""
        self._self_check_state = 1
        self._self_check_status = "Self-checking..."
        await self._delay(1.0)
        self._self_check_state = 2
        self._self_check_status = "Self-check passed"
        logger.info("%s: 自检通过", self.get_name())

    # ── 内部工具 ─────────────────────────────────────────────

    async def _delay(self, seconds: float):
        """按仿真速度倍率缩放后的 sleep。"""
        await asyncio.sleep(seconds / self._sim_speed)

    async def _emit_safety(self, signal_id: str, message: str):
        """
        上报安全信号。子类须已声明 safetySignal 属性并注册变更事件。
        signal_id: 'S1'（紧急停机）/ 'S2'（紧急停充）/ 'S3'（真空告警）
        """
        logger.critical("%s: 安全信号 %s — %s", self.get_name(), signal_id, message)
        self._health_state = 2
        self._health_status = f"{signal_id}: {message}"
        self.push_change_event("healthState", self._health_state)
        self.push_change_event("healthStatus", self._health_status)
        # 子类在调用前须已调用 set_change_event("safetySignal", True, False)
        try:
            self.push_change_event("safetySignal", signal_id)
        except Exception:
            pass  # 子类未声明 safetySignal 时忽略
