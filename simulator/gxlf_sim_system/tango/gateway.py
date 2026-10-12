"""Seed-only gateway, with explicitly selected local or actual Tango transport."""
import argparse
from contextlib import ExitStack
import json
from pathlib import Path

from ..http import create_server
from ..runtime import SeedRuntime, identifier


class TangoAdapter:
    def __init__(self, device):
        self.device = device

    def snapshot(self):
        return json.loads(self.device.read_attribute("Snapshot").value)

    def call(self, name, payload):
        argument = json.dumps(payload) if isinstance(payload, dict) else payload
        return json.loads(self.device.command_inout(name, argument))

    def execute(self, payload):
        return self.call("Execute", payload)

    def complete(self, payload):
        return self.call("Complete", payload)

    def simulate(self, payload):
        return self.call("Simulate", payload)

    def report_event(self, payload):
        return self.call("ReportEvent", payload)

    def result(self, command_id):
        return self.call("GetResult", command_id)

    def history(self, limit=100):
        return self.call("History", limit)


def main():
    parser = argparse.ArgumentParser(description="种子源 HTTP 网关；不包含二倍频或集中流程")
    parser.add_argument("--mode", choices=("local", "tango"), default="local")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--devices", nargs="+", default=["seed_01"], help="独立种子源实例的逻辑编号")
    parser.add_argument("--data", type=Path, default=Path("seed-simulator-data"))
    args = parser.parse_args()
    for name in args.devices:
        identifier(name)
    if len(set(args.devices)) != len(args.devices):
        parser.error("实例编号不能重复")
    with ExitStack() as stack:
        if args.mode == "local":
            devices = {}
            for name in args.devices:
                runtime = SeedRuntime(args.data / (name + ".sqlite3"))
                stack.callback(runtime.close)
                devices[name] = runtime
        else:
            try:
                from tango.test_context import MultiDeviceTestContext
                from .device import SeedSourceDevice
            except ImportError:
                parser.error("Tango 模式需要安装可选依赖：pip install -e './simulator[tango]'")
            args.data.mkdir(parents=True, exist_ok=True)
            definition = [{"class": SeedSourceDevice, "devices": [
                {"name": "gxlf/seed/" + name, "properties": {"database_path": str((args.data / (name + ".sqlite3")).resolve())}}
                for name in args.devices]}]
            context = stack.enter_context(MultiDeviceTestContext(definition, process=True, host="127.0.0.1"))
            devices = {name: TangoAdapter(context.get_device("gxlf/seed/" + name)) for name in args.devices}
        server = create_server(devices, args.port)
        stack.callback(server.server_close)
        print(f"Seed simulator: http://127.0.0.1:{server.server_port} mode={args.mode} devices={','.join(devices)}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
