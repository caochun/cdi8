"""
测量取样组件 仿真器 Device。
Tango Device Path: gxlf/measurement_sample/01
"""
from tango import DevShort, DevString
from tango.server import command
from .base import SimBase


class MeasurementSample(SimBase):
    """仿真 测量取样组件 的 Tango Device。"""

    @command(dtype_in=DevString, dtype_out=DevShort)
    def MeasTargetAlignReady(self, argin: str) -> int:
        
        return self._exec(argin, "MeasTargetAlignReady")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def MeasShotMotionReady(self, argin: str) -> int:
        
        return self._exec(argin, "MeasShotMotionReady")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def ShotMeasurementReady(self, argin: str) -> int:
        
        return self._exec(argin, "ShotMeasurementReady")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def LaserParamCollect(self, argin: str) -> int:
        
        return self._exec(argin, "LaserParamCollect")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def MeasStandby(self, argin: str) -> int:
        
        return self._exec(argin, "MeasStandby")

