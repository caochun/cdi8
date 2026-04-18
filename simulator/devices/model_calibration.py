"""
运行参数获取与性能模型校准 仿真器 Device。
Tango Device Path: gxlf/model_calibration/01
"""
from tango import DevShort, DevString
from tango.server import command
from .base import SimBase


class ModelCalibration(SimBase):
    """仿真 运行参数获取与性能模型校准 的 Tango Device。"""

    @command(dtype_in=DevString, dtype_out=DevShort)
    def ModelParamCalc(self, argin: str) -> int:
        
        return self._exec(argin, "ModelParamCalc")

