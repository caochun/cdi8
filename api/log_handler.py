"""
api/log_handler.py — Python logging → SSE 事件桥接

将 Python logging 产生的日志记录转发为 SSE "log" 事件。
"""
import asyncio
import logging


class SSELogHandler(logging.Handler):
    """将 logging 记录异步广播为 SSE 事件。"""

    def __init__(self, broadcaster):
        super().__init__()
        self._broadcaster = broadcaster

    def emit(self, record: logging.LogRecord) -> None:
        try:
            loop = asyncio.get_event_loop()
            if loop.is_running():
                loop.create_task(self._send(record))
        except RuntimeError:
            pass

    async def _send(self, record: logging.LogRecord) -> None:
        try:
            await self._broadcaster.broadcast("log", {
                "level": record.levelname,
                "logger": record.name,
                "msg": self.format(record),
                "ts": record.created,
            })
        except Exception:
            pass
