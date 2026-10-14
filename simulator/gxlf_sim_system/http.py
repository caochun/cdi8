"""Loopback HTTP facade shared by direct and Tango-backed seed instances."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit
import json

from .component_model import ComponentModel
from .scxml_machine import TransitionError


def create_server(devices, model: ComponentModel, port=8766):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def respond(self, code, value):
            data = json.dumps(value, ensure_ascii=False, allow_nan=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def route(self, payload=None):
            parsed = urlsplit(self.path)
            parts = parsed.path.strip("/").split("/")
            if self.command == "GET" and parts == ["devices"]:
                return {"model": model.model_id, "devices": sorted(devices)}
            if len(parts) < 2 or parts[0] != "devices" or parts[1] not in devices:
                raise LookupError("未知种子源实例")
            device = devices[parts[1]]
            if self.command == "GET":
                if len(parts) == 2:
                    return device.snapshot()
                if len(parts) == 4 and parts[2] == "commands":
                    return device.result(parts[3])
                if len(parts) == 3 and parts[2] == "history":
                    limit = int(parse_qs(parsed.query).get("limit", ["100"])[0])
                    return device.history(limit)
            elif len(parts) == 3:
                method = {"commands": "execute", "complete": "complete", "simulate": "simulate", "events": "report_event"}.get(parts[2])
                if method:
                    return getattr(device, method)(payload)
            raise LookupError("未知接口；动作和事件以所选 SCXML 模型为准")

        def handle_request(self):
            try:
                payload = None
                if self.command == "POST":
                    length = int(self.headers.get("Content-Length", "0"))
                    if not 0 < length <= 16384:
                        raise ValueError("JSON 请求长度必须为 1–16384 字节")
                    payload = json.loads(self.rfile.read(length))
                    if not isinstance(payload, dict):
                        raise ValueError("请求必须为 JSON 对象")
                self.respond(200, self.route(payload))
            except TransitionError as ex:
                self.respond(409, {"error": str(ex)})
            except LookupError as ex:
                self.respond(404, {"error": str(ex)})
            except (ValueError, TypeError) as ex:
                self.respond(400, {"error": str(ex)})
            except Exception as ex:
                self.respond(503, {"error": str(ex)})

        do_GET = handle_request
        do_POST = handle_request

    return ThreadingHTTPServer(("127.0.0.1", port), Handler)
