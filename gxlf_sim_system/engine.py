from __future__ import annotations

import json
import threading
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Callable

from .domain import CommandRequest, FlowNode, FlowStatus, NodeStatus, ServiceTarget, parse_duration
from .guards import GuardEvaluator, GuardResult
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
    data: dict[str, Any] = field(default_factory=dict)


@dataclass
class FlowRunResult:
    flow_status: FlowStatus
    node_statuses: dict[str, NodeStatus]
    events: list[EngineEvent]
    callbacks: list[dict[str, Any]]
    guard_results: list[GuardResult]
    executed_nodes: int


class FlowRunControl:
    def __init__(self) -> None:
        self._condition = threading.Condition()
        self._paused = False
        self._stop_requested = False

    def pause(self) -> None:
        with self._condition:
            self._paused = True
            self._condition.notify_all()

    def resume(self) -> None:
        with self._condition:
            self._paused = False
            self._condition.notify_all()

    def stop(self) -> None:
        with self._condition:
            self._stop_requested = True
            self._paused = False
            self._condition.notify_all()

    def wait_if_paused(self) -> bool:
        with self._condition:
            while self._paused and not self._stop_requested:
                self._condition.wait()
            return self._stop_requested

    @property
    def stop_requested(self) -> bool:
        with self._condition:
            return self._stop_requested


@dataclass
class FlowEngine:
    nodes: dict[str, FlowNode]
    registry: SimServiceRegistry
    tango_adapter: TangoSimAdapter
    node_contracts_by_id: dict[str, dict[str, Any]]
    guard_evaluator: GuardEvaluator | None = None
    event_sink: Callable[[EngineEvent], None] | None = None
    shot_id: str = "SHOT-SIM-001"
    flow_instance_id: str = "FLOW-SIM-001"
    caller: str = "gxlf.flow_engine"
    node_statuses: dict[str, NodeStatus] = field(init=False)
    events: list[EngineEvent] = field(default_factory=list)
    callbacks: list[dict[str, Any]] = field(default_factory=list)
    guard_results: list[GuardResult] = field(default_factory=list)

    def __post_init__(self) -> None:
        self.node_statuses = {name: NodeStatus.PENDING for name in self.nodes}

    def run(self, max_nodes: int | None = None, control: FlowRunControl | None = None) -> FlowRunResult:
        self._emit_event(EngineEvent("flow_started", detail=self.flow_instance_id))
        executed = 0
        while True:
            if control and control.wait_if_paused():
                self._emit_event(EngineEvent("flow_stopped", detail="external stop requested"))
                return self._result(FlowStatus.ABORTED, executed)
            ready = self._ready_nodes()
            if not ready:
                break
            made_progress = False
            blocked_by_guard = False
            for node in ready:
                if control and control.wait_if_paused():
                    self._emit_event(EngineEvent("flow_stopped", detail="external stop requested"))
                    return self._result(FlowStatus.ABORTED, executed)
                if max_nodes is not None and executed >= max_nodes:
                    self._emit_event(EngineEvent("flow_stopped", detail=f"max_nodes={max_nodes}"))
                    return self._result(FlowStatus.COMPLETED, executed)
                if not self._guards_passed(node):
                    blocked_by_guard = True
                    continue
                self._execute_node(node)
                executed += 1
                made_progress = True
                if self.node_statuses[node.name] == NodeStatus.FAILED:
                    self._emit_event(EngineEvent("flow_failed", node.name, node.node_id))
                    return self._result(FlowStatus.FAILED, executed)
            if blocked_by_guard and not made_progress:
                if not self._wait_for_guard_update(control):
                    return self._result(FlowStatus.ABORTED, executed)
        status = FlowStatus.COMPLETED if self._all_terminal_success() else FlowStatus.FAILED
        self._emit_event(EngineEvent("flow_completed" if status == FlowStatus.COMPLETED else "flow_incomplete"))
        return self._result(status, executed)

    def _result(self, flow_status: FlowStatus, executed: int) -> FlowRunResult:
        return FlowRunResult(
            flow_status=flow_status,
            node_statuses=dict(self.node_statuses),
            events=list(self.events),
            callbacks=list(self.callbacks),
            guard_results=list(self.guard_results),
            executed_nodes=executed,
        )

    def _emit_event(self, event: EngineEvent) -> None:
        self.events.append(event)
        if self.event_sink:
            self.event_sink(event)

    def _ready_nodes(self) -> list[FlowNode]:
        ready: list[FlowNode] = []
        for node in self.nodes.values():
            if self.node_statuses[node.name] not in (NodeStatus.PENDING, NodeStatus.WAITING_GUARD):
                continue
            if all(self.node_statuses.get(dep) in (NodeStatus.COMPLETED, NodeStatus.SKIPPED) for dep in node.depends):
                ready.append(node)
        return sorted(ready, key=lambda node: node.node_id)

    def _all_terminal_success(self) -> bool:
        return all(status in (NodeStatus.COMPLETED, NodeStatus.SKIPPED) for status in self.node_statuses.values())

    def _guards_passed(self, node: FlowNode) -> bool:
        if not self.guard_evaluator:
            return True
        results = self.guard_evaluator.evaluate(node, self.node_statuses)
        if not results:
            return True
        self.guard_results.extend(results)
        failed = [result for result in results if not result.passed]
        if failed:
            self.node_statuses[node.name] = NodeStatus.WAITING_GUARD
            detail = "; ".join(result.reason for result in failed)
            self._emit_event(
                EngineEvent(
                    "node_guard_blocked",
                    node.name,
                    node.node_id,
                    detail,
                    data={"failed_guards": [self._guard_result_payload(result) for result in failed]},
                )
            )
            return False
        if self.node_statuses[node.name] == NodeStatus.WAITING_GUARD:
            self._emit_event(EngineEvent("node_guard_passed", node.name, node.node_id))
            self.node_statuses[node.name] = NodeStatus.PENDING
        return True

    def _wait_for_guard_update(self, control: FlowRunControl | None) -> bool:
        if not self.guard_evaluator:
            return True
        version = self.guard_evaluator.context.version
        while True:
            if control and control.stop_requested:
                self._emit_event(EngineEvent("flow_stopped", detail="external stop requested"))
                return False
            next_version = self.guard_evaluator.context.wait_for_update(version, timeout_seconds=0.5)
            if next_version != version:
                return True

    def _guard_result_payload(self, result: GuardResult) -> dict[str, Any]:
        return {
            "passed": result.passed,
            "guard_type": result.guard_type,
            "key": result.key,
            "expected": result.expected,
            "actual": result.actual,
            "reason": result.reason,
            "raw": result.raw,
        }

    def _execute_node(self, node: FlowNode) -> None:
        self.node_statuses[node.name] = NodeStatus.RUNNING
        self._emit_event(EngineEvent("node_started", node.name, node.node_id, node.command or node.node_type))

        if node.node_type == "join":
            self.node_statuses[node.name] = NodeStatus.COMPLETED
            self._emit_event(EngineEvent("node_completed", node.name, node.node_id, "join"))
            return

        if not node.command:
            self.node_statuses[node.name] = NodeStatus.FAILED
            self._emit_event(EngineEvent("node_failed", node.name, node.node_id, "missing command"))
            return

        try:
            targets = self._resolve_node_targets(node)
        except ModelConsistencyError as exc:
            self.node_statuses[node.name] = NodeStatus.FAILED
            self._emit_event(EngineEvent("node_failed", node.name, node.node_id, str(exc)))
            return

        successes = 0
        failures = 0
        accepted_tasks: list[tuple[ServiceTarget, dict[str, Any]]] = []
        for target in targets:
            request = self._make_request(node, target, targets)
            accept_json = self.tango_adapter.command_inout(
                node.command,
                json.dumps(request.to_payload(), ensure_ascii=False),
            )
            accept = json.loads(accept_json)
            if accept.get("accepted"):
                accepted_tasks.append((target, accept))
            else:
                failures += 1
                self._emit_event(
                    EngineEvent(
                        "target_failed",
                        node.name,
                        node.node_id,
                        f"{target.service_id}: {accept.get('message', 'failed')}",
                    )
                )

        for target, accept in accepted_tasks:
            callbacks = self.tango_adapter.callback_bus.wait_for_terminal(
                str(accept.get("task_id") or ""),
                self._callback_wait_timeout_seconds(node, accept),
            )
            self.callbacks.extend(callbacks)
            if self._callbacks_success(callbacks):
                successes += 1
            else:
                failures += 1
                terminal = callbacks[-1].get("task_state") if callbacks else "timeout"
                self._emit_event(
                    EngineEvent(
                        "target_failed",
                        node.name,
                        node.node_id,
                        f"{target.service_id}: {terminal}",
                    )
                )

        self._emit_event(
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
            self._emit_event(EngineEvent("node_completed", node.name, node.node_id, node.command))
        else:
            self.node_statuses[node.name] = NodeStatus.FAILED
            self._emit_event(EngineEvent("node_failed", node.name, node.node_id, "fan-out failure"))

    def _resolve_node_targets(self, node: FlowNode) -> list[ServiceTarget]:
        targets: list[ServiceTarget] = []
        for system_name in node.target_systems:
            targets.extend(self.registry.resolve_targets(system_name, node.fan_out))
        return targets

    def _make_request(self, node: FlowNode, target: ServiceTarget, all_targets: list[ServiceTarget]) -> CommandRequest:
        contract = self.node_contracts_by_id.get(node.node_id, {})
        selected_lines = sorted({t.beam_line_no for t in all_targets if t.beam_line_no is not None}) or None
        selected_groups = sorted({t.beam_group_no for t in all_targets if t.beam_group_no is not None}) or None
        fanout_count = max(len(all_targets), 1)
        params = self._default_params(contract)
        params["_sim_fanout_count"] = fanout_count
        if node.command == "SyncTrigger":
            params["_sim_business_countdown_seconds"] = 5.0
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
            params=params,
            issued_at=utc_now(),
            timeout_ms=int(node.timeout_seconds * 1000) if node.timeout_seconds else None,
            sim_expected_duration_ms=self._sim_expected_duration_ms(node),
        )

    def _sim_expected_duration_ms(self, node: FlowNode) -> int | None:
        timing = node.raw.get("timing") if isinstance(node.raw.get("timing"), dict) else {}
        expected = parse_duration(timing.get("expected_duration"))
        if expected <= 0:
            expected = self._duration_from_timeout(node)
        return int(expected * 1000) if expected > 0 else None

    def _duration_from_timeout(self, node: FlowNode) -> float:
        if node.timeout_seconds <= 0:
            return 0.0
        if node.call_mode == "sync":
            return min(node.timeout_seconds * 0.25, 2.0)
        return min(node.timeout_seconds * 0.5, 60.0)

    def _callback_wait_timeout_seconds(self, node: FlowNode, accept: dict[str, Any]) -> float:
        if accept.get("sim_fault_behavior") == "callback_timeout":
            return 0.2
        model_timeout = node.timeout_seconds if node.timeout_seconds > 0 else 10.0
        sim_node_delay = float(accept.get("sim_node_delay_seconds") or 0)
        sim_delay = float(accept.get("sim_delay_seconds") or 0)
        return max(model_timeout, sim_node_delay, sim_delay) + 1.0

    def estimated_critical_path_duration_seconds(self) -> float:
        durations: dict[str, float] = {}
        for node in sorted(self.nodes.values(), key=lambda item: item.node_id):
            own = self._sim_expected_duration_ms(node)
            own_seconds = (own or 0) / 1000
            dependency_seconds = max((durations.get(dep, 0.0) for dep in node.depends), default=0.0)
            durations[node.name] = dependency_seconds + own_seconds
        return max(durations.values(), default=0.0)

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
