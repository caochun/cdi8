"""Native Taurus application launcher with an optional real no-DB Tango simulator."""
import argparse
from contextlib import ExitStack
import os
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="Taurus / Qt SCXML 分系统仿真操作台")
    parser.add_argument("--component", choices=("seed", "shg", "preamp", "multipass"), default="seed")
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
        from .. import MULTIPASS_MODEL, PREAMP_MODEL, SEED_MODEL, SHG_MODEL
    except ImportError as ex:
        parser.exit(2, "桌面依赖不可用（Taurus / Qt WebEngine / PyTango），请安装 pip install -e './simulator[gui]'\n" + str(ex) + "\n")
    with ExitStack() as stack:
        profile = {"seed": SEED_MODEL, "shg": SHG_MODEL, "preamp": PREAMP_MODEL, "multipass": MULTIPASS_MODEL}[args.component]
        database = args.database or Path(f"{args.component}-panel-data/v{profile.model_version}/{args.component}_01.sqlite3")
        address = args.device
        if not address:
            from tango.test_context import MultiDeviceTestContext
            from ..tango.device import ComponentDevice
            database.parent.mkdir(parents=True, exist_ok=True)
            device_name = f"gxlf/{args.component}/{args.component}_01"
            config = [{"class": ComponentDevice, "devices": [{"name": device_name, "properties": {
                "component_model": profile.model_id, "database_path": str(database.resolve())}}]}]
            context = stack.enter_context(MultiDeviceTestContext(config, process=True, host="127.0.0.1"))
            address = context.get_device_access(device_name)
            print("本地 Tango 仿真设备：" + address, flush=True)
        application = TaurusApplication(args=[], app_name="ComponentPanel", app_version="0.3.0")
        application.setQuitOnLastWindowClosed(True)
        window = ComponentWindow(profile, address, simulation_controls=not args.device or args.allow_simulation)
        window.show()
        try:
            application.exec()
        finally:
            window.close()


if __name__ == "__main__":
    main()
