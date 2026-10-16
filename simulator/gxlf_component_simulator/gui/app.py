"""Native Taurus application launcher with an optional real no-DB Tango simulator."""
import argparse
from contextlib import ExitStack
import os
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="Taurus / Qt SCXML 分系统仿真操作台")
    parser.add_argument("--component", choices=("seed", "shg", "preamp", "multipass", "reference"), default="seed")
    parser.add_argument("--model", type=Path, help="加载任意本地 SCXML 模型（覆盖 --component）")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--demo", action="store_true", help="启动本地 Tango 仿真实例（默认）")
    mode.add_argument("--device", help="已有 Tango Device 地址")
    parser.add_argument("--database", type=Path, help="本地演示实例的持久化文件")
    parser.add_argument("--allow-simulation", action="store_true", help="为外部设备显式启用仿真反馈和确认事件注入")
    args = parser.parse_args()
    os.environ.setdefault("QT_API", "pyqt6")
    try:
        from taurus.qt.qtgui.application import TaurusApplication
        from .window import ComponentWindow
        from .. import ComponentModel, DATA_DIRECTORY, MULTIPASS_MODEL, PREAMP_MODEL, SEED_MODEL, SHG_MODEL
    except ImportError as ex:
        parser.exit(2, "桌面依赖不可用（Taurus / Qt WebEngine / PyTango），请安装 pip install -e './simulator[gui]'\n" + str(ex) + "\n")
    with ExitStack() as stack:
        if args.model:
            profile = ComponentModel(args.model.expanduser().resolve())
            component_name = "reference"
            profiles = {component_name: profile}
        else:
            profiles = {"seed": SEED_MODEL, "shg": SHG_MODEL, "preamp": PREAMP_MODEL,
                        "multipass": MULTIPASS_MODEL}
            component_name = "seed" if args.component == "reference" else args.component
            profile = profiles[component_name]
        database = args.database
        address = args.device
        addresses = {}
        if not address:
            from tango.test_context import MultiDeviceTestContext
            from ..tango.device import ComponentDevice
            config = [{"class": ComponentDevice, "devices": []}]
            for key, candidate in profiles.items():
                candidate_database = database if key == component_name and database else DATA_DIRECTORY / (
                    f"{key}-v{candidate.model_version}-01.sqlite3")
                candidate_database.parent.mkdir(parents=True, exist_ok=True)
                device_name = f"gxlf/{key}/{key}_01"
                config[0]["devices"].append({"name": device_name, "properties": {
                    "component_model": candidate.model_id, "database_path": str(candidate_database.resolve())}})
            context = stack.enter_context(MultiDeviceTestContext(config, process=True, host="127.0.0.1"))
            for key in profiles:
                device_name = f"gxlf/{key}/{key}_01"
                addresses[key] = context.get_device_access(device_name)
            address = addresses[component_name]
            print("本地 Tango 仿真设备：" + address, flush=True)
        else:
            addresses[component_name] = address
        application = TaurusApplication(args=[], app_name="ComponentPanel", app_version="0.3.0")
        application.setQuitOnLastWindowClosed(True)
        window = ComponentWindow(profile, address, simulation_controls=not args.device or args.allow_simulation,
                                 profiles=profiles, addresses=addresses, component_key=component_name)
        window.show()
        try:
            application.exec()
        finally:
            window.close()


if __name__ == "__main__":
    main()
