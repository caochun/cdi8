"""CLI for the SCXML component simulator."""
import argparse
import json
from pathlib import Path

from . import MULTIPASS_MODEL, PREAMP_MODEL, SEED_MODEL, SHG_MODEL
from .component_runtime import ComponentRuntime
from .scxml_machine import TransitionError


def demo(runtime, model):
    if runtime.snapshot()["state_id"] != model.machine.initial_leaf:
        raise TransitionError("演示需要初始 SCXML 状态，请使用新的数据库路径")
    trace = [{"event": "初始", "snapshot": runtime.snapshot()}]
    if model is SEED_MODEL:
        actions = [
            ("power_on_check", "success"), ("function_check", "success"),
            ("parameter_dispatch", "success"), ("seed_source_emit", "success"),
            ("laser_parameter_collect", "failure"), ("laser_parameter_collect", "success"),
        ]
    elif model is PREAMP_MODEL:
        actions = [
            ("power_on_check", "success"), ("function_check", "success"), ("parameter_dispatch", "success"),
            ("preamp_wakeup", "success"), ("coarse_loop", "success"),
            ("fine_loop", "success"), ("energy_meter_clear", "success"),
            ("energy_collect", "success"), ("standby", "success"),
        ]
    elif model is MULTIPASS_MODEL:
        actions = [
            ("power_on_check", "success"), ("function_check", "success"),
            ("parameter_dispatch", "success"), ("optical_collimation", "success"),
            ("enter_shot_state", "success"), ("standby_reset", "success"),
        ]
    else:
        actions = [
            ("power_on_check", "success"), ("function_check", "success"),
            ("parameter_dispatch", "success"), ("frequency_doubled_emit", "success"),
            ("laser_parameter_collect", "success"), ("standby_reset", "success"),
            ("shutdown", "success"),
        ]
    for index, (action, outcome) in enumerate(actions, 1):
        command_id = f"demo-{index}"
        runtime.execute({"command_id": command_id, "action": action})
        result = runtime.simulate({"command_id": command_id, "outcome": outcome})
        trace.append({"event": action + ":" + outcome, "result": result})
    return trace


def main():
    parser = argparse.ArgumentParser(description="按 SCXML 状态模型运行分系统仿真")
    parser.add_argument("--component", choices=("seed", "shg", "preamp", "multipass"), default="seed")
    parser.add_argument("--db", type=Path, help="SQLite 文件；demo 默认使用内存数据库")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("snapshot")
    commands.add_parser("demo")
    request = commands.add_parser("request")
    # Action choices and event choices are checked after the selected SCXML model is loaded.
    request.add_argument("action")
    request.add_argument("command_id")
    request.add_argument("--parameters", default="{}", help="JSON 对象，随命令保存")
    for name in ("complete", "simulate"):
        result = commands.add_parser(name)
        result.add_argument("command_id")
        result.add_argument("outcome", choices=("success", "failure"))
        result.add_argument("--observed", help="实际业务状态确认；simulate 可自动合成所需确认")
    event = commands.add_parser("event")
    event.add_argument("event")
    event.add_argument("event_id")
    event.add_argument("--observed", required=True, choices=("未就绪", "就绪", "出光", "准直", "设备异常"))
    query = commands.add_parser("result")
    query.add_argument("command_id")
    history = commands.add_parser("history")
    history.add_argument("--limit", type=int, default=100)
    args = parser.parse_args()
    model = {"seed": SEED_MODEL, "shg": SHG_MODEL, "preamp": PREAMP_MODEL, "multipass": MULTIPASS_MODEL}[args.component]
    runtime = None
    try:
        runtime = ComponentRuntime(model, args.db or (":memory:" if args.command == "demo" else f"{args.component}-simulator-v{model.model_version}.sqlite3"))
        if args.command == "snapshot":
            value = runtime.snapshot()
        elif args.command == "demo":
            value = demo(runtime, model)
        elif args.command == "request":
            if args.action not in model.actions:
                raise TransitionError("未知动作：" + args.action)
            value = runtime.execute({"command_id": args.command_id, "action": args.action, "parameters": json.loads(args.parameters)})
        elif args.command in ("complete", "simulate"):
            payload = {"command_id": args.command_id, "outcome": args.outcome}
            if args.observed is not None:
                payload["observed_outer_state"] = args.observed
            value = getattr(runtime, args.command)(payload)
        elif args.command == "event":
            if args.event not in model.confirmations:
                raise TransitionError("未知确认事件：" + args.event)
            value = runtime.report_event({"event_id": args.event_id, "event": args.event, "observed_outer_state": args.observed})
        elif args.command == "result":
            value = runtime.result(args.command_id)
        else:
            value = runtime.history(args.limit)
        print(json.dumps(value, ensure_ascii=False, indent=2))
    except (ValueError, OSError) as ex:
        parser.exit(2, f"错误：{ex}\n")
    finally:
        if runtime is not None:
            runtime.close()


if __name__ == "__main__":
    main()
