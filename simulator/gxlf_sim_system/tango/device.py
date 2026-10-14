"""Generic PyTango device backed by an SCXML component model."""

import json
from tango.server import Device, attribute, command, device_property

from ..component_runtime import ComponentRuntime
from ..component_model import model_by_id


class ComponentDevice(Device):
    component_model = device_property(dtype=str)
    database_path = device_property(dtype=str)

    def init_device(self):
        super().init_device()
        old = getattr(self, "runtime", None)
        if old is not None:
            old.close()
        if not self.component_model or not self.database_path:
            raise ValueError("component_model and database_path are required")
        self.model = model_by_id(self.component_model)
        self.runtime = ComponentRuntime(self.model, self.database_path)
        self.set_change_event("Snapshot", True, False)

    def delete_device(self):
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

    @command(dtype_in=str, dtype_out=str)
    def Execute(self, payload):
        return self.publish(self.runtime.execute(json.loads(payload)))

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
