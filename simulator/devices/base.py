"""
SimBase: 所有仿真器 Device 的基类。

支持四种运行模式（可通过 SetMode 命令在运行时切换）：
  normal   - 命令正常执行，返回成功状态（默认 200ms 延迟）
  slow     - 命令正常执行，但延迟较长（模拟设备繁忙，默认 5s）
  fail     - 命令立即返回失败状态码 0
  no_perm  - 命令返回无服务权限状态码 2
  timeout  - 命令挂起不返回（模拟超时场景）
"""

import json
import logging
import threading
import time

from tango import DevShort, DevState, DevString
from tango.server import Device, attribute, command, run

logger = logging.getLogger(__name__)


class SimBase(Device):
    """GXLF 仿真器基类。"""

    # ── 内部状态 ──────────────────────────────────────────────────────────────

    def init_device(self):
        super().init_device()
        self._mode: str = "normal"
        self._normal_delay: float = 0.2   # 正常模式延迟（秒）
        self._slow_delay: float = 5.0     # slow 模式延迟（秒）
        self._cmd_count: int = 0
        self._lock = threading.Lock()
        self.set_state(DevState.ON)
        logger.info("[%s] initialized, mode=normal", self.__class__.__name__)

    # ── 通用执行逻辑 ──────────────────────────────────────────────────────────

    def _exec(self, argin: str, cmd_name: str = "") -> int:
        """
        解析 JSON 输入，根据当前模式模拟执行，返回状态码。
        状态码约定：0=失败，1=收到，2=无服务权限
        """
        try:
            params = json.loads(argin) if argin.strip() else {}
        except json.JSONDecodeError:
            logger.warning("[%s] %s: invalid JSON input %r",
                           self.__class__.__name__, cmd_name, argin)
            params = {}

        with self._lock:
            self._cmd_count += 1

        logger.info("[%s] %s mode=%s params=%s",
                    self.__class__.__name__, cmd_name, self._mode, params)

        if self._mode == "timeout":
            time.sleep(3600)
            return 0

        if self._mode == "fail":
            time.sleep(0.05)
            return 0

        if self._mode == "no_perm":
            return 2

        delay = self._slow_delay if self._mode == "slow" else self._normal_delay
        time.sleep(delay)
        return 1

    def _exec_json(self, argin: str, cmd_name: str = "", extra: dict = None) -> str:
        """
        与 _exec 相同，但返回 JSON 字符串（用于复杂输出接口）。
        extra: 额外字段合并到返回 JSON 中。
        """
        status = self._exec(argin, cmd_name)
        result = {"accept_status": status}
        if extra:
            result.update(extra)
        return json.dumps(result, ensure_ascii=False)

    # ── 控制命令 ──────────────────────────────────────────────────────────────

    @command(dtype_in=DevString, doc_in="Mode: normal / slow / fail / no_perm / timeout")
    def SetMode(self, mode: str):
        """设置仿真模式。"""
        valid = {"normal", "slow", "fail", "no_perm", "timeout"}
        if mode not in valid:
            raise ValueError(f"Invalid mode '{mode}'. Choose from {valid}")
        self._mode = mode
        logger.info("[%s] mode changed to '%s'", self.__class__.__name__, mode)

    @command(dtype_out=DevString)
    def GetStatus(self) -> str:
        """返回设备当前状态 JSON。"""
        return json.dumps({
            "device": self.__class__.__name__,
            "mode": self._mode,
            "cmd_count": self._cmd_count,
            "state": str(self.get_state()),
        }, ensure_ascii=False)
