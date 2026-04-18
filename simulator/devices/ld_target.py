"""
LD靶分系统 仿真器 Device。
Tango Device Path: gxlf/ld_target/01
"""
from tango import DevShort, DevString
from tango.server import command
from .base import SimBase


class LDTarget(SimBase):
    """仿真 LD靶分系统 的 Tango Device。"""

    @command(dtype_in=DevString, dtype_out=DevShort)
    def SimLDTargetOpen(self, argin: str) -> int:
        
        return self._exec(argin, "SimLDTargetOpen")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def LDTargetReset(self, argin: str) -> int:
        
        return self._exec(argin, "LDTargetReset")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def LDTargetOpen(self, argin: str) -> int:
        
        return self._exec(argin, "LDTargetOpen")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def LDTargetRetract(self, argin: str) -> int:
        
        return self._exec(argin, "LDTargetRetract")

