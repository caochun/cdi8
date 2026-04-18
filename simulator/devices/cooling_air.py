"""
冷空系统 仿真器 Device。
Tango Device Path: gxlf/cooling_air/01
"""
from tango import DevShort, DevString
from tango.server import command
from .base import SimBase


class CoolingAir(SimBase):
    """仿真 冷空系统 的 Tango Device。"""

    @command(dtype_in=DevString, dtype_out=DevShort)
    def SlabPurge(self, argin: str) -> int:
        
        return self._exec(argin, "SlabPurge")

