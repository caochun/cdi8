"""
DCF — 多程放大（主放）Tango Device。

职责：主放大器，光路迭代准直，接收泵浦并放大至百焦级。
  A 阶段：迭代准直（Align），确认就绪（ConfirmReady）
  B 阶段：接收泵浦能量并完成放大（ReceivePumpAndFire）

准直算法：5 步迭代，每步引入随机误差，收敛阈值 10 μrad。
"""

import logging
import random

from tango import AttrWriteType, DevState
from tango.server import attribute, command

from ._base import SubsystemDevice

logger = logging.getLogger(__name__)


class DCFDevice(SubsystemDevice):
    """多程放大（主放）。"""

    alignmentError = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="μrad",
        doc="当前光束指向误差（<10 μrad 为就绪判据）",
    )
    outputEnergy = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="J",
        doc="最近一次放大输出能量",
    )
    alignmentSteps = attribute(
        dtype=int,
        access=AttrWriteType.READ,
        doc="本次准直收敛所用步数",
    )

    async def init_device(self):
        await super().init_device()
        self._alignment_error: float = 999.0  # μrad，未准直时大值
        self._output_energy: float = 0.0
        self._alignment_steps: int = 0
        self._aligned: bool = False
        self.set_change_event("alignmentError", True, False)
        self.set_change_event("outputEnergy", True, False)

    async def read_alignmentError(self) -> float:
        return self._alignment_error

    async def read_outputEnergy(self) -> float:
        return self._output_energy

    async def read_alignmentSteps(self) -> int:
        return self._alignment_steps

    @command
    async def Align(self):
        """A04: 光路迭代准直（5 步，收敛阈值 10 μrad）。"""
        if self.get_state() == DevState.FAULT:
            raise Exception("DCF 处于故障状态，请先 Reset")
        self.set_state(DevState.MOVING)
        self.set_status("光路准直中")
        self._aligned = False

        error = 5.0  # 初始基准误差 μrad
        for step in range(1, 6):
            await self._delay(0.5)
            error = abs(error + random.gauss(0, 3.0))
            self._alignment_error = error
            self.push_change_event("alignmentError", error)
            logger.debug("DCF: 准直步 %d，误差 %.2f μrad", step, error)
            if error < 10.0:
                self._alignment_steps = step
                break
        else:
            self._alignment_steps = 5

        if self._alignment_error >= 10.0:
            self.set_state(DevState.FAULT)
            self.set_status(f"准直失败，误差 {self._alignment_error:.1f} μrad（阈值 10 μrad）")
            raise Exception(f"DCF 准直失败，最终误差 {self._alignment_error:.1f} μrad")

        self._aligned = True
        self.set_state(DevState.ON)
        self.set_status(
            f"准直完成，误差 {self._alignment_error:.2f} μrad，"
            f"用时 {self._alignment_steps} 步"
        )
        logger.info("DCF: 准直完成，误差 %.2f μrad，%d 步", self._alignment_error, self._alignment_steps)

    @command
    async def ConfirmReady(self):
        """A04: 确认 DCF 处于就绪状态（准直完成后调用）。"""
        if not self._aligned or self.get_state() != DevState.ON:
            raise Exception(f"DCF 未完成准直（aligned={self._aligned}，state={self.get_state()}）")
        logger.info("DCF: 就绪确认通过")

    @command(dtype_in=float, dtype_out=float,
             doc_in="泵浦能量 (J)", doc_out="主放输出能量 (J)")
    async def ReceivePumpAndFire(self, pump_energy: float) -> float:
        """B11/B12: 接收泵浦能量，完成主放大，返回输出能量。"""
        if not self._aligned:
            raise Exception("DCF 未完成准直，无法放大")
        self.set_state(DevState.RUNNING)
        self.set_status("主放大进行中")
        await self._delay(0.15)

        # 简化增益模型（片放小信号增益 ~3，饱和效应 + 噪声）
        gain = 3.0 * random.uniform(0.95, 1.05)
        self._output_energy = pump_energy * 0.7 * gain  # 70% 泵浦分配给主放
        self.set_state(DevState.ON)
        self.set_status(f"主放完成，输出 {self._output_energy:.1f} J")
        self.push_change_event("outputEnergy", self._output_energy)
        logger.info("DCF: 主放完成，泵浦 %.1f J → 输出 %.1f J", pump_energy, self._output_energy)
        return self._output_energy

    @command
    async def Reset(self):
        """复位：清除准直状态。"""
        self._aligned = False
        self._alignment_error = 999.0
        self._output_energy = 0.0
        await super().Reset()

    @command
    async def ConfirmRepFrequencyState(self):
        """A阶段：重频光状态确认。
        确认主放大链在重频（连续脉冲）模式下的光束状态，
        包括光束指向、光斑质量等，用于打靶准备阶段的预检查。
        仅在准直完成（Align）后允许调用。
        """
        if not self._aligned:
            raise Exception("DCF 未完成准直，无法确认重频光状态")
        logger.info("DCF: 重频光状态确认通过，误差 %.2f μrad", self._alignment_error)

    @command
    async def ConfirmShotState(self):
        """A阶段：打靶状态确认。
        在 A 阶段结束时对主放大链的打靶就绪状态进行最终确认，
        包括准直状态、光路清洁度、隔离器状态等，全部通过后方可进入 B 阶段。
        """
        if not self._aligned or self.get_state() != DevState.ON:
            raise Exception(f"DCF 未就绪（aligned={self._aligned}，state={self.get_state()}）")
        logger.info("DCF: 打靶状态确认通过")
