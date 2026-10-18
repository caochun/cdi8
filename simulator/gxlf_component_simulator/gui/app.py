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
    parser.add_argument("--auto-complete", action="store_true",
                        help="演示模式下让设备延迟自动完成 Execute")
    parser.add_argument("--completion-delay-ms", type=int, default=500,
                        help="自动完成延迟（毫秒）")
    for component in ("seed", "shg", "preamp", "multipass"):
        parser.add_argument(f"--{component}-count", type=int, default=1,
                            help=f"演示模式下的{component}实例数")
    args = parser.parse_args()
    if args.completion_delay_ms < 0:
        parser.error("--completion-delay-ms 不能为负数")
    os.environ.setdefault("QT_API", "pyqt6")
    try:
        from taurus.qt.qtgui.application import TaurusApplication
        from .window import ComponentWindow
        from .. import ComponentModel, DATA_DIRECTORY, MULTIPASS_MODEL, PREAMP_MODEL, SEED_MODEL, SHG_MODEL
        from ..tango.demo import component_bindings, component_definition, component_specs, validate_counts
    except ImportError as ex:
        parser.exit(2, "桌面依赖不可用（Taurus / Qt WebEngine / PyTango），请安装 pip install -e './simulator[gui]'\n" + str(ex) + "\n")
    with ExitStack() as stack:
        if args.model:
            profile = ComponentModel(args.model.expanduser().resolve())
            component_name = "reference"
            profiles = {component_name: profile}
            profile_labels = {component_name: profile.display_name}
        else:
            models = {"seed": SEED_MODEL, "shg": SHG_MODEL, "preamp": PREAMP_MODEL,
                      "multipass": MULTIPASS_MODEL}
            try:
                counts = validate_counts({component: getattr(args, f"{component}_count")
                                          for component in models})
            except (TypeError, ValueError) as ex:
                parser.error(str(ex))
            specs = component_specs(counts)
            profiles = {key: models[component] for key, component, _ in specs}
            profile_labels = {key: f"{models[component].display_name} · {key}"
                             for key, component, _ in specs}
            component_name = ("seed" if args.component == "reference" else args.component) + "_01"
            profile = profiles[component_name]
        database = args.database
        address = args.device
        addresses = {}
        if not address:
            from tango.test_context import MultiDeviceTestContext
            if args.model:
                from ..tango.device import ComponentDevice
                database_path = database or DATA_DIRECTORY / f"reference-v{profile.model_version}-01.sqlite3"
                database_path.parent.mkdir(parents=True, exist_ok=True)
                context = stack.enter_context(MultiDeviceTestContext([{"class": ComponentDevice, "devices": [{
                    "name": "gxlf/reference/reference_01",
                    "properties": {"component_model": profile.model_id, "database_path": str(database_path.resolve()),
                                   "auto_complete": args.auto_complete,
                                   "auto_complete_delay_ms": args.completion_delay_ms},
                }]}], process=True, host="127.0.0.1"))
                addresses = {"reference": context.get_device_access("gxlf/reference/reference_01")}
            else:
                database_overrides = {component_name: database} if database else {}
                for path in database_overrides.values():
                    path.parent.mkdir(parents=True, exist_ok=True)
                for key, candidate in profiles.items():
                    (DATA_DIRECTORY / f"{key}-v{candidate.model_version}.sqlite3").parent.mkdir(parents=True, exist_ok=True)
                context = stack.enter_context(MultiDeviceTestContext(
                    component_definition(specs, DATA_DIRECTORY, database_overrides,
                                        auto_complete=args.auto_complete,
                                        completion_delay_ms=args.completion_delay_ms),
                    process=True, host="127.0.0.1"))
                addresses = {key: binding["address"] for key, binding in component_bindings(context, specs).items()}
            address = addresses[component_name]
            print("本地 Tango Device Server 已启动，设备数：" + str(len(addresses)), flush=True)
        else:
            addresses[component_name] = address
        application = TaurusApplication(args=[], app_name="ComponentPanel", app_version="0.3.0")
        application.setQuitOnLastWindowClosed(True)
        window = ComponentWindow(profile, address, simulation_controls=not args.device or args.allow_simulation,
                                 profiles=profiles, profile_labels=profile_labels,
                                 addresses=addresses, component_key=component_name)
        window.show()
        try:
            application.exec()
        finally:
            window.close()


if __name__ == "__main__":
    main()
