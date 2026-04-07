"""
api/broadcaster.py — SSE 事件广播器

将后端事件扇出给所有已连接的 SSE 客户端。
每个客户端持有一个独立的 asyncio.Queue，broadcast() 写入所有队列。
"""
import asyncio
import json
import logging
import time
from collections.abc import AsyncIterator

logger = logging.getLogger(__name__)


class EventBroadcaster:
    def __init__(self):
        self._clients: list[asyncio.Queue] = []
        # 最近 500 条事件，新客户端连接时补发日志类事件
        self._log_buffer: list[dict] = []
        self._log_buffer_max = 500

    async def broadcast(self, event_type: str, data: dict) -> None:
        """广播事件到所有连接的客户端。"""
        payload = json.dumps({"type": event_type, "data": data, "ts": time.time()}, ensure_ascii=False)
        dead = []
        for q in self._clients:
            try:
                q.put_nowait(payload)
            except asyncio.QueueFull:
                dead.append(q)
        for q in dead:
            if q in self._clients:
                self._clients.remove(q)

        # 缓存日志事件供新客户端追赶
        if event_type == "log":
            self._log_buffer.append({"type": event_type, "data": data, "ts": time.time()})
            if len(self._log_buffer) > self._log_buffer_max:
                self._log_buffer = self._log_buffer[-self._log_buffer_max:]

    async def subscribe(self, catchup_logs: bool = True) -> AsyncIterator[str]:
        """注册为新 SSE 客户端，返回 JSON 字符串异步迭代器（由 EventSourceResponse 包装为 SSE 格式）。"""
        q: asyncio.Queue = asyncio.Queue(maxsize=1000)
        self._clients.append(q)
        try:
            # 补发最近日志（让新连接的客户端看到历史）
            if catchup_logs:
                for entry in self._log_buffer[-100:]:
                    yield json.dumps(entry, ensure_ascii=False)
            while True:
                payload = await q.get()
                yield payload
        except asyncio.CancelledError:
            pass
        finally:
            if q in self._clients:
                self._clients.remove(q)

    @property
    def client_count(self) -> int:
        return len(self._clients)
