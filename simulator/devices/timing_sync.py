"""
集中同步分系统 仿真器 Device。
Tango Device Path: gxlf/timing_sync/01
"""
from tango import DevShort, DevString
from tango.server import command
from .base import SimBase


class TimingSync(SimBase):
    """仿真 集中同步分系统 的 Tango Device。"""

    @command(dtype_in=DevString, dtype_out=DevShort)
    def ShotReadyRecipe(self, argin: str) -> int:
        
        return self._exec(argin, "ShotReadyRecipe")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def MeasRepRateRecipe(self, argin: str) -> int:
        
        return self._exec(argin, "MeasRepRateRecipe")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def PreampRepRateRecipe(self, argin: str) -> int:
        
        return self._exec(argin, "PreampRepRateRecipe")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def MeasSingleRecipe(self, argin: str) -> int:
        
        return self._exec(argin, "MeasSingleRecipe")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def PreampSingleRecipe(self, argin: str) -> int:
        
        return self._exec(argin, "PreampSingleRecipe")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def SyncTrigger(self, argin: str) -> int:
        
        return self._exec(argin, "SyncTrigger")

