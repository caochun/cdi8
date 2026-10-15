"""Explicit demo stand-in for synchronization, safety and environment evidence.

All conditions start false. The operator/test controls them through Tango.
This is not a physical safety service or a component SCXML model.
"""
import copy
import json
from threading import RLock

from tango.server import Device, attribute, command

from ..component_runtime import identifier


class PreparationContextDevice(Device):
    def init_device(self):
        super().init_device()
        self.lock = RLock()
        self.conditions = dict(safety_permit=False, preamp_repeat_ready=False,
                               collimation_environment_ready=False)
        self.results = {}
        self.active = None
        self.last = None
        self.set_change_event("Snapshot", True, False)

    def snapshot(self):
        return {"model": "preparation-context-demo-v1", "component": "演示同步及环境条件",
                "conditions": dict(self.conditions), "active_command_id": self.active,
                "active_action": "preamp_repeat" if self.active else None,
                "last_result": copy.deepcopy(self.results.get(self.last)) if self.last else None}

    def publish(self, value):
        self.push_change_event("Snapshot", json.dumps(self.snapshot(), ensure_ascii=True))
        return json.dumps(value, ensure_ascii=True)

    @attribute(dtype=str)
    def Snapshot(self):
        with self.lock:
            return json.dumps(self.snapshot(), ensure_ascii=True)

    @command(dtype_in=str, dtype_out=str)
    def SetConditions(self, payload):
        values = json.loads(payload)
        with self.lock:
            if not isinstance(values, dict) or not values or any(
                    key not in self.conditions or not isinstance(value, bool)
                    for key, value in values.items()):
                raise ValueError("expected named boolean conditions")
            self.conditions.update(values)
            return self.publish(self.snapshot())

    @command(dtype_in=str, dtype_out=str)
    def Execute(self, payload):
        request = json.loads(payload)
        key = identifier(request.get("command_id"))
        with self.lock:
            if key in self.results:
                if request != self.results[key]["request"]:
                    raise ValueError("command ID reused")
                return json.dumps(self.results[key], ensure_ascii=True)
            if request.get("action") != "preamp_repeat" or self.active:
                raise ValueError("unsupported action or command already active")
            self.conditions["preamp_repeat_ready"] = False
            self.active = key
            self.results[key] = {"command_id": key, "action": "preamp_repeat", "status": "accepted", "request": request}
            return self.publish(self.results[key])

    def finish(self, payload):
        request = json.loads(payload)
        key = identifier(request.get("command_id"))
        outcome = request.get("outcome")
        if outcome not in ("success", "failure"):
            raise ValueError("outcome must be success or failure")
        with self.lock:
            result = self.results[key]
            if result["status"] != "accepted":
                if result["status"] != ("succeeded" if outcome == "success" else "failed"):
                    raise ValueError("conflicting completion")
                return json.dumps(result, ensure_ascii=True)
            self.conditions["preamp_repeat_ready"] = outcome == "success"
            self.active = None
            self.last = key
            result["status"] = "succeeded" if outcome == "success" else "failed"
            result["snapshot"] = {"conditions": dict(self.conditions), "active_command_id": None}
            return self.publish(result)

    @command(dtype_in=str, dtype_out=str)
    def Simulate(self, payload):
        return self.finish(payload)

    @command(dtype_in=str, dtype_out=str)
    def Complete(self, payload):
        return self.finish(payload)

    @command(dtype_in=str, dtype_out=str)
    def GetResult(self, key):
        with self.lock:
            return json.dumps(self.results[key], ensure_ascii=True)
