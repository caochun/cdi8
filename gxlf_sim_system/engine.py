from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from .models import CommandRequest, FlowNode, FlowStatus, NodeStatus, ServiceTarget
from .service_sim import ModelConsistencyError, SimServiceRegistry, TangoSimAdapter


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass
class EngineEvent:
    event_type: str
    node_name: str = ""
    node_id: str = ""
    detail: str = ""
    fan_out_total: int = 0
    fan_out_success: int = 0
    fan_out_failed: int = 0


@dataclass
class FlowRunResult:
    flow_status: FlowStatus
    node_statuses: dict[str, NodeStatus]
    events: list[EngineEvent]
    callbacks: list[dict[str, Any]]
    executed_nodes: int


@dataclass
class FlowEngine:
    nodes: dict[str, FlowNode]
    registry: SimServiceRegistry
    tango_adapter: TangoSimAdapter
    node_contracts_by_id: dict[str, dict[str, Any]]
    shot_id: str = "SHOT-SIM-001"
    flow_instance_id: str = "FLOW-SIM-001"
    caller: str = "gxlf.flow_engine"
    node_statuses: dict[str, NodeStatus] = field(init=False)
    events: list[EngineEvent] = field(default_factory=list)
    callbacks: list[dict[str, Any]] = field(default_factory=list)

    def __post_init__(self) -> None:
        self.node_statuses = {name: NodeStatus.PENDING for name in self.nodes}

    def run(self, max_nodes: int | None = None) -> FlowRunResult:
        self.events.append(EngineEvent("flow_started", detail=self.flow_instance_id))
        executed = 0
        while True:
            ready = self._ready_nodes()
            if not ready:
                break
            for node in ready:
                if max_nodes is not None and executed >= max_nodes:
                    self.events.append(EngineEvent("flow_stopped", detail=f"max_nodes={max_nodes}"))
                    return self._result(FlowStatus.COMPLETED, executed)
                self._execute_node(node)
                executed += 1
                if self.node_statuses[node.name] == NodeStatus.FAILED:
                    self.events.append(EngineEvent("flow_failed", node.name, node.node_id))
                    return self._result(FlowStatus.FAILED, executed)
        status = FlowStatus.COMPLETED if self._all_terminal_success() else FlowStatus.FAILED
        self.events.append(EngineEvent("flow_completed" if status == FlowStatus.COMPLETED else "flow_incomplete"))
        return self._result(status, executed)

    def _result(self, flow_status: FlowStatus, executed: int) -> FlowRunResult:
        return FlowRunResult(
            flow_status=flow_status,
            node_statuses=dict(self.node_statuses),
            events=list(self.events),
            callbacks=list(self.callbacks),
            executed_nodes=executed,
        )

    def _ready_nodes(self) -> list[FlowNode]:
        ready: list[FlowNode] = []
        for node in self.nodes.values():
            if self.node_statuses[node.name] != NodeStatus.PENDING:
                continue
            if all(self.node_statuses.get(dep) in (NodeStatus.COMPLETED, NodeStatus.SKIPPED) for dep in node.depends):
                ready.append(node)
        return sorted(ready, key=lambda node: node.node_id)

    def _all_terminal_success(self) -> bool:
        return all(status in (NodeStatus.COMPLETED, NodeStatus.SKIPPED) for status in self.node_statuses.values())

    def _execute_node(self, node: FlowNode) -> None:
        self.node_statuses[node.name] = NodeStatus.RUNNING
        self.events.append(EngineEvent("node_started", node.name, node.node_id, node.command or node.node_type))

        if node.node_type == "join":
            self.node_statuses[node.name] = NodeStatus.COMPLETED
            self.events.append(EngineEvent("node_completed", node.name, node.node_id, "join"))
            return

        if not node.command:
            self.node_statuses[node.name] = NodeStatus.FAILED
            self.events.append(EngineEvent("node_failed", node.name, node.node_id, "missing command"))
            return

        try:
            targets = self._resolve_node_targets(node)
        except ModelConsistencyError as exc:
            self.node_statuses[node.name] = NodeStatus.FAILED
            self.events.append(EngineEvent("node_failed", node.name, node.node_id, str(exc)))
            return

        successes = 0
        failures = 0
        for target in targets:
            request = self._make_request(node, target, targets)
            accept_json, callbacks = self.tango_adapter.command_inout(
                node.command,
                json.dumps(request.to_payload(), ensure_ascii=False),
            )
            accept = json.loads(accept_json)
            if accept.get("accepted") and self._callbacks_success(callbacks):
                successes += 1
            else:
                failures += 1
                self.events.append(
                    EngineEvent(
                        "target_failed",
                        node.name,
                        node.node_id,
                        f"{target.service_id}: {accept.get('message', 'failed')}",
                    )
                )
            self.callbacks.extend(callbacks)

        self.events.append(
            EngineEvent(
                "node_fanout_result",
                node.name,
                node.node_id,
                node.completion,
                fan_out_total=len(targets),
                fan_out_success=successes,
                fan_out_failed=failures,
            )
        )
        if failures == 0:
            self.node_statuses[node.name] = NodeStatus.COMPLETED
            self.events.append(EngineEvent("node_completed", node.name, node.node_id, node.command))
        else:
            self.node_statuses[node.name] = NodeStatus.FAILED
            self.events.append(EngineEvent("node_failed", node.name, node.node_id, "fan-out failure"))

    def _resolve_node_targets(self, node: FlowNode) -> list[ServiceTarget]:
        targets: list[ServiceTarget] = []
        for system_name in node.target_systems:
            targets.extend(self.registry.resolve_targets(system_name, node.fan_out))
        return targets

    def _make_request(self, node: FlowNode, target: ServiceTarget, all_targets: list[ServiceTarget]) -> CommandRequest:
        contract = self.node_contracts_by_id.get(node.node_id, {})
        selected_lines = sorted({t.beam_line_no for t in all_targets if t.beam_line_no is not None}) or None
        selected_groups = sorted({t.beam_group_no for t in all_targets if t.beam_group_no is not None}) or None
        return CommandRequest(
            shot_id=self.shot_id,
            stage_id=node.stage,
            flow_task_id=f"{self.flow_instance_id}.{node.node_id}",
            node_id=node.node_id,
            node_name=node.name,
            command=node.command or "",
            business_instruction=str(contract.get("business_instruction") or node.name),
            caller=self.caller,
            flow_instance_id=self.flow_instance_id,
            target_service_id=target.service_id,
            target_instance_code=target.instance_code,
            beam_line_no=target.beam_line_no,
            beam_group_no=target.beam_group_no,
            selected_beam_lines=selected_lines,
            selected_beam_groups=selected_groups,
            params=self._default_params(contract),
            issued_at=utc_now(),
            timeout_ms=int(node.timeout_seconds * 1000) if node.timeout_seconds else None,
        )

    def _default_params(self, contract: dict[str, Any]) -> dict[str, Any]:
        params: dict[str, Any] = {}
        for item in contract.get("extra_inputs") or []:
            name = item.get("name")
            if not name or name in ("beam_line_no", "beam_group_no", "selected_beam_lines", "selected_beam_groups"):
                continue
            if "default" in item:
                params[name] = item["default"]
            elif item.get("type") == "number":
                params[name] = 1.0
            elif item.get("type") == "integer":
                enum = item.get("enum") or item.get("values")
                params[name] = enum[0] if enum else 1
            elif item.get("type") == "boolean":
                params[name] = True
            else:
                params[name] = "sim"
        return params

    def _callbacks_success(self, callbacks: list[dict[str, Any]]) -> bool:
        return any(cb.get("task_state") == "succeeded" and cb.get("result_status") == "success" for cb in callbacks)
