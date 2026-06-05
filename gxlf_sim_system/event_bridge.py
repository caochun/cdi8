from __future__ import annotations

import json
import threading
import time
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable
from urllib.parse import parse_qs, urlparse

from .lifecycle import LifecycleEventSink, LifecycleLogger
from .models import LifecycleEvent


SSE_HEADERS = {
    "Cache-Control": "no-cache",
    "Connection": "keep-alive",
    "Content-Type": "text/event-stream; charset=utf-8",
    "Access-Control-Allow-Headers": "Cache-Control",
}


@dataclass(frozen=True)
class BridgeConfig:
    root: Path
    host: str = "127.0.0.1"
    port: int = 8765
    delay_seconds: float = 0.01
    max_nodes: int | None = None
    time_scale: float | None = None
    min_delay_seconds: float = 0.0
    max_delay_seconds: float = 3.0
    target_duration_seconds: float | None = 60.0


class SSELifecycleSink(LifecycleEventSink):
    def __init__(self, write_event: Callable[[LifecycleEvent], None], delay_seconds: float = 0.0):
        self.write_event = write_event
        self.delay_seconds = delay_seconds

    def publish(self, event: LifecycleEvent) -> None:
        self.write_event(event)
        if self.delay_seconds > 0:
            time.sleep(self.delay_seconds)

    def close(self) -> None:
        return


def _sse_frame(event: LifecycleEvent) -> bytes:
    payload = json.dumps(event.to_payload(), ensure_ascii=False)
    return (
        f"id: {event.seq}\n"
        "event: message\n"
        f"data: {payload}\n\n"
    ).encode("utf-8")


def run_bridge(config: BridgeConfig) -> None:
    from .cli import build_engine
    from .service_sim import SimTimingConfig

    class Handler(BaseHTTPRequestHandler):
        server_version = "GXLFEventBridge/0.1"

        def handle(self) -> None:
            try:
                super().handle()
            except (BrokenPipeError, ConnectionResetError):
                return

        def log_message(self, format: str, *args: object) -> None:
            print(f"{self.address_string()} - {format % args}")

        def end_headers(self) -> None:
            self.send_header("Access-Control-Allow-Origin", "*")
            super().end_headers()

        def do_OPTIONS(self) -> None:
            self.send_response(HTTPStatus.NO_CONTENT)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Cache-Control")
            self.end_headers()

        def do_GET(self) -> None:
            parsed = urlparse(self.path)
            if parsed.path in ("/", "/health"):
                self._write_json({"status": "ok", "events": "/events"})
                return
            if parsed.path == "/events":
                self._stream_events(parsed.query)
                return
            self.send_error(HTTPStatus.NOT_FOUND, "not found")

        def _write_json(self, payload: dict[str, object]) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _stream_events(self, query: str) -> None:
            params = parse_qs(query)
            delay = _query_float(params.get("delay", []), config.delay_seconds)
            max_nodes = _query_int(params.get("max_nodes", []), config.max_nodes)

            self.send_response(HTTPStatus.OK)
            for key, value in SSE_HEADERS.items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(b": gxlf event bridge connected\n\n")
            self.wfile.flush()

            def write_event(event: LifecycleEvent) -> None:
                self.wfile.write(_sse_frame(event))
                self.wfile.flush()

            lifecycle_logger = LifecycleLogger(sinks=[SSELifecycleSink(write_event, delay)])
            try:
                engine = build_engine(config.root, lifecycle_logger=None)
                time_scale = _effective_time_scale(engine, config.time_scale, config.target_duration_seconds)
                sim_timing = SimTimingConfig(
                    time_scale=time_scale,
                    min_delay_seconds=config.min_delay_seconds,
                    max_delay_seconds=config.max_delay_seconds,
                )
                engine = build_engine(config.root, lifecycle_logger=lifecycle_logger, sim_timing=sim_timing)
                result = engine.run(max_nodes=max_nodes)
                summary = {
                    "flow_status": result.flow_status.value,
                    "executed_nodes": result.executed_nodes,
                    "lifecycle_events": len(lifecycle_logger.events),
                    "time_scale": time_scale,
                    "target_duration_seconds": config.target_duration_seconds,
                }
                self.wfile.write(
                    (
                        "event: gxlf.bridge.complete\n"
                        f"data: {json.dumps(summary, ensure_ascii=False)}\n\n"
                    ).encode("utf-8")
                )
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                return
            finally:
                lifecycle_logger.close()

    server = ThreadingHTTPServer((config.host, config.port), Handler)
    server.daemon_threads = True
    print(f"GXLF event bridge listening on http://{config.host}:{config.port}")
    print(f"  SSE events: http://{config.host}:{config.port}/events")
    print("  Stop with Ctrl+C")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print()
    finally:
        shutdown = threading.Thread(target=server.shutdown)
        shutdown.start()
        shutdown.join(timeout=2)
        server.server_close()


def _query_float(values: list[str], default: float) -> float:
    if not values:
        return default
    try:
        return float(values[0])
    except ValueError:
        return default


def _query_int(values: list[str], default: int | None) -> int | None:
    if not values:
        return default
    try:
        return int(values[0])
    except ValueError:
        return default


def _effective_time_scale(engine: object, configured: float | None, target_duration_seconds: float | None) -> float:
    if configured is not None:
        return configured
    if target_duration_seconds is None or target_duration_seconds <= 0:
        return 0.0
    estimate = getattr(engine, "estimated_critical_path_duration_seconds")()
    if estimate <= 0:
        return configured
    return target_duration_seconds / estimate
