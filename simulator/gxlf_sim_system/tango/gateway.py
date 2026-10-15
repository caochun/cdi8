"""HTTP gateway for local or Tango-backed SCXML component instances."""

import argparse
from contextlib import ExitStack
import json
from pathlib import Path

from ..component_runtime import ComponentRuntime, identifier
from ..http import create_server
from ..component_model import MULTIPASS_MODEL, PREAMP_MODEL, SEED_MODEL, SHG_MODEL


class TangoAdapter:
    def __init__(self, device):
        self.device = device

    def snapshot(self):
        return json.loads(self.device.read_attribute("Snapshot").value)

    def call(self, name, payload):
        argument = json.dumps(payload) if isinstance(payload, dict) else payload
        return json.loads(self.device.command_inout(name, argument))

    def execute(self, payload): return self.call("Execute", payload)
    def complete(self, payload): return self.call("Complete", payload)
    def simulate(self, payload): return self.call("Simulate", payload)
    def report_event(self, payload): return self.call("ReportEvent", payload)
    def result(self, command_id): return self.call("GetResult", command_id)
    def history(self, limit=100): return self.call("History", limit)


def main():
    parser = argparse.ArgumentParser(description="SCXML 组件 HTTP 网关")
    parser.add_argument("--component", choices=("seed", "shg", "preamp", "multipass"), default="seed")
    parser.add_argument("--mode", choices=("local", "tango"), default="local")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--devices", nargs="+", default=["01"], help="独立组件实例编号")
    parser.add_argument("--data", type=Path, default=Path("component-simulator-data"))
    args = parser.parse_args()
    model = {"seed": SEED_MODEL, "shg": SHG_MODEL, "preamp": PREAMP_MODEL, "multipass": MULTIPASS_MODEL}[args.component]
    for name in args.devices:
        identifier(name)
    if len(set(args.devices)) != len(args.devices):
        parser.error("实例编号不能重复")
    with ExitStack() as stack:
        if args.mode == "local":
            devices = {}
            for name in args.devices:
                runtime = ComponentRuntime(model, args.data / args.component / (name + "-v" + str(model.model_version) + ".sqlite3"))
                stack.callback(runtime.close)
                devices[name] = runtime
        else:
            try:
                from tango.test_context import MultiDeviceTestContext
                from .device import ComponentDevice
            except ImportError:
                parser.error("Tango 模式需要安装 simulator GUI/Tango 依赖")
            args.data.mkdir(parents=True, exist_ok=True)
            definition = [{"class": ComponentDevice, "devices": [
                {"name": f"gxlf/{args.component}/{name}", "properties": {
                    "component_model": model.model_id,
                    "database_path": str((args.data / args.component / (name + "-v" + str(model.model_version) + ".sqlite3")).resolve())}}
                for name in args.devices]}]
            context = stack.enter_context(MultiDeviceTestContext(definition, process=True, host="127.0.0.1"))
            devices = {name: TangoAdapter(context.get_device(f"gxlf/{args.component}/{name}")) for name in args.devices}
        server = create_server(devices, model, args.port)
        stack.callback(server.server_close)
        print(f"Component simulator ({model.model_id}): http://127.0.0.1:{server.server_port} devices={','.join(devices)}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
