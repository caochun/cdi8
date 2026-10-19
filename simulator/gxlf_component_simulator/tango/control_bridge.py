"""Tango Command/CHANGE_EVENT bridge for the Flowable controller.

No workflow lives here. /events is a live NDJSON subscription, with a fresh
snapshot replay on every connection. GetResult is the recovery source.
"""

import argparse
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
from queue import Empty, Full, Queue
from threading import Event, RLock, Thread
from urllib.parse import urlsplit

from ..component_model import DATA_DIRECTORY
from ..component_runtime import identifier
from .demo import (component_bindings, component_definition, component_specs,
                   reset_component_data, validate_counts)


class TangoBridge:
    def __init__(self, bindings):
        import tango

        self.lock = RLock()
        self.clients = set()
        self.latest = {}
        self.devices = {}
        self.subscriptions = []
        self.closed = Event()
        self.sequence = 0
        for device_id, binding in bindings.items():
            identifier(device_id)
            proxy = tango.DeviceProxy(binding["address"])
            proxy.set_timeout_millis(2500)
            self.devices[device_id] = {**binding, "proxy": proxy}
            try:
                subscription = proxy.subscribe_event(
                    "Snapshot", tango.EventType.CHANGE_EVENT,
                    lambda event, key=device_id: self.receive(key, event),
                    stateless=True,
                )
                self.subscriptions.append((proxy, subscription))
            except Exception:
                self.close()
                raise
        self.monitor = Thread(target=self.reconcile, daemon=True)
        self.monitor.start()

    def publish(self, device_id, snapshot=None, error=None, origin="tango_event"):
        with self.lock:
            self.sequence += 1
            value = {"type": "snapshot" if error is None else "device_error",
                     "sequence": self.sequence, "device_id": device_id,
                     "origin": origin, "connected": error is None}
            if error is None:
                value["snapshot"] = snapshot
            else:
                value["error"] = str(error)
            self.latest[device_id] = value
            for queue in list(self.clients):
                try:
                    queue.put_nowait(value)
                except Full:
                    # A slow subscriber must reconnect and reconcile current state.
                    self.clients.discard(queue)

    def receive(self, device_id, event):
        try:
            if event.err:
                # A CHANGE_EVENT channel can report a transient transport error
                # while the device remains readable. Reconcile immediately so a
                # single event-admin fault does not revoke a live device.
                try:
                    self.publish(device_id, snapshot=self.snapshot(device_id), origin="event-reconcile")
                except Exception:
                    self.publish(device_id, error=event.errors)
            else:
                self.publish(device_id, snapshot=json.loads(event.attr_value.value))
        except Exception as exc:
            self.publish(device_id, error=exc)

    def snapshot(self, device_id):
        return json.loads(self.devices[device_id]["proxy"].read_attribute("Snapshot").value)

    def call(self, device_id, command, argument):
        if isinstance(argument, dict):
            argument = json.dumps(argument, ensure_ascii=True, allow_nan=False)
        return json.loads(self.devices[device_id]["proxy"].command_inout(command, argument))

    def catalog(self):
        with self.lock:
            return [{"id": key, "component": value["component"], "address": value["address"],
                     **self.latest.get(key, {"connected": False})}
                    for key, value in self.devices.items()]

    def reconcile(self):
        while not self.closed.is_set():
            for key in list(self.devices):
                try:
                    snapshot = self.snapshot(key)
                    with self.lock:
                        previous = self.latest.get(key, {})
                    if not previous.get("connected") or previous.get("snapshot") != snapshot:
                        self.publish(key, snapshot=snapshot, origin="reconcile")
                except Exception as exc:
                    self.publish(key, error=exc, origin="reconcile")
            self.closed.wait(5)

    def close(self):
        self.closed.set()
        for proxy, subscription in self.subscriptions:
            try:
                proxy.unsubscribe_event(subscription)
            except Exception:
                pass
        monitor = getattr(self, "monitor", None)
        if monitor is not None:
            monitor.join(timeout=10)


def transport_error(exc):
    """An ambiguous transport failure must retain the command ID for retry."""
    reasons = {getattr(error, "reason", "") for item in exc.args
               for error in (item if isinstance(item, tuple) else (item,))}
    return bool(reasons & {"API_CantConnectToDevice", "API_CorbaException",
                           "API_DeviceTimedOut", "API_CommunicationFailed",
                           "API_CantConnectToDatabase", "API_DeviceNotExported"})


def create_server(bridge, port, workbench=None):
    initial_bridge = bridge

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def respond(self, code, value):
            data = json.dumps(value, ensure_ascii=False, allow_nan=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def stream(self):
            bridge = workbench.bridge if workbench else initial_bridge
            queue = Queue(maxsize=1024)
            # Register and capture current state under the same lock: no event gap.
            with bridge.lock:
                bridge.clients.add(queue)
                initial = list(bridge.latest.values())
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "close")
            self.end_headers()
            self.connection.settimeout(10)
            try:
                pending = initial + [{"type": "ready"}]
                while not bridge.closed.is_set():
                    with bridge.lock:
                        if queue not in bridge.clients:
                            break
                    for value in pending:
                        self.wfile.write(json.dumps(value, ensure_ascii=False).encode() + b"\n")
                    self.wfile.flush()
                    try:
                        pending = [queue.get(timeout=3)]
                    except Empty:
                        pending = [{"type": "heartbeat"}]
            except (OSError, TimeoutError):
                pass
            finally:
                with bridge.lock:
                    bridge.clients.discard(queue)
                self.close_connection = True

        def do_GET(self):
            bridge = workbench.bridge if workbench else initial_bridge
            parts = urlsplit(self.path).path.strip("/").split("/")
            try:
                if parts == ["events"]:
                    self.stream()
                    return
                if workbench and parts == ["lab", "environment"]:
                    value = workbench.current
                elif workbench and len(parts) == 3 and parts[:2] == ["lab", "trials"]:
                    value = workbench.trials.view(parts[2])
                elif len(parts) == 3 and parts[0] == "devices" and parts[2] == "history":
                    value = bridge.call(parts[1], "History", 200)
                elif parts == ["devices"]:
                    value = bridge.catalog()
                elif len(parts) == 2 and parts[0] == "devices":
                    value = bridge.snapshot(parts[1])
                elif len(parts) == 4 and parts[0] == "devices" and parts[2] == "commands":
                    value = bridge.call(parts[1], "GetResult", parts[3])
                else:
                    raise KeyError("unknown endpoint")
                self.respond(200, value)
            except KeyError as exc:
                self.respond(404, {"error": str(exc)})
            except Exception as exc:
                self.respond(502, {"error": str(exc)})

        def do_POST(self):
            bridge = workbench.bridge if workbench else initial_bridge
            try:
                parts = urlsplit(self.path).path.strip("/").split("/")
                length = int(self.headers.get("Content-Length", 0))
                if not 0 < length <= 4_000_000:
                    raise ValueError("invalid body length")
                payload = json.loads(self.rfile.read(length))
                if not isinstance(payload, dict):
                    raise ValueError("body must be an object")
                if workbench and parts[:1] == ["lab"]:
                    if parts == ["lab", "validate"]:
                        value = workbench.trials.validate(payload.get("xml"))
                    elif parts == ["lab", "trials"]:
                        value = workbench.trials.create(payload["xml"])
                    elif len(parts) == 4 and parts[:2] == ["lab", "trials"]:
                        value = workbench.trials.operate(parts[2], parts[3], payload)
                    elif parts == ["lab", "environment"]:
                        value = workbench.configure(payload)
                    else:
                        raise KeyError("unknown workbench endpoint")
                    self.respond(200, value)
                    return
                if len(parts) != 3 or parts[0] != "devices" or parts[1] not in bridge.devices:
                    raise KeyError("unknown device")
                command = {"commands": "Execute", "simulate": "Simulate", "complete": "Complete",
                           "events": "ReportEvent", "conditions": "SetConditions"}.get(parts[2])
                if command is None:
                    raise KeyError("unknown command")
                self.respond(200, bridge.call(parts[1], command, payload))
            except (ValueError, TypeError) as exc:
                self.respond(400, {"error": str(exc)})
            except KeyError as exc:
                self.respond(404, {"error": str(exc)})
            except Exception as exc:
                # Tango command rejection: do not manufacture a success result.
                self.respond(503 if transport_error(exc) else 409, {"error": str(exc)})

    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.daemon_threads = True
    return server


def main():
    parser = argparse.ArgumentParser(description="Flowable Tango command/event bridge")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--demo", action="store_true", help="start four component types plus explicit demo conditions")
    mode.add_argument("--devices", type=Path, help="JSON logical ID -> {component, address}")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--data", type=Path, default=DATA_DIRECTORY)
    for component in ("seed", "shg", "preamp", "multipass"):
        parser.add_argument(f"--{component}-count", type=int, default=1,
                            help=f"演示模式下的{component}实例数")
    parser.add_argument("--auto-complete", action="store_true",
                        help="演示模式下让设备延迟自动完成 Execute（真实 --devices 模式不启用）")
    parser.add_argument("--reset-data", action="store_true",
                        help="演示模式下删除当前实例的 SQLite 状态并从未就绪状态启动")
    parser.add_argument("--completion-delay-ms", type=int, default=500,
                        help="自动完成延迟（毫秒）")
    args = parser.parse_args()
    if args.completion_delay_ms < 0:
        parser.error("--completion-delay-ms 不能为负数")
    counts = None
    if args.demo:
        try:
            counts = validate_counts({component: getattr(args, f"{component}_count")
                                      for component in ("seed", "shg", "preamp", "multipass")})
        except (TypeError, ValueError) as ex:
            parser.error(str(ex))
    with ExitStack() as stack:
        if args.demo:
            from tango.test_context import MultiDeviceTestContext
            from .preparation_context import PreparationContextDevice

            specs = component_specs(counts)
            args.data.mkdir(parents=True, exist_ok=True)
            if args.reset_data:
                reset_component_data(specs, args.data)
            definition = component_definition(specs, args.data,
                                              auto_complete=args.auto_complete,
                                              completion_delay_ms=args.completion_delay_ms)
            definition.append({"class": PreparationContextDevice, "devices": [{
                "name": "gxlf/context/01", "properties": {
                    "auto_complete": args.auto_complete,
                    "auto_complete_delay_ms": args.completion_delay_ms,
                }}]})
            context = MultiDeviceTestContext(definition, process=True, host="127.0.0.1", timeout=20)
            context.start()
            bindings = component_bindings(context, specs)
            bindings["context_01"] = {"component": "context", "address": context.get_device_access("gxlf/context/01")}
        else:
            bindings = json.loads(args.devices.read_text())
        bridge = TangoBridge(bindings)
        stack.callback(bridge.close)
        workbench = None
        if args.demo:
            from .workbench import Workbench
            workbench = Workbench(bridge, context=context)
            stack.callback(workbench.close)
        server = create_server(bridge, args.port, workbench)
        stack.callback(server.server_close)
        print(json.dumps({"url": f"http://127.0.0.1:{server.server_port}", "devices": bindings}, ensure_ascii=False), flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
