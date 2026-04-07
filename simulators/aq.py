"""
AQ — 安全联锁模拟器

职责：屏蔽门管理、警灯、人员计数、紧急停机上报（S1）。

状态序列：
  OPEN（人员可进入）
  → CLEARING（清场中，等待人员撤离）
  → LOCKED（门已锁，屏蔽完成）
  → ARMED（联锁激活，允许出光）
  → OPEN（发射后解除）

安全信号：
  S1 紧急停机 — 任何阶段均可触发，通过 'safety' 事件上报
"""
import asyncio
import enum
import logging
import random

from .base import BaseSimulator, SimState

logger = logging.getLogger(__name__)


class AQState(enum.Enum):
    """AQ 子系统专用状态（独立枚举，不扩展 SimState）。"""
    OPEN = "OPEN"
    CLEARING = "CLEARING"
    LOCKED = "LOCKED"
    ARMED = "ARMED"
    FAULT = "FAULT"   # 与 SimState.FAULT.value 相同，用于故障态


class AQSimulator(BaseSimulator):
    """安全联锁子系统模拟器。"""

    def __init__(self, sim_speed: float = 1.0):
        super().__init__("AQ", sim_speed)
        self._state = AQState.OPEN
        self._status = "区域开放，人员可进入"
        self._door_status: str = "OPEN"    # OPEN / CLOSING / LOCKED
        self._area_clear: bool = False
        self._beam_shutter_closed: bool = True
        self._emergency_stop_active: bool = False
        # 模拟：区域内有随机数量的人员
        self._person_count: int = random.randint(0, 3)

    # ── 属性 ────────────────────────────────────────────────

    @property
    def door_status(self) -> str:
        return self._door_status

    @property
    def person_count(self) -> int:
        return self._person_count

    @property
    def area_clear(self) -> bool:
        return self._area_clear

    @property
    def beam_shutter_closed(self) -> bool:
        return self._beam_shutter_closed

    @property
    def emergency_stop_active(self) -> bool:
        return self._emergency_stop_active

    # ── 命令 ────────────────────────────────────────────────

    async def clear_area(self):
        """
        A13: 清场指令。
        打开警报，等待人员撤离，关闭屏蔽门。
        """
        if self._emergency_stop_active:
            raise RuntimeError("紧急停机激活，无法执行清场")

        await self._transition(AQState.CLEARING, "清场中：警报已启动，等待人员撤离")
        self._door_status = "CLOSING"

        # 模拟人员撤离（每人约 2 秒）
        while self._person_count > 0:
            await self._delay(2.0)
            self._person_count -= 1
            logger.info("AQ: 区域内剩余人员 %d", self._person_count)

        self._area_clear = True
        await self._delay(1.0)  # 门关闭时间

        self._door_status = "LOCKED"
        self._beam_shutter_closed = False  # 屏蔽门锁定后光束通路打开
        await self._transition(AQState.LOCKED, "区域已清场，屏蔽门已锁定")

    async def lock_down(self):
        """在 LOCKED 状态后激活联锁，允许出光。对应 A14。"""
        if self._state is not AQState.LOCKED:
            raise RuntimeError(f"AQ 需在 LOCKED 状态才能激活联锁，当前: {self._state}")
        await self._delay(0.5)
        await self._transition(AQState.ARMED, "联锁已激活，允许出光")

    async def release(self):
        """发射后解除联锁，恢复区域开放。"""
        self._beam_shutter_closed = True
        self._area_clear = False
        self._person_count = 0
        await self._transition(AQState.OPEN, "联锁解除，区域已开放")
        self._door_status = "OPEN"

    async def emergency_stop(self, reason: str = "手动紧急停机"):
        """触发 S1 紧急停机信号。可由内部检测（人员闯入）或外部按钮触发。"""
        self._emergency_stop_active = True
        self._beam_shutter_closed = True
        await self._transition(AQState.FAULT, f"紧急停机: {reason}")
        await self._emit_safety("S1", reason)

    async def reset(self):
        """复位：解除紧急停机，恢复 OPEN 状态。"""
        self._emergency_stop_active = False
        self._door_status = "OPEN"
        self._area_clear = False
        self._beam_shutter_closed = True
        self._person_count = 0
        self._fault_reason = ""
        await self._transition(AQState.OPEN, "已复位，区域开放")

    async def simulate_intrusion(self):
        """故障注入：模拟人员闯入（用于测试 S1 路径）。"""
        self._person_count += 1
        logger.warning("AQ: 检测到人员进入！count=%d", self._person_count)
        if self._state in (AQState.LOCKED, AQState.ARMED):
            await self.emergency_stop("检测到人员进入受保护区域")
