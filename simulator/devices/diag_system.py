"""
物理实验诊断分系统 仿真器 Device。
Tango Device Path: gxlf/diag_system/01
"""
from tango import DevShort, DevString
from tango.server import command
from .base import SimBase


class DiagSystem(SimBase):
    """仿真 物理实验诊断分系统 的 Tango Device。"""

    @command(dtype_in=DevString, dtype_out=DevShort)
    def DiagCollectSetup(self, argin: str) -> int:
        
        return self._exec(argin, "DiagCollectSetup")

    @command(dtype_in=DevString, dtype_out=DevString)
    def DiagStatusConfirm(self, argin: str) -> str:
        
        return self._exec_json(argin, "DiagStatusConfirm")

    @command(dtype_in=DevString, dtype_out=DevString)
    def DiagDeviceAction(self, argin: str) -> str:
        
        return self._exec_json(argin, "DiagDeviceAction")

    @command(dtype_in=DevString, dtype_out=DevString)
    def DiagDataCollect(self, argin: str) -> str:
        
        return self._exec_json(argin, "DiagDataCollect")

    @command(dtype_in=DevString, dtype_out=DevString)
    def DiagShotPostProcess(self, argin: str) -> str:
        
        return self._exec_json(argin, "DiagShotPostProcess")

