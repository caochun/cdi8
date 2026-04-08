"""
ZK — 真空靶室 Tango Device。

职责：维持靶室真空（~10⁻⁵ Pa），监控漏气。
  A 阶段：启动后台压强监控（StartMonitoring），抽真空（StartEvacuation）
  全程：后台漏气检测，触发 S3 告警
  中止：停止监控（StopMonitoring）

VACUUM_THRESHOLD: 可接受的最高压强（Pa），高于此值为 S3 告警。
抽真空模型：40 步随机指数衰减（确保收敛到阈值以下）。
"""

import asyncio
import logging
import random

from tango import AttrWriteType, DevState
from tango.server import attribute, command

from ._base import SubsystemDevice

logger = logging.getLogger(__name__)

# 真空判据阈值（控制器通过 `from simulators.zk import VACUUM_THRESHOLD` 获取）
VACUUM_THRESHOLD: float = 1e-3  # Pa


class ZKDevice(SubsystemDevice):
    """真空靶室。"""

    pressure = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="Pa",
        doc="当前靶室压强",
    )
    evacuated = attribute(
        dtype=bool,
        access=AttrWriteType.READ,
        doc="True = 已达到真空判据（pressure < VACUUM_THRESHOLD）",
    )
    safetySignal = attribute(
        dtype=str,
        access=AttrWriteType.READ,
        doc="安全信号：'' = 无，'S3' = 真空泄漏告警",
    )

    async def init_device(self):
        await super().init_device()
        self._pressure: float = 1.013e5  # Pa，大气压
        self._evacuated: bool = False
        self._safety_signal: str = ""
        self._monitoring_task: asyncio.Task | None = None
        self.set_change_event("pressure", True, False)
        self.set_change_event("safetySignal", True, False)

    async def read_pressure(self) -> float:
        return self._pressure

    async def read_evacuated(self) -> bool:
        return self._evacuated

    async def read_safetySignal(self) -> str:
        return self._safety_signal

    @command
    async def StartMonitoring(self):
        """A: 启动真空后台监控（每 5s 检查一次，0.5% 漏气概率）。"""
        if self._monitoring_task is not None and not self._monitoring_task.done():
            return  # 已在监控中
        self._monitoring_task = asyncio.create_task(self._monitor_loop())
        self.set_status("真空监控已启动")
        logger.info("ZK: 后台真空监控启动")

    @command
    async def StopMonitoring(self):
        """A/补偿: 停止真空后台监控。"""
        if self._monitoring_task and not self._monitoring_task.done():
            self._monitoring_task.cancel()
            try:
                await self._monitoring_task
            except asyncio.CancelledError:
                pass
        self._monitoring_task = None
        logger.info("ZK: 真空监控已停止")

    @command
    async def StartEvacuation(self):
        """A: 抽真空（40 步随机指数衰减，确保收敛到 VACUUM_THRESHOLD 以下）。"""
        if self.get_state() == DevState.FAULT:
            raise Exception("ZK 处于故障状态，请先 Reset")
        self.set_state(DevState.RUNNING)
        self.set_status("抽真空中")
        self._evacuated = False
        self._pressure = 1.013e5  # 从大气压开始

        for step in range(40):
            await self._delay(0.25)
            decay = random.uniform(0.2, 0.5)
            self._pressure *= decay
            self.push_change_event("pressure", self._pressure)
            if self._pressure < VACUUM_THRESHOLD:
                break

        if self._pressure >= VACUUM_THRESHOLD:
            self.set_state(DevState.FAULT)
            self.set_status(f"抽真空失败，压强 {self._pressure:.2e} Pa")
            raise Exception(f"ZK 抽真空失败，{self._pressure:.2e} Pa > {VACUUM_THRESHOLD:.2e} Pa")

        self._evacuated = True
        self.set_state(DevState.ON)
        self.set_status(f"真空就绪，{self._pressure:.2e} Pa")
        logger.info("ZK: 抽真空完成，压强 %.2e Pa", self._pressure)

    async def _monitor_loop(self):
        """后台任务：定期检查压强，0.5% 概率触发 S3 漏气告警。"""
        while True:
            await asyncio.sleep(5.0 / self._sim_speed)
            if self._evacuated and random.random() < 0.005:
                leak_amount = random.uniform(1e-4, 1e-1)
                self._pressure += leak_amount
                self._safety_signal = "S3"
                self.push_change_event("pressure", self._pressure)
                await self._emit_safety(
                    "S3",
                    f"靶室漏气检测到，压强升至 {self._pressure:.2e} Pa"
                )
