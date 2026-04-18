"""
多程放大分系统 仿真器 Device。
Tango Device Path: gxlf/main_amplifier/<beamline_id>
"""
from tango import DevShort, DevString
from tango.server import command
from .base import SimBase


class MainAmplifier(SimBase):
    """仿真 多程放大分系统 的 Tango Device。"""

    @command(dtype_in=DevString, dtype_out=DevShort)
    def BeamlineAlign(self, argin: str) -> int:
        
        return self._exec(argin, "BeamlineAlign")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def RepRateLaserConfirm(self, argin: str) -> int:
        
        return self._exec(argin, "RepRateLaserConfirm")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def ShotStatusConfirm(self, argin: str) -> int:
        
        return self._exec(argin, "ShotStatusConfirm")

