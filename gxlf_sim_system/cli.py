from __future__ import annotations

import argparse
from pathlib import Path

from .contracts import ContractValidator
from .engine import FlowEngine
from .loader import load_models
from .service_sim import SimServiceRegistry, TangoSimAdapter
from .validation import validate_model_bundle


def build_engine(root: Path) -> FlowEngine:
    bundle = load_models(root)
    registry = SimServiceRegistry(bundle.state_machine)
    validator = ContractValidator(bundle.interface, bundle.state_machine)
    tango = TangoSimAdapter(registry, bundle.state_machine, validator)
    return FlowEngine(
        nodes=bundle.nodes,
        registry=registry,
        tango_adapter=tango,
        node_contracts_by_id=bundle.node_contracts_by_id,
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
    engine = build_engine(args.root)
    result = engine.run(max_nodes=args.max_nodes)
    completed = sum(1 for status in result.node_statuses.values() if status == "completed")
    failed = sum(1 for status in result.node_statuses.values() if status == "failed")
    pending = sum(1 for status in result.node_statuses.values() if status == "pending")

    print("GXLF simulation run")
    print(f"  status:         {result.flow_status.value}")
    print(f"  executed_nodes: {result.executed_nodes}")
    print(f"  completed:      {completed}")
    print(f"  failed:         {failed}")
    print(f"  pending:        {pending}")
    print(f"  callbacks:      {len(result.callbacks)}")
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


def main() -> None:
    parser = argparse.ArgumentParser(description="GXLF contract-driven simulation system")
    parser.add_argument("--root", type=Path, default=Path("."), help="repository root containing flow-1/")
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate_parser = subparsers.add_parser("validate", help="validate model cross references")
    validate_parser.set_defaults(func=cmd_validate)

    run_parser = subparsers.add_parser("run", help="run the simulated flow")
    run_parser.add_argument("--max-nodes", type=int, default=None, help="stop after N executed nodes")
    run_parser.add_argument("--events", type=int, default=20, help="number of recent events to print")
    run_parser.add_argument("--show-failed", action="store_true", help="print failed node names")
    run_parser.set_defaults(func=cmd_run)

    args = parser.parse_args()
    raise SystemExit(args.func(args))

