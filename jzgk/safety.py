"""
SafetyWatcher — 独立协程，监听所有子系统的 S 信号。

S1（AQ 紧急停机）、S2（BB 紧急停充）立即触发 emergency_event，
中止当前正在运行的发次阶段。

S3（ZK 真空度反馈）记录告警，不自动中止（由 JZGK 决策）。
"""
import asyncio
import logging
from dataclasses import dataclass, field
from datetime import datetime

from simulators.registry import SimulatorRegistry

logger = logging.getLogger(__name__)

# S1/S2 立即触发中止；S3 仅记录
ABORT_SIGNALS = {"S1", "S2"}


@dataclass
class SafetyEvent:
    signal_id: str
    source: str
    message: str
    timestamp: datetime = field(default_factory=datetime.now)

    def __str__(self):
        return f"[{self.signal_id}] {self.source}: {self.message}"


class SafetyWatcher:
    """
    订阅所有子系统的 safety 事件。
    - S1/S2: 设置 emergency_event（发次编排检测到后抛出 ShotAborted）
    - S3:    记录到 warnings 列表，不中止
    """

    def __init__(self, registry: SimulatorRegistry):
        self.emergency_event = asyncio.Event()
        self.warnings: list[SafetyEvent] = []
        self._last_emergency: SafetyEvent | None = None
        registry.on_safety(self._handle)

    def _handle(self, source: str, signal_id: str, message: str):
        evt = SafetyEvent(signal_id=signal_id, source=source, message=message)
        if signal_id in ABORT_SIGNALS:
            logger.critical("SafetyWatcher: 紧急中止信号 %s", evt)
            self._last_emergency = evt
            self.emergency_event.set()
        else:
            logger.warning("SafetyWatcher: 告警信号 %s", evt)
            self.warnings.append(evt)

    @property
    def last_emergency(self) -> SafetyEvent | None:
        return self._last_emergency

    def clear(self):
        """发次结束后复位（准备下一发次）。"""
        self.emergency_event.clear()
        self._last_emergency = None
        self.warnings.clear()

    async def check(self):
        """
        在发次关键节点调用：若 emergency_event 已设置则抛出 ShotAborted。
        用于在 await 之间的同步检查点。
        """
        from jzgk.recipe import AbortReason, ShotAborted
        if self.emergency_event.is_set() and self._last_emergency:
            raise ShotAborted(
                AbortReason.SAFETY_SIGNAL,
                str(self._last_emergency),
            )

    async def guard(self, coro):
        """
        包裹一个协程，若 emergency_event 在其执行期间触发，
        立即取消协程并抛出 ShotAborted。
        用法：await safety.guard(some_long_operation())
        """
        from jzgk.recipe import AbortReason, ShotAborted

        task = asyncio.create_task(coro)
        emergency_task = asyncio.create_task(self.emergency_event.wait())

        done, pending = await asyncio.wait(
            {task, emergency_task},
            return_when=asyncio.FIRST_COMPLETED,
        )

        for p in pending:
            p.cancel()
            try:
                await p
            except asyncio.CancelledError:
                pass

        if emergency_task in done and self._last_emergency:
            if not task.done():
                raise ShotAborted(AbortReason.SAFETY_SIGNAL, str(self._last_emergency))
            # task 已完成：取消 or 异常 都视为紧急中止
            if task.cancelled():
                raise ShotAborted(AbortReason.SAFETY_SIGNAL, str(self._last_emergency))
            if task.exception():
                raise task.exception()
            # task 正常完成但 emergency 也触发了——以安全信号优先
            raise ShotAborted(AbortReason.SAFETY_SIGNAL, str(self._last_emergency))

        if task in done:
            if task.cancelled():
                raise asyncio.CancelledError()
            if task.exception():
                raise task.exception()
            return task.result()
