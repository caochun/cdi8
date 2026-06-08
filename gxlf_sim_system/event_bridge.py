from __future__ import annotations

import json
import threading
import time
from dataclasses import dataclass, replace
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

from .lifecycle import LifecycleEventSink, LifecycleLogger
from .domain import LifecycleEvent
from .guards import FlowRuntimeContext
from .service_sim import SimFault, SimFaultRegistry


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


@dataclass(frozen=True)
class BridgeFrame:
    seq: int
    event_name: str
    payload: dict[str, Any]


class EventBroadcaster:
    def __init__(self) -> None:
        self._condition = threading.Condition()
        self._frames: list[BridgeFrame] = []
        self._complete_summary: dict[str, Any] | None = None
        self._error: str | None = None
        self._status = "idle"
        self._subscribers = 0
        self._seq = 0

    def publish_lifecycle(self, event: LifecycleEvent) -> None:
        self.publish("gxlf.lifecycle", event.to_payload())

    def publish(self, event_name: str, payload: dict[str, Any]) -> None:
        with self._condition:
            self._seq += 1
            self._frames.append(BridgeFrame(self._seq, event_name, payload))
            self._condition.notify_all()

    def set_status(self, status: str, detail: dict[str, Any] | None = None) -> None:
        with self._condition:
            self._status = status
        payload = {"status": status}
        if detail:
            payload.update(detail)
        self.publish("gxlf.engine.status", payload)

    def complete(self, summary: dict[str, Any]) -> None:
        flow_status = str(summary.get("flow_status") or "completed")
        status = "completed" if flow_status == "completed" else flow_status
        with self._condition:
            self._complete_summary = summary
            self._status = status
        self.publish("gxlf.engine.status", {"status": status, "flow_status": flow_status})
        self.publish("gxlf.bridge.complete", summary)

    def fail(self, message: str) -> None:
        with self._condition:
            self._error = message
            self._status = "failed"
        self.publish("gxlf.bridge.error", {"status": "failed", "message": message})

    def reset(self) -> None:
        with self._condition:
            self._complete_summary = None
            self._error = None
            self._status = "idle"
            self._condition.notify_all()
        self.publish("gxlf.engine.status", {"status": "idle", "reason": "reset"})

    def add_subscriber(self) -> None:
        with self._condition:
            self._subscribers += 1

    def remove_subscriber(self) -> None:
        with self._condition:
            self._subscribers = max(self._subscribers - 1, 0)

    def snapshot(self) -> dict[str, Any]:
        with self._condition:
            return {
                "status": self._status,
                "events": len(self._frames),
                "subscribers": self._subscribers,
                "complete": self._complete_summary is not None,
                "error": self._error,
            }

    def event_count(self) -> int:
        with self._condition:
            return len(self._frames)

    def wait_for_frame(self, offset: int) -> BridgeFrame:
        with self._condition:
            while offset >= len(self._frames):
                self._condition.wait()
            return self._frames[offset]

    def terminal_summary(self) -> dict[str, Any] | None:
        with self._condition:
            return self._complete_summary

    def terminal_error(self) -> str | None:
        with self._condition:
            return self._error


class BroadcastLifecycleSink(LifecycleEventSink):
    def __init__(self, broadcaster: EventBroadcaster):
        self.broadcaster = broadcaster

    def publish(self, event: LifecycleEvent) -> None:
        self.broadcaster.publish_lifecycle(event)

    def close(self) -> None:
        return


def _sse_frame(event: LifecycleEvent, event_name: str = "message") -> bytes:
    payload = json.dumps(event.to_payload(), ensure_ascii=False)
    return (
        f"id: {event.seq}\n"
        f"event: {event_name}\n"
        f"data: {payload}\n\n"
    ).encode("utf-8")


def _bridge_frame(frame: BridgeFrame, event_name: str | None = None) -> bytes:
    data = json.dumps(frame.payload, ensure_ascii=False)
    return (
        f"id: {frame.seq}\n"
        f"event: {event_name or frame.event_name}\n"
        f"data: {data}\n\n"
    ).encode("utf-8")


def _named_sse_frame(event_name: str, payload: dict[str, Any]) -> bytes:
    data = json.dumps(payload, ensure_ascii=False)
    return (
        f"event: {event_name}\n"
        f"data: {data}\n\n"
    ).encode("utf-8")


def _publish_engine_event(broadcaster: EventBroadcaster, event: Any, context: FlowRuntimeContext) -> None:
    payload = {
        "event_type": event.event_type,
        "node_name": event.node_name,
        "node_id": event.node_id,
        "detail": event.detail,
        "fan_out_total": event.fan_out_total,
        "fan_out_success": event.fan_out_success,
        "fan_out_failed": event.fan_out_failed,
        **event.data,
    }
    if event.event_type == "node_guard_blocked":
        broadcaster.set_status("waiting_guard", {"guard_block": payload, "guard_context": context.snapshot()})
    if event.event_type == "node_guard_passed":
        broadcaster.set_status("running", {"guard_context": context.snapshot()})
    broadcaster.publish("gxlf.engine.event", payload)


class BridgeEngineController:
    def __init__(self, config: BridgeConfig, broadcaster: EventBroadcaster):
        self.config = config
        self.broadcaster = broadcaster
        self.runtime_context = FlowRuntimeContext()
        self.fault_registry = SimFaultRegistry()
        self.service_health_overrides: dict[str, str] = {}
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None
        self._control: Any = None
        self._engine: Any = None

    def command(self, command: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        payload = payload or {}
        command = command.lower()
        command_event: dict[str, Any] = {"command": command}
        if command == "start":
            command_event.update(_start_options_from_payload(payload, self.config))
        if command in ("set_guard", "set_flag", "set_interlock"):
            command_event.update(_guard_options_from_payload(command, payload))
        if command in ("inject_fault", "set_service_health"):
            command_event.update(_fault_options_from_payload(command, payload))
        self.broadcaster.publish("gxlf.engine.command", command_event)
        if command == "start":
            return self.start(payload)
        if command == "pause":
            return self.pause()
        if command == "resume":
            return self.resume()
        if command == "stop":
            return self.stop()
        if command == "reset":
            return self.reset()
        if command in ("set_guard", "set_flag", "set_interlock"):
            return self.set_guard(command, payload)
        if command == "inject_fault":
            return self.inject_fault(payload)
        if command == "clear_faults":
            return self.clear_faults()
        if command == "set_service_health":
            return self.set_service_health(payload)
        return {"accepted": False, "message": f"unsupported command {command}"}

    def start(self, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        run_config = _run_config_from_payload(self.config, payload or {})
        start_options = _start_options_from_config(self.config, run_config)
        with self._lock:
            if self._thread and self._thread.is_alive():
                return {"accepted": False, "message": "engine is already running"}
            self.broadcaster.reset()
            self._thread = threading.Thread(
                target=_run_simulation_once,
                args=(run_config, self.broadcaster, self, start_options),
                name="gxlf-event-bridge-simulation",
                daemon=True,
            )
            self._thread.start()
        return {"accepted": True, "message": "engine start requested", **start_options}

    def pause(self) -> dict[str, Any]:
        with self._lock:
            control = self._control
        if not control:
            return {"accepted": False, "message": "engine is not running"}
        control.pause()
        self.broadcaster.set_status("paused")
        return {"accepted": True, "message": "engine pause requested"}

    def resume(self) -> dict[str, Any]:
        with self._lock:
            control = self._control
        if not control:
            return {"accepted": False, "message": "engine is not running"}
        control.resume()
        self.broadcaster.set_status("running")
        return {"accepted": True, "message": "engine resume requested"}

    def stop(self) -> dict[str, Any]:
        with self._lock:
            control = self._control
        if not control:
            return {"accepted": False, "message": "engine is not running"}
        control.stop()
        self.broadcaster.set_status("stopping")
        return {"accepted": True, "message": "engine stop requested"}

    def reset(self) -> dict[str, Any]:
        with self._lock:
            if self._thread and self._thread.is_alive():
                return {"accepted": False, "message": "stop the running engine before reset"}
            self._control = None
        self.broadcaster.reset()
        return {"accepted": True, "message": "engine reset"}

    def set_guard(self, command: str, payload: dict[str, Any]) -> dict[str, Any]:
        key = str(payload.get("key") or payload.get("guard") or "")
        if not key:
            return {"accepted": False, "message": "guard key is required"}
        value = payload.get("value")
        if value is None:
            value = payload.get("state")

        guard_type = str(payload.get("type") or "")
        if command == "set_flag" or guard_type == "context_flag" or key in self.runtime_context.flags:
            self.runtime_context.set_flag(key, value)
            update = {"type": "context_flag", "key": key, "value": value}
        else:
            self.runtime_context.set_interlock(key, value)
            update = {"type": "interlock_state", "key": key, "value": value}

        self.broadcaster.publish("gxlf.guard.update", update)
        self.broadcaster.set_status(self.broadcaster.snapshot()["status"], {"guard_context": self.runtime_context.snapshot()})
        return {"accepted": True, "message": "guard updated", **update}

    def inject_fault(self, payload: dict[str, Any]) -> dict[str, Any]:
        fault_payload = payload.get("fault") if isinstance(payload.get("fault"), dict) else payload
        behavior = str(fault_payload.get("behavior") or fault_payload.get("type") or "")
        if behavior not in {"reject", "callback_failed", "callback_timeout"}:
            return {"accepted": False, "message": "fault behavior must be reject, callback_failed, or callback_timeout"}
        fault = self.fault_registry.add(
            SimFault(
                behavior=behavior,
                node_id=str(fault_payload.get("node_id") or ""),
                system_name=str(fault_payload.get("system_name") or fault_payload.get("system") or ""),
                service_id=str(fault_payload.get("service_id") or ""),
                instance_code=str(fault_payload.get("instance_code") or fault_payload.get("instance") or ""),
                command=str(fault_payload.get("command_name") or fault_payload.get("command_filter") or ""),
            )
        )
        update = {"action": "inject", "fault": fault.to_payload(), "faults": self.fault_registry.snapshot()}
        self.broadcaster.publish("gxlf.fault.update", update)
        self.broadcaster.set_status(self.broadcaster.snapshot()["status"], {"faults": self.fault_registry.snapshot()})
        return {"accepted": True, "message": "fault injected", **update}

    def clear_faults(self) -> dict[str, Any]:
        self.fault_registry.clear()
        self.service_health_overrides.clear()
        self.runtime_context.set_flag("recipe_loaded", True)
        self.runtime_context.set_flag("central_control_healthy", True)
        self.runtime_context.set_interlock("safety", "normal")
        with self._lock:
            engine = self._engine
        if engine:
            self._restore_service_health(engine)
        update = {
            "action": "clear",
            "faults": [],
            "guard_context": self.runtime_context.snapshot(),
            "service_health_overrides": {},
        }
        self.broadcaster.publish("gxlf.fault.update", update)
        self.broadcaster.set_status(self.broadcaster.snapshot()["status"], update)
        return {"accepted": True, "message": "faults cleared", **update}

    def set_service_health(self, payload: dict[str, Any]) -> dict[str, Any]:
        system_name = str(payload.get("system_name") or payload.get("system") or "")
        health_state = str(payload.get("health_state") or payload.get("state") or "")
        if not system_name or not health_state:
            return {"accepted": False, "message": "system_name and health_state are required"}
        self.service_health_overrides[system_name] = health_state
        with self._lock:
            engine = self._engine
        if engine:
            self._apply_service_health_override(engine, system_name, health_state)
        self.runtime_context.notify_update()
        update = {
            "action": "service_health",
            "system_name": system_name,
            "health_state": health_state,
            "service_health_overrides": dict(self.service_health_overrides),
        }
        self.broadcaster.publish("gxlf.fault.update", update)
        self.broadcaster.set_status(self.broadcaster.snapshot()["status"], update)
        return {"accepted": True, "message": "service health updated", **update}

    def apply_service_health_overrides(self, engine: Any) -> None:
        for system_name, health_state in self.service_health_overrides.items():
            self._apply_service_health_override(engine, system_name, health_state)

    def _apply_service_health_override(self, engine: Any, system_name: str, health_state: str) -> None:
        for instance in engine.registry.instances_by_system.get(system_name, []):
            instance.health_state = health_state

    def _restore_service_health(self, engine: Any) -> None:
        for instances in engine.registry.instances_by_system.values():
            for instance in instances:
                instance.health_state = "running"

    def bind_control(self, control: Any) -> None:
        with self._lock:
            self._control = control

    def bind_engine(self, engine: Any) -> None:
        with self._lock:
            self._engine = engine

    def clear_control(self, control: Any) -> None:
        with self._lock:
            if self._control is control:
                self._control = None

    def clear_engine(self, engine: Any) -> None:
        with self._lock:
            if self._engine is engine:
                self._engine = None


def _run_simulation_once(
    config: BridgeConfig,
    broadcaster: EventBroadcaster,
    controller: BridgeEngineController,
    start_options: dict[str, Any] | None = None,
) -> None:
    from .cli import build_engine
    from .engine import FlowRunControl
    from .service_sim import SimTimingConfig

    lifecycle_logger = LifecycleLogger(sinks=[BroadcastLifecycleSink(broadcaster)])
    control = FlowRunControl()
    controller.bind_control(control)
    try:
        probe_engine = build_engine(config.root, lifecycle_logger=None, runtime_context=controller.runtime_context)
        time_scale = _effective_time_scale(probe_engine, config.time_scale, config.target_duration_seconds)
        sim_timing = SimTimingConfig(
            time_scale=time_scale,
            min_delay_seconds=config.min_delay_seconds,
            max_delay_seconds=config.max_delay_seconds,
        )
        engine = build_engine(
            config.root,
            lifecycle_logger=lifecycle_logger,
            sim_timing=sim_timing,
            runtime_context=controller.runtime_context,
            engine_event_sink=lambda event: _publish_engine_event(broadcaster, event, controller.runtime_context),
            fault_registry=controller.fault_registry,
        )
        controller.apply_service_health_overrides(engine)
        controller.bind_engine(engine)
        status_detail = {
            "time_scale": time_scale,
            "target_duration_seconds": config.target_duration_seconds,
            "guard_context": controller.runtime_context.snapshot(),
            "faults": controller.fault_registry.snapshot(),
            "service_health_overrides": dict(controller.service_health_overrides),
            **(start_options or {}),
        }
        broadcaster.set_status("running", status_detail)
        result = engine.run(max_nodes=config.max_nodes, control=control)
        broadcaster.complete(
            {
                "flow_status": result.flow_status.value,
                "executed_nodes": result.executed_nodes,
                "lifecycle_events": len(lifecycle_logger.events),
                "time_scale": time_scale,
                "target_duration_seconds": config.target_duration_seconds,
                "max_nodes": config.max_nodes,
                "guard_context": controller.runtime_context.snapshot(),
                "faults": controller.fault_registry.snapshot(),
                "service_health_overrides": dict(controller.service_health_overrides),
                **(start_options or {}),
            }
        )
    except Exception as exc:
        message = f"{type(exc).__name__}: {exc}"
        print(f"GXLF event bridge simulation failed: {message}")
        broadcaster.fail(message)
    finally:
        lifecycle_logger.close()
        if "engine" in locals():
            controller.clear_engine(engine)
        controller.clear_control(control)


def run_bridge(config: BridgeConfig) -> None:
    broadcaster = EventBroadcaster()
    controller = BridgeEngineController(config, broadcaster)
    broadcaster.publish("gxlf.engine.status", {"status": "idle"})

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
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Cache-Control, Content-Type")
            self.end_headers()

        def do_POST(self) -> None:
            parsed = urlparse(self.path)
            if parsed.path != "/commands":
                self.send_error(HTTPStatus.NOT_FOUND, "not found")
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(length) if length > 0 else b"{}"
                payload = json.loads(raw.decode("utf-8"))
            except (ValueError, json.JSONDecodeError):
                self.send_error(HTTPStatus.BAD_REQUEST, "invalid json")
                return
            command = str(payload.get("command") or "")
            result = controller.command(command, payload)
            status = HTTPStatus.OK if result.get("accepted") else HTTPStatus.CONFLICT
            self._write_json(result, status=status)

        def do_GET(self) -> None:
            parsed = urlparse(self.path)
            if parsed.path in ("/", "/health"):
                self._write_json({"status": "ok", "events": "/events", "bridge": broadcaster.snapshot()})
                return
            if parsed.path == "/events":
                self._stream_events(parsed.query)
                return
            self.send_error(HTTPStatus.NOT_FOUND, "not found")

        def _write_json(self, payload: dict[str, object], status: HTTPStatus = HTTPStatus.OK) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _stream_events(self, query: str) -> None:
            params = parse_qs(query)
            delay = _query_float(params.get("delay", []), config.delay_seconds)

            self.send_response(HTTPStatus.OK)
            for key, value in SSE_HEADERS.items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(b": gxlf event bridge connected\n\n")
            self.wfile.flush()

            offset = 0
            broadcaster.add_subscriber()
            replay_until = broadcaster.event_count()
            try:
                while True:
                    frame = broadcaster.wait_for_frame(offset)
                    event_name = frame.event_name
                    if event_name == "gxlf.lifecycle":
                        event_name = "gxlf.lifecycle.replay" if offset < replay_until else "gxlf.lifecycle.live"
                    self.wfile.write(_bridge_frame(frame, event_name))
                    self.wfile.flush()
                    offset += 1
                    if delay > 0:
                        time.sleep(delay)
            except (BrokenPipeError, ConnectionResetError):
                return
            finally:
                broadcaster.remove_subscriber()

    server = ThreadingHTTPServer((config.host, config.port), Handler)
    server.daemon_threads = True
    print(f"GXLF event bridge listening on http://{config.host}:{config.port}")
    print(f"  SSE events: http://{config.host}:{config.port}/events")
    print(f"  Engine commands: http://{config.host}:{config.port}/commands")
    print("  Simulation waits for a start command; SSE clients receive buffered and live events")
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


def _guard_options_from_payload(command: str, payload: dict[str, Any]) -> dict[str, Any]:
    value = payload.get("value")
    if value is None:
        value = payload.get("state")
    return {
        "guard_command": command,
        "key": payload.get("key") or payload.get("guard"),
        "value": value,
        "type": payload.get("type"),
    }


def _fault_options_from_payload(command: str, payload: dict[str, Any]) -> dict[str, Any]:
    fault_payload = payload.get("fault") if isinstance(payload.get("fault"), dict) else payload
    return {
        "fault_command": command,
        "behavior": fault_payload.get("behavior") or fault_payload.get("type"),
        "node_id": fault_payload.get("node_id"),
        "system_name": fault_payload.get("system_name") or fault_payload.get("system"),
        "service_id": fault_payload.get("service_id"),
        "instance_code": fault_payload.get("instance_code") or fault_payload.get("instance"),
        "health_state": payload.get("health_state") or payload.get("state"),
    }


def _payload_float(payload: dict[str, Any], key: str) -> float | None:
    value = payload.get(key)
    if value is None:
        return None
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return None
    return parsed if parsed > 0 else None


def _run_config_from_payload(config: BridgeConfig, payload: dict[str, Any]) -> BridgeConfig:
    speed_multiplier = _payload_float(payload, "speed_multiplier") or 1.0
    target_duration = _payload_float(payload, "target_duration_seconds")
    time_scale = _payload_float(payload, "time_scale")

    if target_duration is None and config.target_duration_seconds and config.target_duration_seconds > 0:
        target_duration = config.target_duration_seconds / speed_multiplier
    if time_scale is None and config.time_scale is not None:
        time_scale = config.time_scale / speed_multiplier

    return replace(config, target_duration_seconds=target_duration, time_scale=time_scale)


def _start_options_from_payload(payload: dict[str, Any], config: BridgeConfig) -> dict[str, Any]:
    return _start_options_from_config(config, _run_config_from_payload(config, payload))


def _start_options_from_config(base_config: BridgeConfig, run_config: BridgeConfig) -> dict[str, Any]:
    if run_config.target_duration_seconds and base_config.target_duration_seconds:
        speed_multiplier = base_config.target_duration_seconds / run_config.target_duration_seconds
    elif run_config.time_scale and base_config.time_scale:
        speed_multiplier = base_config.time_scale / run_config.time_scale
    else:
        speed_multiplier = 1.0
    return {
        "speed_multiplier": speed_multiplier,
        "target_duration_seconds": run_config.target_duration_seconds,
        "time_scale_override": run_config.time_scale,
    }


def _effective_time_scale(engine: object, configured: float | None, target_duration_seconds: float | None) -> float:
    if configured is not None:
        return configured
    if target_duration_seconds is None or target_duration_seconds <= 0:
        return 0.0
    estimate = getattr(engine, "estimated_critical_path_duration_seconds")()
    if estimate <= 0:
        return 0.0
    return target_duration_seconds / estimate
