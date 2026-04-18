"""
频率转换分系统 仿真器 Device。
Tango Device Path: gxlf/freq_conversion/<beamline_id>
"""
from tango import DevShort, DevString
from tango.server import command
from .base import SimBase


class FreqConversion(SimBase):
    """仿真 频率转换分系统 的 Tango Device。"""

    @command(dtype_in=DevString, dtype_out=DevShort)
    def CrystalPoseAdjust(self, argin: str) -> int:
        
        return self._exec(argin, "CrystalPoseAdjust")

