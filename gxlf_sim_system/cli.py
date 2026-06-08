from __future__ import annotations

import argparse
from pathlib import Path
from typing import Callable

from .contracts import ContractValidator
from .engine import EngineEvent, FlowEngine
from .event_bridge import BridgeConfig, run_bridge
from .guards import FlowRuntimeContext, GuardEvaluator
from .lifecycle import LifecycleLogger
from .loader import load_models
from .service_sim import SimFaultRegistry, SimServiceRegistry, SimTimingConfig, TangoSimAdapter
from .timeline import write_timeline
from .validation import validate_model_bundle


def build_engine(
    root: Path,
    lifecycle_logger: LifecycleLogger | None = None,
    sim_timing: SimTimingConfig | None = None,
    runtime_context: FlowRuntimeContext | None = None,
    engine_event_sink: Callable[[EngineEvent], None] | None = None,
    fault_registry: SimFaultRegistry | None = None,
) -> FlowEngine:
    bundle = load_models(root)
    registry = SimServiceRegistry(bundle.state_machine)
    validator = ContractValidator(bundle.interface, bundle.state_machine)
    tango = TangoSimAdapter(
        registry,
        bundle.state_machine,
        validator,
        lifecycle_logger=lifecycle_logger,
        timing=sim_timing,
        fault_registry=fault_registry,
    )
    return FlowEngine(
        nodes=bundle.nodes,
        registry=registry,
        tango_adapter=tango,
        node_contracts_by_id=bundle.node_contracts_by_id,
        guard_evaluator=GuardEvaluator(registry, runtime_context),
        event_sink=engine_event_sink,
    )


def cmd_validate(args: argparse.Namespace) -> int:
    bundle = load_models(args.root)
    report = validate_model_bundle(bundle)
    print("GXLF model validation")
    print(f"  flow:       {bundle.flow_path}")
    print(f"  interface:  {bundle.interface_path}")
    print(f"  state:      {bundle.state_machine_path}")
    print(f"  nodes:      {len(bundle.nodes)}")
    print(f"  contracts:  {len(bundle.node_contracts_by_id)}")
    print()
    for warning in report.warnings:
        print(f"[WARN] {warning}")
    for error in report.errors:
        print(f"[ERR ] {error}")
    if report.ok:
        print("Validation completed with no blocking errors.")
        return 0
    print(f"Validation failed with {len(report.errors)} blocking error(s).")
    return 1


def cmd_run(args: argparse.Namespace) -> int:
    lifecycle_logger = LifecycleLogger(args.lifecycle_log) if args.lifecycle_log else None
    probe_engine = None
    time_scale = args.time_scale
    if args.target_duration and args.target_duration > 0 and time_scale is None:
        probe_engine = build_engine(args.root)
        estimate = probe_engine.estimated_critical_path_duration_seconds()
        time_scale = args.target_duration / estimate if estimate > 0 else 0.0
    sim_timing = SimTimingConfig(
        time_scale=time_scale or 0.0,
        min_delay_seconds=args.min_delay,
        max_delay_seconds=args.max_delay,
    )
    try:
        engine = build_engine(args.root, lifecycle_logger=lifecycle_logger, sim_timing=sim_timing)
        result = engine.run(max_nodes=args.max_nodes)
    finally:
        if lifecycle_logger:
            lifecycle_logger.close()
    completed = sum(1 for status in result.node_statuses.values() if status == "completed")
    failed = sum(1 for status in result.node_statuses.values() if status == "failed")
    pending = sum(1 for status in result.node_statuses.values() if status == "pending")
    waiting_guard = sum(1 for status in result.node_statuses.values() if status == "waiting_guard")

    print("GXLF simulation run")
    print(f"  status:         {result.flow_status.value}")
    print(f"  executed_nodes: {result.executed_nodes}")
    print(f"  completed:      {completed}")
    print(f"  failed:         {failed}")
    print(f"  pending:        {pending}")
    print(f"  waiting_guard:  {waiting_guard}")
    print(f"  callbacks:      {len(result.callbacks)}")
    print(f"  guard_checks:   {len(result.guard_results)}")
    print(f"  time_scale:     {sim_timing.time_scale:g}")
    if args.target_duration:
        print(f"  target_duration:{args.target_duration:g}s")
    if lifecycle_logger:
        print(f"  lifecycle_log:  {lifecycle_logger.path}")
        print(f"  lifecycle_events: {len(lifecycle_logger.events)}")
    print()

    print("Recent events:")
    for event in result.events[-args.events:]:
        fanout = ""
        if event.fan_out_total:
            fanout = f" fanout={event.fan_out_success}/{event.fan_out_total}, failed={event.fan_out_failed}"
        node = f" {event.node_id}/{event.node_name}" if event.node_name else ""
        detail = f" {event.detail}" if event.detail else ""
        print(f"  {event.event_type}{node}{detail}{fanout}")

    if args.show_failed:
        failed_nodes = [
            name
            for name, status in result.node_statuses.items()
            if status == "failed"
        ]
        if failed_nodes:
            print()
            print("Failed nodes:")
            for name in failed_nodes:
                print(f"  {name}")

    return 0 if result.flow_status.value == "completed" else 1


def cmd_timeline(args: argparse.Namespace) -> int:
    event_count = write_timeline(args.input, args.output, title=args.title)
    print("GXLF timeline generated")
    print(f"  input:  {args.input}")
    print(f"  output: {args.output}")
    print(f"  events: {event_count}")
    return 0


def cmd_bridge(args: argparse.Namespace) -> int:
    config = BridgeConfig(
        root=args.root,
        host=args.host,
        port=args.port,
        delay_seconds=args.delay,
        max_nodes=args.max_nodes,
        time_scale=args.time_scale,
        min_delay_seconds=args.min_delay,
        max_delay_seconds=args.max_delay,
        target_duration_seconds=args.target_duration,
    )
    run_bridge(config)
    return 0


def main() -> None:
    parser = argparse.ArgumentParser(description="GXLF contract-driven simulation system")
    parser.add_argument(
        "--root",
        type=Path,
        default=Path("."),
        help="repository root, package root, or directory containing gxlf_sim_system/models/",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate_parser = subparsers.add_parser("validate", help="validate model cross references")
    validate_parser.set_defaults(func=cmd_validate)

    run_parser = subparsers.add_parser("run", help="run the simulated flow")
    run_parser.add_argument("--max-nodes", type=int, default=None, help="stop after N executed nodes")
    run_parser.add_argument("--events", type=int, default=20, help="number of recent events to print")
    run_parser.add_argument("--show-failed", action="store_true", help="print failed node names")
    run_parser.add_argument("--lifecycle-log", type=Path, default=None, help="write device lifecycle events as JSONL")
    run_parser.add_argument("--time-scale", type=float, default=None, help="scale model durations into simulator sleep time")
    run_parser.add_argument("--target-duration", type=float, default=None, help="auto-scale the full flow critical path to N seconds")
    run_parser.add_argument("--min-delay", type=float, default=0.0, help="minimum async command delay in seconds")
    run_parser.add_argument("--max-delay", type=float, default=5.0, help="maximum async command delay in seconds")
    run_parser.set_defaults(func=cmd_run)

    timeline_parser = subparsers.add_parser("timeline", help="render lifecycle JSONL as an interactive HTML timeline")
    timeline_parser.add_argument("--input", type=Path, required=True, help="lifecycle JSONL input path")
    timeline_parser.add_argument("--output", type=Path, required=True, help="HTML output path")
    timeline_parser.add_argument("--title", default="GXLF Device Timeline", help="timeline title")
    timeline_parser.set_defaults(func=cmd_timeline)

    bridge_parser = subparsers.add_parser("bridge", help="serve lifecycle events over SSE")
    bridge_parser.add_argument("--host", default="127.0.0.1", help="bind host")
    bridge_parser.add_argument("--port", type=int, default=8765, help="bind port")
    bridge_parser.add_argument("--delay", type=float, default=0.0, help="extra delay after each published lifecycle event in seconds")
    bridge_parser.add_argument("--max-nodes", type=int, default=None, help="stop simulation after N executed nodes")
    bridge_parser.add_argument("--time-scale", type=float, default=None, help="scale model durations into simulator sleep time; overrides --target-duration")
    bridge_parser.add_argument("--target-duration", type=float, default=60.0, help="auto-scale the full flow critical path to N seconds")
    bridge_parser.add_argument("--min-delay", type=float, default=0.0, help="minimum async command delay in seconds")
    bridge_parser.add_argument("--max-delay", type=float, default=3.0, help="maximum async command delay in seconds")
    bridge_parser.set_defaults(func=cmd_bridge)

    args = parser.parse_args()
    raise SystemExit(args.func(args))
