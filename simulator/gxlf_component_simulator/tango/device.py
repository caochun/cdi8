"""Generic PyTango device backed by an SCXML component model."""

import json
from threading import RLock, Timer

from tango.server import Device, attribute, command, device_property

from ..component_runtime import ComponentRuntime
from ..component_model import model_by_id


class ComponentDevice(Device):
    component_model = device_property(dtype=str)
    database_path = device_property(dtype=str)
    # Demo-only behavior. Real devices leave this disabled and report their
    # completion through Complete or ReportEvent when hardware responds.
    auto_complete = device_property(dtype=bool, default_value=False)
    auto_complete_delay_ms = device_property(dtype=int, default_value=500)

    def init_device(self):
        super().init_device()
        old_lock = getattr(self, "_auto_lock", None)
        if old_lock is not None:
            with old_lock:
                self._deleted = True
                for timer in getattr(self, "_auto_timers", {}).values():
                    timer.cancel()
                getattr(self, "_auto_timers", {}).clear()
        self._auto_lock = RLock()
        self._auto_timers = {}
        self._deleted = False
        old = getattr(self, "runtime", None)
        if old is not None:
            old.close()
        if not self.component_model or not self.database_path:
            raise ValueError("component_model and database_path are required")
        self.model = model_by_id(self.component_model)
        self.runtime = ComponentRuntime(self.model, self.database_path)
        self.set_change_event("Snapshot", True, False)

    def delete_device(self):
        with getattr(self, "_auto_lock", RLock()):
            self._deleted = True
            for timer in getattr(self, "_auto_timers", {}).values():
                timer.cancel()
            getattr(self, "_auto_timers", {}).clear()
        runtime = getattr(self, "runtime", None)
        if runtime is not None:
            runtime.close()
            self.runtime = None

    @attribute(dtype=str)
    def Snapshot(self):
        return json.dumps(self.runtime.snapshot(), ensure_ascii=True)

    def publish(self, result):
        self.push_change_event("Snapshot", json.dumps(self.runtime.snapshot(), ensure_ascii=True))
        return json.dumps(result, ensure_ascii=True)

    def _finish_automatically(self, command_id):
        try:
            with self._auto_lock:
                if self._deleted:
                    return
            # A hardware completion, fault, or operator retry may have won the
            # race while the timer was waiting. In that case it is already done.
            if self.runtime.result(command_id)["status"] != "accepted":
                return
            result = self.runtime.simulate({"command_id": command_id, "outcome": "success"})
            self.publish(result)
        except Exception as ex:
            # Do not turn a late timer error into a false success. Keep it
            # visible in the Tango server log; GetResult remains authoritative.
            try:
                self.error_stream("automatic completion failed for %s: %s" % (command_id, ex))
            except Exception:
                pass
        finally:
            with self._auto_lock:
                self._auto_timers.pop(command_id, None)

    def _schedule_auto_completion(self, result):
        if not getattr(self, "auto_complete", False) or result.get("status") != "accepted":
            return
        command_id = result["command_id"]
        with self._auto_lock:
            if self._deleted or command_id in self._auto_timers:
                return
            delay = max(0, int(getattr(self, "auto_complete_delay_ms", 500))) / 1000
            timer = Timer(delay, self._finish_automatically, args=(command_id,))
            timer.daemon = True
            self._auto_timers[command_id] = timer
            timer.start()

    @command(dtype_in=str, dtype_out=str)
    def Execute(self, payload):
        result = self.runtime.execute(json.loads(payload))
        response = self.publish(result)
        self._schedule_auto_completion(result)
        return response

    @command(dtype_in=str, dtype_out=str)
    def Complete(self, payload):
        return self.publish(self.runtime.complete(json.loads(payload)))

    @command(dtype_in=str, dtype_out=str)
    def Simulate(self, payload):
        return self.publish(self.runtime.simulate(json.loads(payload)))

    @command(dtype_in=str, dtype_out=str)
    def ReportEvent(self, payload):
        return self.publish(self.runtime.report_event(json.loads(payload)))

    @command(dtype_in=str, dtype_out=str)
    def GetResult(self, command_id):
        return json.dumps(self.runtime.result(command_id), ensure_ascii=True)

    @command(dtype_in=int, dtype_out=str)
    def History(self, limit):
        return json.dumps(self.runtime.history(limit), ensure_ascii=True)


if __name__ == "__main__":
    ComponentDevice.run_server()
