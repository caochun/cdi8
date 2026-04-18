"""
泵浦分系统 仿真器 Device。
Tango Device Path: gxlf/pump_laser/<beamline_id>
"""
from tango import DevShort, DevString
from tango.server import command
from .base import SimBase


class PumpLaser(SimBase):
    """仿真 泵浦分系统 的 Tango Device。"""

    @command(dtype_in=DevString, dtype_out=DevShort)
    def PumpShotReady(self, argin: str) -> int:
        
        return self._exec(argin, "PumpShotReady")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def ShotChargeReady(self, argin: str) -> int:
        
        return self._exec(argin, "ShotChargeReady")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def ShotCharge(self, argin: str) -> int:
        
        return self._exec(argin, "ShotCharge")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def PreionizationShotReady(self, argin: str) -> int:
        
        return self._exec(argin, "PreionizationShotReady")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def PreionizationShotCharge(self, argin: str) -> int:
        
        return self._exec(argin, "PreionizationShotCharge")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def PumpTriggerReady(self, argin: str) -> int:
        
        return self._exec(argin, "PumpTriggerReady")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def PumpDataCollect(self, argin: str) -> int:
        
        return self._exec(argin, "PumpDataCollect")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def PumpStandby(self, argin: str) -> int:
        
        return self._exec(argin, "PumpStandby")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def ChargeProgressReport(self, argin: str) -> int:
        
        return self._exec(argin, "ChargeProgressReport")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def ShutdownReset(self, argin: str) -> int:
        
        return self._exec(argin, "ShutdownReset")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def EmergencyStopReport(self, argin: str) -> int:
        
        return self._exec(argin, "EmergencyStopReport")

