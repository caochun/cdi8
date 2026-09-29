"""Real PyTango Device Server class. Install the optional tango dependency."""
import json
from tango.server import Device, attribute, command, device_property
from .runtime import DeviceRuntime


class SubsystemDevice(Device):
    model_path = device_property(dtype=str)
    database_path = device_property(dtype=str)

    def init_device(self):
        super().init_device()
        self.runtime = DeviceRuntime(self.model_path, self.database_path)
        self.set_change_event('Snapshot', True, False)

    @attribute(dtype=str)
    def Snapshot(self):
        return json.dumps(self.runtime.snapshot(), ensure_ascii=True)

    def publish(self, result):
        self.push_change_event('Snapshot', json.dumps(self.runtime.snapshot(), ensure_ascii=True))
        return json.dumps(result, ensure_ascii=True)

    @command(dtype_in=str, dtype_out=str)
    def Execute(self, payload):
        return self.publish(self.runtime.execute(json.loads(payload)))

    @command(dtype_in=str, dtype_out=str)
    def GetResult(self, command_id):
        return json.dumps(self.runtime.result(command_id), ensure_ascii=True)

    @command(dtype_in=str, dtype_out=str)
    def Cancel(self, command_id):
        return self.publish(self.runtime.cancel(command_id))

    @command(dtype_in=str, dtype_out=str)
    def Simulate(self, payload):
        return self.publish(self.runtime.simulate(json.loads(payload)))


if __name__ == '__main__':
    SubsystemDevice.run_server()
