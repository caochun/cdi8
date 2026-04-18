"""
真空靶室分系统 仿真器 Device。
Tango Device Path: gxlf/vacuum_chamber/01
"""
import json
from tango import DevBoolean, DevShort, DevString
from tango.server import attribute, command
from .base import SimBase


class VacuumChamber(SimBase):
    """仿真 真空靶室分系统 的 Tango Device。"""

    @attribute(dtype=DevBoolean)
    def VacuumLevelOk(self) -> bool:
        # 正常模式返回 True，fail 模式返回 False
        return self._mode not in ("fail", "no_perm")

    @command(dtype_in=DevString, dtype_out=DevShort)
    def VacuumPump(self, argin: str) -> int:
        # 返回 4 种状态码（见接口文档）
        import time
        try:
            params = json.loads(argin) if argin.strip() else {}
        except Exception:
            params = {}
        if self._mode == "timeout":
            time.sleep(3600)
            return 0
        if self._mode == "fail":
            return 1  # 系统自检未通过
        if self._mode == "no_perm":
            return 0
        time.sleep(self._slow_delay if self._mode == "slow" else self._normal_delay)
        return 3  # 自检通过、功能检查通过、抽气启动中

