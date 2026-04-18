"""
开关驱动源组件 仿真器 Device。
Tango Device Path: gxlf/switch_driver/<beamline_id>
"""
from tango import DevShort, DevString
from tango.server import command
from .base import SimBase


class SwitchDriver(SimBase):
    """仿真 开关驱动源组件 的 Tango Device。"""

    @command(dtype_in=DevString, dtype_out=DevShort)
    def SwitchCharge(self, argin: str) -> int:
        
        return self._exec(argin, "SwitchCharge")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def TriggerReady(self, argin: str) -> int:
        
        return self._exec(argin, "TriggerReady")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def DischargeWaveformCollect(self, argin: str) -> int:
        
        return self._exec(argin, "DischargeWaveformCollect")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def SwitchStandby(self, argin: str) -> int:
        
        return self._exec(argin, "SwitchStandby")

