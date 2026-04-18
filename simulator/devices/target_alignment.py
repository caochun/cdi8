"""
靶瞄准定位分系统 仿真器 Device。
Tango Device Path: gxlf/target_alignment/01
"""
from tango import DevShort, DevString
from tango.server import command
from .base import SimBase


class TargetAlignment(SimBase):
    """仿真 靶瞄准定位分系统 的 Tango Device。"""

    @command(dtype_in=DevString, dtype_out=DevShort)
    def ExpTargetPrePosition(self, argin: str) -> int:
        
        return self._exec(argin, "ExpTargetPrePosition")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def SimTargetPosition(self, argin: str) -> int:
        
        return self._exec(argin, "SimTargetPosition")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def BeamGuide(self, argin: str) -> int:
        
        return self._exec(argin, "BeamGuide")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def ExpTargetReset(self, argin: str) -> int:
        
        return self._exec(argin, "ExpTargetReset")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def TargetShotStatusConfirm(self, argin: str) -> int:
        
        return self._exec(argin, "TargetShotStatusConfirm")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def TargetRetract(self, argin: str) -> int:
        
        return self._exec(argin, "TargetRetract")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def TargetAlignDataAnalysis(self, argin: str) -> int:
        
        return self._exec(argin, "TargetAlignDataAnalysis")

