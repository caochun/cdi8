"""
再生与双程放大组件 仿真器 Device。
Tango Device Path: gxlf/preamplifier/<beamline_id>
"""
from tango import DevShort, DevString
from tango.server import command
from .base import SimBase


class Preamplifier(SimBase):
    """仿真 再生与双程放大组件 的 Tango Device。"""

    @command(dtype_in=DevString, dtype_out=DevShort)
    def PreampWakeup(self, argin: str) -> int:
        
        return self._exec(argin, "PreampWakeup")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def PreampLaserOn(self, argin: str) -> int:
        
        return self._exec(argin, "PreampLaserOn")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def EnergyCoarseLoop(self, argin: str) -> int:
        
        return self._exec(argin, "EnergyCoarseLoop")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def EnergyFineLoop(self, argin: str) -> int:
        
        return self._exec(argin, "EnergyFineLoop")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def EnergyMeterReset(self, argin: str) -> int:
        
        return self._exec(argin, "EnergyMeterReset")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def EnergyDataCollect(self, argin: str) -> int:
        
        return self._exec(argin, "EnergyDataCollect")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def PreampStandby(self, argin: str) -> int:
        
        return self._exec(argin, "PreampStandby")

