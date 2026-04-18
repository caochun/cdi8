"""
二倍频宽带激光注入组件 仿真器 Device。
Tango Device Path: gxlf/shg_injection/01
"""
from tango import DevShort, DevString
from tango.server import command
from .base import SimBase


class SHGInjection(SimBase):
    """仿真 二倍频宽带激光注入组件 的 Tango Device。"""

    @command(dtype_in=DevString, dtype_out=DevShort)
    def SHGLaserOn(self, argin: str) -> int:
        
        return self._exec(argin, "SHGLaserOn")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def SHGLaserParamCollect(self, argin: str) -> int:
        
        return self._exec(argin, "SHGLaserParamCollect")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def SHGLaserStandby(self, argin: str) -> int:
        
        return self._exec(argin, "SHGLaserStandby")

