"""
BaseSimulator — 所有子系统模拟器的基类。

设计原则：
- 纯 asyncio，不依赖 Tango 安装
- 接口方法名与 Tango device command 一一对应，后续换装只需加装饰器
- 支持故障注入（inject_fault / reset）
- 支持事件回调（on / _emit），供集中管控订阅状态变化和安全信号
"""
import asyncio
import enum
import logging
from typing import Any, Callable

logger = logging.getLogger(__name__)


class SimState(enum.Enum):
    OFF = "OFF"
    STANDBY = "STANDBY"
    RUNNING = "RUNNING"
    MOVING = "MOVING"
    READY = "READY"
    FAULT = "FAULT"
    # 子类可以定义自己的扩展状态（字符串枚举兼容）


class BaseSimulator:
    """
    所有子系统模拟器的基类。

    子类需要：
    1. 调用 super().__init__(name)
    2. 实现业务命令（async def）
    3. 在命令里调用 _transition() 驱动状态变化
    4. 安全信号通过 _emit('safety', signal_id, message) 上报
    """

    def __init__(self, name: str, sim_speed: float = 1.0):
        """
        Args:
            name: 子系统标识，如 'JZT', 'AQ'
            sim_speed: 仿真速度倍率。2.0 = 两倍速，0.5 = 半速。
        """
        self.name = name
        self.sim_speed = sim_speed
        self._state: enum.Enum = SimState.STANDBY
        self._status: str = "就绪"
        self._fault_reason: str = ""
        self._callbacks: dict[str, list[Callable]] = {}
        self._lock = asyncio.Lock()  # 防止并发命令竞态

    # ── 属性 ────────────────────────────────────────────────

    @property
    def state(self) -> enum.Enum:
        return self._state

    @property
    def status(self) -> str:
        return self._status

    @property
    def is_fault(self) -> bool:
        return self._state.value == SimState.FAULT.value

    @property
    def is_ready(self) -> bool:
        return self._state.value == SimState.READY.value

    # ── 通用命令 ─────────────────────────────────────────────

    async def reset(self):
        """从 FAULT 状态恢复到 STANDBY。"""
        async with self._lock:
            if self._state.value == SimState.FAULT.value:
                self._fault_reason = ""
                await self._transition(SimState.STANDBY, "已复位")
            else:
                logger.debug("%s: reset called but not in FAULT (state=%s)", self.name, self._state)

    # ── 故障注入 ─────────────────────────────────────────────

    async def inject_fault(self, reason: str = "模拟故障"):
        """从外部注入故障，用于测试安全联锁路径。"""
        self._fault_reason = reason
        await self._transition(SimState.FAULT, f"故障: {reason}")
        logger.warning("%s: 故障注入 — %s", self.name, reason)

    # ── 事件订阅 ─────────────────────────────────────────────

    def on(self, event: str, callback: Callable[..., Any]):
        """
        订阅事件。

        常用事件：
        - 'state_changed': (subsystem_name, old_state, new_state, message)
        - 'safety': (subsystem_name, signal_id, message)  如 S1/S2/S3
        """
        self._callbacks.setdefault(event, []).append(callback)

    def off(self, event: str, callback: Callable):
        """取消订阅。"""
        cbs = self._callbacks.get(event, [])
        if callback in cbs:
            cbs.remove(callback)

    # ── 内部工具 ─────────────────────────────────────────────

    async def _transition(self, new_state: enum.Enum, message: str):
        """切换状态并触发 state_changed 事件。"""
        old_state = self._state
        self._state = new_state
        self._status = message
        logger.info("%s: %s → %s | %s", self.name, old_state.value, new_state.value, message)
        await self._emit("state_changed", self.name, old_state, new_state, message)

    async def _delay(self, seconds: float):
        """经仿真速度倍率调整后的 sleep。"""
        await asyncio.sleep(seconds / self.sim_speed)

    async def _emit(self, event: str, *args):
        """触发事件，支持同步和异步回调。"""
        for cb in self._callbacks.get(event, []):
            try:
                result = cb(*args)
                if asyncio.iscoroutine(result):
                    await result
            except Exception:
                logger.exception("%s: 事件回调异常 (event=%s)", self.name, event)

    async def _emit_safety(self, signal_id: str, message: str):
        """上报安全信号（S1/S2/S3）。"""
        logger.critical("%s: 安全信号 %s — %s", self.name, signal_id, message)
        await self._emit("safety", self.name, signal_id, message)

    def __repr__(self) -> str:
        return f"<{self.__class__.__name__} name={self.name!r} state={self._state.value}>"
