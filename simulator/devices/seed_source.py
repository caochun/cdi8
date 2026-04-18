"""
光纤种子源组件 仿真器 Device。
Tango Device Path: gxlf/seed_source/01
"""
from tango import DevShort, DevString
from tango.server import command
from .base import SimBase


class SeedSource(SimBase):
    """仿真 光纤种子源组件 的 Tango Device。"""

    @command(dtype_in=DevString, dtype_out=DevShort)
    def SeedLaserOn(self, argin: str) -> int:
        
        return self._exec(argin, "SeedLaserOn")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def SeedLaserParamCollect(self, argin: str) -> int:
        
        return self._exec(argin, "SeedLaserParamCollect")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def SeedLaserStandby(self, argin: str) -> int:
        
        return self._exec(argin, "SeedLaserStandby")

