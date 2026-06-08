from __future__ import annotations

import json
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from gxlf_sim_system.cli import build_engine
from gxlf_sim_system.domain import FlowNode, FlowStatus, NodeStatus, ServiceInstance, ServiceTarget
from gxlf_sim_system.engine import EngineEvent, FlowEngine, FlowRunControl
from gxlf_sim_system.guards import FlowRuntimeContext, GuardEvaluator
from gxlf_sim_system.loader import load_models
from gxlf_sim_system.service_sim import SimFault, SimFaultRegistry, SimServiceRegistry, SimTimingConfig


REPO_ROOT = Path(__file__).resolve().parents[2]


@dataclass
class AdapterAction:
    behavior: str = "success"
    task_state: str = "succeeded"
    result_status: str = "success"


@dataclass
class FakeCallbackBus:
    callbacks_by_task: dict[str, list[dict[str, Any]]] = field(default_factory=dict)

    def wait_for_terminal(self, task_id: str, timeout_seconds: float) -> list[dict[str, Any]]:
        return list(self.callbacks_by_task.get(task_id, []))


class RecordingAdapter:
    def __init__(self, actions: dict[str, AdapterAction] | None = None):
        self.actions = actions or {}
        self.command_records: list[dict[str, Any]] = []
        self.callback_bus = FakeCallbackBus()
        self._seq = 0

    def command_inout(self, command_name: str, dev_string_json: str) -> str:
        payload = json.loads(dev_string_json)
        self.command_records.append(payload)
        service_id = str(payload.get("target_service_id") or "")
        action = self.actions.get(service_id) or self.actions.get("*") or AdapterAction()
        if action.behavior == "reject":
            return json.dumps({
                "accepted": False,
                "task_id": payload.get("flow_task_id", ""),
                "service_id": service_id,
                "instance_code": payload.get("target_instance_code", ""),
                "command": command_name,
                "message": "injected reject",
            })
        self._seq += 1
        task_id = f"{payload['shot_id']}.{payload['node_id']}.{service_id}.{self._seq}"
        if action.behavior != "timeout":
            self.callback_bus.callbacks_by_task[task_id] = [
                {
                    "task_id": task_id,
                    "task_state": action.task_state,
                    "result_status": action.result_status,
                    "service_id": service_id,
                    "node_id": payload.get("node_id"),
                    "node_name": payload.get("node_name"),
                }
            ]
        return json.dumps({
            "accepted": True,
            "task_id": task_id,
            "service_id": service_id,
            "instance_code": payload.get("target_instance_code", ""),
            "command": command_name,
            "message": "accepted",
            "sim_delay_seconds": 0,
            "sim_node_delay_seconds": 0,
        })


def make_registry(system_name: str = "系统A", count: int = 1) -> SimServiceRegistry:
    state_machine = {
        "system_instance_catalog": {
            system_name: {
                "system_code": "system_a",
                "selector": "broadcast",
                "service_ids": [f"fake.system_a.svc{i:02d}" for i in range(1, count + 1)],
            }
        },
        "service_type_templates": {
            "fake": {
                "applies_to": [system_name],
                "initial_business_state": "idle",
                "business_states": ["idle"],
                "commands": {"Do": {"transition": {"from": "any", "to": "idle"}}},
            }
        },
    }
    return SimServiceRegistry(state_machine)


def make_node(
    name: str,
    node_id: str,
    depends: list[str] | None = None,
    raw: dict[str, Any] | None = None,
    target_systems: list[str] | None = None,
    fan_out: str = "broadcast",
    completion: str = "all_success",
    command: str | None = "Do",
) -> FlowNode:
    return FlowNode(
        name=name,
        node_id=node_id,
        node_type="action",
        stage="测试",
        depends=depends or [],
        target_systems=target_systems or ["系统A"],
        fan_out=fan_out,
        completion=completion,
        command=command,
        call_mode="sync",
        timeout_seconds=0.001,
        raw=raw or {},
    )


def make_engine(
    nodes: list[FlowNode],
    registry: SimServiceRegistry | None = None,
    adapter: RecordingAdapter | None = None,
    context: FlowRuntimeContext | None = None,
    event_sink: list[EngineEvent] | None = None,
) -> tuple[FlowEngine, RecordingAdapter, list[EngineEvent]]:
    registry = registry or make_registry()
    adapter = adapter or RecordingAdapter()
    events = event_sink if event_sink is not None else []
    engine = FlowEngine(
        nodes={node.name: node for node in nodes},
        registry=registry,
        tango_adapter=adapter,  # type: ignore[arg-type]
        node_contracts_by_id={},
        guard_evaluator=GuardEvaluator(registry, context),
        event_sink=events.append,
    )
    return engine, adapter, events


def run_engine_in_thread(engine: FlowEngine, control: FlowRunControl | None = None, **run_kwargs: Any) -> tuple[threading.Thread, dict[str, Any]]:
    box: dict[str, Any] = {}
    control = control or FlowRunControl()

    def runner() -> None:
        box["result"] = engine.run(control=control, **run_kwargs)

    thread = threading.Thread(target=runner, daemon=True)
    thread.start()
    return thread, box


def wait_until(predicate: Any, timeout_seconds: float = 2.0) -> bool:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.01)
    return bool(predicate())


def load_scenario(path: Path) -> dict[str, Any]:
    return yaml.safe_load(path.read_text(encoding="utf-8")) or {}


def build_real_engine(
    runtime_context: FlowRuntimeContext | None = None,
    events: list[EngineEvent] | None = None,
    fault_registry: SimFaultRegistry | None = None,
) -> FlowEngine:
    return build_engine(
        REPO_ROOT,
        sim_timing=SimTimingConfig(time_scale=0.0),
        runtime_context=runtime_context,
        engine_event_sink=(events.append if events is not None else None),
        fault_registry=fault_registry,
    )


def normalize_status(value: Any) -> str:
    if isinstance(value, (FlowStatus, NodeStatus)):
        return value.value
    return str(value)


def node_by_id(engine: FlowEngine, node_id: str) -> str:
    for name, node in engine.nodes.items():
        if node.node_id == node_id:
            return name
    raise AssertionError(f"unknown node id {node_id}")


def set_instance_health(engine: FlowEngine, system_name: str, health_state: str) -> None:
    for instance in engine.registry.instances_by_system.get(system_name, []):
        instance.health_state = health_state


def scenario_paths() -> list[Path]:
    return sorted((Path(__file__).parent / "scenarios").glob("**/*.yaml"))
