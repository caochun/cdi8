"""
MX — 模型校准 Tango Device。

职责：基于每次发次的测量数据更新虚拟光路预测模型，
      为下一发次的配方参数反演提供校准因子。
  C 阶段：启动校准（StartCalibration）
"""

import json
import logging
import random

from tango import AttrWriteType, DevState
from tango.server import attribute, command

from ._base import SubsystemDevice

logger = logging.getLogger(__name__)


class MXDevice(SubsystemDevice):
    """模型校准系统（参数反演）。"""

    modelVersion = attribute(
        dtype=int,
        access=AttrWriteType.READ,
        doc="当前预测模型版本号（每发次更新自增）",
    )
    correctionFactors = attribute(
        dtype=str,
        access=AttrWriteType.READ,
        doc="最新校准因子（JSON 编码，供下发次配方使用）",
    )
    calibrationQuality = attribute(
        dtype=float,
        access=AttrWriteType.READ,
        unit="%",
        doc="本次校准质量评分（数据充分性 + 收敛性）",
    )

    async def init_device(self):
        await super().init_device()
        self._model_version: int = 0
        self._correction_factors: dict = {}
        self._calibration_quality: float = 0.0

    async def read_modelVersion(self) -> int:
        return self._model_version

    async def read_correctionFactors(self) -> str:
        return json.dumps(self._correction_factors, ensure_ascii=False)

    async def read_calibrationQuality(self) -> float:
        return self._calibration_quality

    @command(dtype_in=str, doc_in="发次测量数据（JSON 编码）")
    async def StartCalibration(self, shot_data_json: str):
        """C: 基于本次发次数据更新预测模型（模型校准）。"""
        if self.get_state() == DevState.FAULT:
            raise Exception("MX 处于故障状态，请先 Reset")
        try:
            shot_data = json.loads(shot_data_json)
        except json.JSONDecodeError:
            shot_data = {}

        self.set_state(DevState.RUNNING)
        self.set_status("Model calibrating")
        await self._delay(1.0)  # 校准计算耗时

        # 模拟校准因子更新
        self._model_version += 1
        energy_kj = shot_data.get("energy_kj", 1.0)
        neutron = shot_data.get("neutron_count", 1000)

        self._correction_factors = {
            "energy_gain_correction": round(random.uniform(0.98, 1.02), 4),
            "timing_offset_ns": round(random.uniform(-0.5, 0.5), 3),
            "wavefront_correction_coefficient": round(random.uniform(0.95, 1.05), 4),
            "predicted_neutron_next": int(neutron * random.uniform(0.9, 1.1)),
            "model_version": self._model_version,
        }
        self._calibration_quality = min(100.0, 60.0 + energy_kj * 10.0 + random.uniform(-5, 5))

        self.set_state(DevState.ON)
        self.set_status(
            f"Calibration done, model v{self._model_version}, "
            f"quality {self._calibration_quality:.1f}%"
        )
        logger.info(
            "MX: 模型校准完成，v%d，质量 %.1f%%",
            self._model_version, self._calibration_quality,
        )
