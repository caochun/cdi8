from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

from .domain import FlowNode, parse_duration


@dataclass
class ModelBundle:
    root: Path
    flow_path: Path
    interface_path: Path
    state_machine_path: Path
    flow: dict[str, Any]
    interface: dict[str, Any]
    state_machine: dict[str, Any]
    nodes: dict[str, FlowNode]
    node_contracts_by_id: dict[str, dict[str, Any]]
    service_type_by_system: dict[str, str]


def _read_yaml(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as fh:
        return yaml.safe_load(fh) or {}


def _depends(raw: list[Any]) -> list[str]:
    result: list[str] = []
    for item in raw or []:
        if isinstance(item, str):
            result.append(item)
        elif isinstance(item, dict) and item.get("node"):
            result.append(str(item["node"]))
    return result


def _target_systems(raw_target: Any) -> tuple[list[str], str, str]:
    if not isinstance(raw_target, dict):
        return [], "broadcast", "all_success"
    systems = raw_target.get("system") or []
    if isinstance(systems, str):
        systems = [systems]
    return (
        [str(s) for s in systems],
        str(raw_target.get("fan_out", "broadcast")),
        str(raw_target.get("completion", "all_success")),
    )


def _load_nodes(flow: dict[str, Any]) -> dict[str, FlowNode]:
    nodes: dict[str, FlowNode] = {}
    for name, raw in (flow.get("nodes") or {}).items():
        target_systems, fan_out, completion = _target_systems(raw.get("target"))
        nodes[name] = FlowNode(
            name=name,
            node_id=str(raw.get("id", "")),
            node_type=str(raw.get("type", "action")),
            stage=str(raw.get("stage", "")),
            depends=_depends(raw.get("depends") or []),
            target_systems=target_systems,
            fan_out=fan_out,
            completion=completion,
            command=raw.get("command"),
            call_mode=str(raw.get("call_mode", "sync")),
            timeout_seconds=parse_duration(raw.get("timeout", "10s")),
            raw=raw,
        )
    return nodes


def _service_type_by_system(state_machine: dict[str, Any]) -> dict[str, str]:
    mapping: dict[str, str] = {}
    for service_type, template in (state_machine.get("service_type_templates") or {}).items():
        for system_name in template.get("applies_to") or []:
            mapping[str(system_name)] = str(service_type)
    return mapping


def load_models(root: str | Path = ".") -> ModelBundle:
    root_path = Path(root).resolve()
    model_candidates = [
        root_path / "gxlf_sim_system" / "models",
        root_path / "models",
        Path(__file__).resolve().parent / "models",
    ]
    model_dir = next((candidate for candidate in model_candidates if candidate.exists()), model_candidates[-1])
    flow_path = model_dir / "gxlf-firing-flow.yaml"
    interface_path = model_dir / "interface-contracts.yaml"
    state_machine_path = model_dir / "service-state-machines.yaml"

    flow = _read_yaml(flow_path)
    interface = _read_yaml(interface_path)
    state_machine = _read_yaml(state_machine_path)
    nodes = _load_nodes(flow)
    node_contracts = {
        str(contract.get("node_id")): contract
        for contract in interface.get("node_contracts") or []
        if contract.get("node_id")
    }

    return ModelBundle(
        root=root_path,
        flow_path=flow_path,
        interface_path=interface_path,
        state_machine_path=state_machine_path,
        flow=flow,
        interface=interface,
        state_machine=state_machine,
        nodes=nodes,
        node_contracts_by_id=node_contracts,
        service_type_by_system=_service_type_by_system(state_machine),
    )
