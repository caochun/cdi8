"""
AQ — 安全联锁 Tango Device。

职责：靶场区域安全管控（屏蔽门、警灯、人员计数 RFID、紧急停机）。
  A 阶段：清场（ClearArea）、联锁激活（LockDown）
  C 阶段：释放联锁（Release）
  紧急：SimulateIntrusion 发出 S1 紧急停机信号

⚠️  S1 触发后，AQ 进入 FAULT 状态。软件不自动解除联锁。
    必须由操作员人工确认清场后手动调用 Reset 复位。
"""

import asyncio
import logging
import random

from tango import AttrWriteType, DevState
from tango.server import attribute, command

from ._base import SubsystemDevice

logger = logging.getLogger(__name__)


class AQDevice(SubsystemDevice):
    """安全联锁系统。"""

    areaCleared = attribute(
        dtype=bool,
        access=AttrWriteType.READ,
        doc="True = 人员清场已确认（RFID 计数为零）",
    )
    lockedDown = attribute(
        dtype=bool,
        access=AttrWriteType.READ,
        doc="True = 屏蔽门关闭锁定、联锁已激活",
    )
    personCount = attribute(
        dtype=int,
        access=AttrWriteType.READ,
        doc="靶场内当前 RFID 检测人员数",
    )
    safetySignal = attribute(
        dtype=str,
        access=AttrWriteType.READ,
        doc="安全信号：'' = 无，'S1' = 紧急停机",
    )

    async def init_device(self):
        await super().init_device()
        self._area_cleared: bool = False
        self._locked_down: bool = False
        self._person_count: int = 3  # 初始有人员
        self._safety_signal: str = ""
        self._intrusion_task: asyncio.Task | None = None
        self.set_change_event("safetySignal", True, False)
        self.set_change_event("personCount", True, False)

    async def read_areaCleared(self) -> bool:
        return self._area_cleared

    async def read_lockedDown(self) -> bool:
        return self._locked_down

    async def read_personCount(self) -> int:
        return self._person_count

    async def read_safetySignal(self) -> str:
        return self._safety_signal

    @command
    async def ClearArea(self):
        """A10: 清场（等待 RFID 确认靶场无人）。"""
        if self.get_state() == DevState.FAULT:
            raise Exception("AQ 处于故障（S1）状态，需操作员手动复位")
        self.set_state(DevState.RUNNING)
        self.set_status("清场中，等待人员撤离")

        # 模拟人员逐步撤离（3 步）
        for i in range(3, 0, -1):
            await self._delay(0.5)
            self._person_count = i - 1
            self.push_change_event("personCount", self._person_count)

        self._area_cleared = True
        self.set_state(DevState.ON)
        self.set_status("清场完成，RFID 确认无人")
        logger.info("AQ: 清场完成")

    @command
    async def LockDown(self):
        """A10: 屏蔽门关闭锁定，警灯亮起，进入安全联锁状态。"""
        if not self._area_cleared:
            raise Exception("AQ: 未完成清场，无法锁定")
        self._locked_down = True
        self.set_state(DevState.ON)
        self.set_status("⚠ 安全联锁激活，屏蔽门已锁")
        logger.info("AQ: 安全联锁激活")

    @command
    async def Release(self):
        """C11: 退出安全联锁状态（警灯熄灭、屏蔽门解锁）。"""
        if self.get_state() == DevState.FAULT:
            logger.warning("AQ: FAULT（S1）状态下，Release 被忽略，需操作员手动复位")
            return
        self._locked_down = False
        self._area_cleared = False
        self.set_state(DevState.STANDBY)
        self.set_status("联锁已释放，警灯熄灭")
        logger.info("AQ: 安全联锁释放")

    @command
    async def SimulateIntrusion(self):
        """测试：模拟人员入侵（触发 S1 紧急停机信号）。"""
        self._person_count = 1
        self.push_change_event("personCount", 1)
        await self._trigger_s1("检测到人员进入受保护区域")

    async def _trigger_s1(self, message: str):
        """内部：发出 S1 紧急停机信号并进入 FAULT。"""
        self._safety_signal = "S1"
        self._locked_down = False
        self.set_state(DevState.FAULT)
        self.set_status(f"⚠ S1 紧急停机: {message}")
        await self._emit_safety("S1", message)
        logger.critical("AQ: S1 紧急停机 — %s", message)

    @command
    async def Reset(self):
        """操作员确认清场后手动复位（S1 后必须由人工触发）。"""
        self._safety_signal = ""
        self._area_cleared = False
        self._locked_down = False
        self._person_count = 0
        await super().Reset()
        logger.info("AQ: 手动复位完成")

    @command(dtype_in=int, doc_in="警灯控制：0=关闭，1=常亮，2=闪烁")
    async def ControlWarningLight(self, mode: int):
        """B/C阶段：警灯控制。
        控制靶场区域警示灯的工作状态：
        - 0：关闭警灯（发次结束、区域安全时使用）；
        - 1：常亮（联锁激活、激光运行期间）；
        - 2：闪烁（系统预备状态、人员撤离提示）。
        B 阶段联锁激活后应置为常亮，C 阶段联锁释放后关闭。
        """
        modes = {0: "关闭", 1: "常亮", 2: "闪烁"}
        mode_str = modes.get(mode, f"未知({mode})")
        self.set_status(f"警灯状态: {mode_str}")
        logger.info("AQ: 警灯设置为 %s（mode=%d）", mode_str, mode)

    @command(dtype_in=int, doc_in="警示音控制：0=关闭，1=开启")
    async def ControlAlarmSound(self, mode: int):
        """B/C阶段：警示音乐控制。
        控制靶场区域警示音响的开关状态：
        - 0：关闭警示音（联锁释放后停止）；
        - 1：开启警示音（联锁激活前提示人员撤离）。
        通常在 ClearArea() 时开启，LockDown() 完成后可根据需要关闭。
        """
        mode_str = "开启" if mode else "关闭"
        self.set_status(f"警示音: {mode_str}")
        logger.info("AQ: 警示音设置为 %s（mode=%d）", mode_str, mode)

    @command(dtype_in=int, doc_in="屏蔽门控制：0=开门，1=关门，2=锁定")
    async def ControlShieldDoor(self, action: int):
        """A阶段：屏蔽门控制。
        控制靶场屏蔽门的开关与锁定状态：
        - 0：开门（允许人员进出，用于 C 阶段联锁释放后）；
        - 1：关门（人员撤离完成后关闭屏蔽门）；
        - 2：锁定（屏蔽门关闭并电磁锁定，与联锁激活联动）。
        A 阶段清场后应执行关门（1）再锁定（2）操作。
        """
        actions = {0: "开门", 1: "关门", 2: "锁定"}
        action_str = actions.get(action, f"未知({action})")
        self.set_state(DevState.RUNNING)
        self.set_status(f"屏蔽门{action_str}中")
        await self._delay(1.0)  # 模拟门动作时间
        if action == 2:
            self._locked_down = True
        elif action == 0:
            self._locked_down = False
        self.set_state(DevState.ON if action in (1, 2) else DevState.STANDBY)
        self.set_status(f"屏蔽门{action_str}完成")
        logger.info("AQ: 屏蔽门 %s（action=%d）", action_str, action)

    @command(dtype_in=int, doc_in="安全管控状态：0=释放，1=预备，2=联锁激活，3=紧急停机")
    async def SetSafetyState(self, state: int):
        """A/C阶段：安全管控状态控制。
        设置靶场安全管控系统的整体状态：
        - 0：释放（发次结束，联锁解除，允许人员入场）；
        - 1：预备（系统预备，人员开始撤离，警灯闪烁）；
        - 2：联锁激活（清场完成，屏蔽门锁定，激光允许发射）；
        - 3：紧急停机（触发 S1，进入 FAULT，等待人工复位）。
        A 阶段末尾置为 2，C 阶段结束后置为 0。
        """
        states = {0: "释放", 1: "预备", 2: "联锁激活", 3: "紧急停机"}
        state_str = states.get(state, f"未知({state})")
        if state == 3:
            await self._trigger_s1("SetSafetyState(3) 触发紧急停机")
            return
        if state == 0:
            self._locked_down = False
            self._area_cleared = False
        elif state == 2:
            self._locked_down = True
        self.set_status(f"安全管控状态: {state_str}")
        logger.info("AQ: 安全管控状态设置为 %s（state=%d）", state_str, state)
