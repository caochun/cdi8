"""
PLZ — 频率转换 Tango Device。

职责：用 KDP 晶体将 1053 nm 基频光转换为 351 nm 三倍频（紫外）光。
晶体姿态需 μrad 级精密调整。
  B 阶段：晶体调姿（SetCrystalPose），接收基频光（ReceiveFundamental）
"""

import logging
import random

from tango import AttrWriteType, DevState
from tango.server import attribute, command

from ._base import SubsystemDevice

logger = logging.getLogger(__name__)


class PLZDevice(SubsystemDevice):
    """频率转换（三倍频）。"""

    pitchAngle = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="mrad",
        doc="晶体俯仰角",
    )
    yawAngle = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="mrad",
        doc="晶体偏航角",
    )
    uvEnergy = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="J",
        doc="最近一次三倍频（351 nm）输出能量",
    )
    conversionEfficiency = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="%",
        doc="基频→三倍频转换效率",
    )

    async def init_device(self):
        await super().init_device()
        self._pitch: float = 0.0
        self._yaw: float = 0.0
        self._uv_energy: float = 0.0
        self._efficiency: float = 0.0
        self.set_change_event("uvEnergy", True, False)

    async def read_pitchAngle(self) -> float:
        return self._pitch

    async def read_yawAngle(self) -> float:
        return self._yaw

    async def read_uvEnergy(self) -> float:
        return self._uv_energy

    async def read_conversionEfficiency(self) -> float:
        return self._efficiency

    @command(dtype_in=[float], doc_in="[pitch_mrad, yaw_mrad]")
    async def SetCrystalPose(self, argin):
        """B05: 晶体位姿设置（俯仰角 + 偏航角，mrad）。"""
        if self.get_state() == DevState.FAULT:
            raise Exception("PLZ 处于故障状态，请先 Reset")
        if len(argin) < 2:
            raise Exception("SetCrystalPose 需要 [pitch, yaw] 两个参数")
        pitch, yaw = float(argin[0]), float(argin[1])
        self.set_state(DevState.MOVING)
        self.set_status(f"Crystal adjusting: pitch={pitch:.3f} yaw={yaw:.3f} mrad")
        await self._delay(0.3)
        self._pitch = pitch
        self._yaw = yaw
        self.set_state(DevState.ON)
        self.set_status(f"Crystal positioned, pitch={self._pitch:.3f} yaw={self._yaw:.3f} mrad")
        logger.info("PLZ: 晶体调姿完成，pitch=%.3f yaw=%.3f mrad", pitch, yaw)

    @command(dtype_in=float, dtype_out=float,
             doc_in="基频光能量 (J)", doc_out="三倍频输出能量 (J)")
    async def ReceiveFundamental(self, fundamental_energy: float) -> float:
        """B: 接收基频光，完成频率转换，返回三倍频（UV）能量。"""
        self.set_state(DevState.RUNNING)
        self.set_status("Frequency conversion (1053->351 nm)")
        await self._delay(0.05)

        # 转换效率与晶体角度相关，最佳角度附近最高
        angle_penalty = 1.0 - abs(self._pitch - 0.05) * 0.1
        self._efficiency = random.uniform(0.70, 0.80) * max(0.5, angle_penalty) * 100.0
        self._uv_energy = fundamental_energy * self._efficiency / 100.0
        self.set_state(DevState.ON)
        self.set_status(f"Conversion done, UV {self._uv_energy:.1f} J (eff {self._efficiency:.1f}%)")
        self.push_change_event("uvEnergy", self._uv_energy)
        logger.info(
            "PLZ: 频转完成，基频 %.1f J → UV %.1f J（%.1f%%）",
            fundamental_energy, self._uv_energy, self._efficiency,
        )
        return self._uv_energy
