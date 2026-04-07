"""
ZK — 真空靶室模拟器

职责：维持靶室真空，真空度反馈（S3）。

状态序列：
  STANDBY（大气压）→ EVACUATING → EVACUATED → STANDBY

安全信号：
  S3 真空度反馈 — 真空度低于阈值时上报（不中止，但集中管控需响应）
"""
import asyncio
import logging
import random

from .base import BaseSimulator, SimState

logger = logging.getLogger(__name__)

VACUUM_THRESHOLD = 1e-3   # Pa，允许打靶的最高气压
ATMOSPHERIC = 1.013e5     # Pa，大气压


class ZKSimulator(BaseSimulator):
    """真空靶室模拟器。"""

    def __init__(self, sim_speed: float = 1.0):
        super().__init__("ZK", sim_speed)
        self._pressure: float = ATMOSPHERIC   # Pa
        self._pump_active: bool = False
        self._vacuum_ok: bool = False
        # 启动后台真空监控
        self._monitor_task: asyncio.Task | None = None

    async def start_monitoring(self):
        """启动持续真空监控（应在事件循环启动后调用）。"""
        if self._monitor_task is None or self._monitor_task.done():
            self._monitor_task = asyncio.create_task(self._monitor_vacuum())

    async def stop_monitoring(self):
        if self._monitor_task and not self._monitor_task.done():
            self._monitor_task.cancel()
            try:
                await self._monitor_task
            except asyncio.CancelledError:
                pass

    # ── 属性 ────────────────────────────────────────────────

    @property
    def pressure(self) -> float:
        return self._pressure

    @property
    def vacuum_ok(self) -> bool:
        return self._vacuum_ok

    @property
    def pump_active(self) -> bool:
        return self._pump_active

    # ── 命令 ────────────────────────────────────────────────

    async def start_evacuation(self):
        """
        A09: 开始抽真空。
        模拟指数衰减抽气过程（约 10 秒内从大气压降至目标真空度）。
        """
        if self._state == SimState.FAULT:
            raise RuntimeError("ZK 处于故障状态，请先 reset")
        if self._pump_active:
            logger.info("ZK: 抽气泵已在运行")
            return

        self._pump_active = True
        await self._transition(SimState.RUNNING, "抽真空中")

        # 模拟抽气：指数衰减，40 步确保在最坏随机情况下也能收敛
        steps = 40
        for i in range(steps):
            await self._delay(0.25)
            self._pressure *= random.uniform(0.2, 0.5)
            if self._pressure < 1e-7:
                self._pressure = 1e-7
            logger.debug("ZK: 气压 %.2e Pa", self._pressure)
            if self._pressure <= VACUUM_THRESHOLD:
                break

        if self._pressure <= VACUUM_THRESHOLD:
            self._vacuum_ok = True
            await self._transition(SimState.READY, f"真空就绪，气压: {self._pressure:.2e} Pa")
        else:
            self._vacuum_ok = False
            await self._transition(SimState.FAULT, f"抽真空失败，气压: {self._pressure:.2e} Pa")

    async def vent_chamber(self):
        """充气（发射后维护用）。"""
        self._pump_active = False
        self._vacuum_ok = False
        await self._transition(SimState.RUNNING, "充气中")
        await self._delay(3.0)
        self._pressure = ATMOSPHERIC
        await self._transition(SimState.STANDBY, "靶室已充气至大气压")

    async def reset(self):
        self._vacuum_ok = False
        await super().reset()

    # ── 后台监控 ─────────────────────────────────────────────

    async def _monitor_vacuum(self):
        """持续监控真空度，低于阈值时上报 S3。"""
        while True:
            await asyncio.sleep(5.0 / self.sim_speed)
            if self._state == SimState.READY and self._vacuum_ok:
                # 模拟随机漏气（0.5% 概率）
                if random.random() < 0.005:
                    self._pressure *= random.uniform(5, 20)
                    if self._pressure > VACUUM_THRESHOLD:
                        self._vacuum_ok = False
                        logger.warning("ZK: 真空度下降至 %.2e Pa，上报 S3", self._pressure)
                        await self._emit_safety(
                            "S3", f"真空度异常: {self._pressure:.2e} Pa > {VACUUM_THRESHOLD:.2e} Pa"
                        )
