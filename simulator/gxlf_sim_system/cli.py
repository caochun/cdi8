"""CLI for individual state-machine events, plus a reproducible in-memory demo."""
import argparse
import json
from pathlib import Path

from .machine import ACTIONS, CONFIRMATIONS, MACHINE, TransitionError
from .runtime import SeedRuntime


def demo(runtime):
    if runtime.snapshot()["state_id"] != MACHINE.initial_leaf:
        raise TransitionError("演示需要初始 SCXML 状态，请使用新的数据库路径")
    trace = [{"event": "初始", "snapshot": runtime.snapshot()}]
    for index, (action, outcome) in enumerate([
        ("power_on_self_test", "success"), ("function_check", "success"),
        ("parameter_dispatch", "success"), ("seed_source_emit", "success"),
        ("laser_parameter_collect", "failure"), ("laser_parameter_collect", "success"),
    ], 1):
        command_id = f"demo-{index}"
        runtime.execute({"command_id": command_id, "action": action})
        result = runtime.simulate({"command_id": command_id, "outcome": outcome})
        trace.append({"event": action + ":" + outcome, "result": result})
    return trace


def main():
    parser = argparse.ArgumentParser(description="按 SCXML 状态模型运行种子源分系统仿真")
    parser.add_argument("--db", type=Path, help="SQLite 文件；默认当前目录 seed-simulator.sqlite3，demo 默认内存数据库")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("snapshot")
    commands.add_parser("demo")
    request = commands.add_parser("request")
    request.add_argument("action", choices=ACTIONS)
    request.add_argument("command_id")
    request.add_argument("--parameters", default="{}", help="JSON 对象，随命令保存")
    for name in ("complete", "simulate"):
        result = commands.add_parser(name)
        result.add_argument("command_id")
        result.add_argument("outcome", choices=("success", "failure"))
        result.add_argument("--observed", help="实际业务状态确认；simulate 可自动合成所需确认")
    event = commands.add_parser("event")
    event.add_argument("event", choices=CONFIRMATIONS)
    event.add_argument("event_id")
    event.add_argument("--observed", required=True, choices=("未就绪", "就绪", "出光", "设备异常"))
    query = commands.add_parser("result")
    query.add_argument("command_id")
    history = commands.add_parser("history")
    history.add_argument("--limit", type=int, default=100)
    args = parser.parse_args()
    runtime = None
    try:
        runtime = SeedRuntime(args.db or (":memory:" if args.command == "demo" else "seed-simulator.sqlite3"))
        if args.command == "snapshot":
            value = runtime.snapshot()
        elif args.command == "demo":
            value = demo(runtime)
        elif args.command == "request":
            value = runtime.execute({"command_id": args.command_id, "action": args.action, "parameters": json.loads(args.parameters)})
        elif args.command in ("complete", "simulate"):
            payload = {"command_id": args.command_id, "outcome": args.outcome}
            if args.observed is not None:
                payload["observed_business_state"] = args.observed
            value = getattr(runtime, args.command)(payload)
        elif args.command == "event":
            value = runtime.report_event({"event_id": args.event_id, "event": args.event, "observed_business_state": args.observed})
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
