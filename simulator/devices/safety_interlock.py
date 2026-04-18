"""
安全联锁 仿真器 Device。
Tango Device Path: gxlf/safety_interlock/01
"""
from tango import DevShort, DevString
from tango.server import command
from .base import SimBase


class SafetyInterlock(SimBase):
    """仿真 安全联锁 的 Tango Device。"""

    @command(dtype_in=DevString, dtype_out=DevShort)
    def WarningLightControl(self, argin: str) -> int:
        
        return self._exec(argin, "WarningLightControl")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def WarningSoundControl(self, argin: str) -> int:
        
        return self._exec(argin, "WarningSoundControl")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def AreaClear(self, argin: str) -> int:
        
        return self._exec(argin, "AreaClear")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def ShieldDoorControl(self, argin: str) -> int:
        
        return self._exec(argin, "ShieldDoorControl")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def SafetyStateControl(self, argin: str) -> int:
        
        return self._exec(argin, "SafetyStateControl")

