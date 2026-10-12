"""Native Taurus application launcher with an optional real no-DB Tango simulator."""
import argparse
from contextlib import ExitStack
import os
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="Taurus / Qt 种子源仿真操作台")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--demo", action="store_true", help="启动本地 Tango 仿真实例（默认）")
    mode.add_argument("--device", help="已有 Tango Device 地址，如 tango://host:10000/gxlf/seed/01")
    parser.add_argument("--database", type=Path, default=Path("seed-panel-data/seed_01.sqlite3"), help="本地演示实例的持久化文件")
    parser.add_argument("--allow-simulation", action="store_true", help="为外部设备显式启用仿真反馈和确认事件注入")
    args = parser.parse_args()
    os.environ.setdefault("QT_API", "pyqt6")
    try:
        from taurus.qt.qtgui.application import TaurusApplication
        from .window import SeedWindow
    except ImportError as ex:
        parser.exit(2, "桌面依赖不可用，请安装 pip install -e './simulator[gui]'\n" + str(ex) + "\n")
    with ExitStack() as stack:
        address = args.device
        if not address:
            from tango.test_context import MultiDeviceTestContext
            from ..tango.device import SeedSourceDevice
            args.database.parent.mkdir(parents=True, exist_ok=True)
            config = [{"class": SeedSourceDevice, "devices": [{"name": "gxlf/seed/seed_01", "properties": {
                "database_path": str(args.database.resolve())}}]}]
            context = stack.enter_context(MultiDeviceTestContext(config, process=True, host="127.0.0.1"))
            address = context.get_device_access("gxlf/seed/seed_01")
            print("本地 Tango 仿真设备：" + address, flush=True)
        application = TaurusApplication(args=[], app_name="SeedPanel", app_version="0.2.0")
        application.setQuitOnLastWindowClosed(True)
        window = SeedWindow(address, simulation_controls=not args.device or args.allow_simulation)
        window.show()
        try:
            application.exec()
        finally:
            window.close()


if __name__ == "__main__":
    main()
