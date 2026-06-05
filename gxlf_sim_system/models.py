from __future__ import annotations

import re
from dataclasses import dataclass, field
from enum import Enum
from typing import Any


_TIME_RE = re.compile(r"(\d+(?:\.\d+)?)\s*(ms|s|min|m)")


def parse_duration(value: Any) -> float:
    if value is None:
        return 0.0
    if isinstance(value, (int, float)):
        return float(value)
    match = _TIME_RE.fullmatch(str(value).strip())
    if not match:
        try:
            return float(value)
        except (TypeError, ValueError):
            return 0.0
    amount = float(match.group(1))
    unit = match.group(2)
    if unit == "ms":
        return amount / 1000
    if unit in ("min", "m"):
        return amount * 60
    return amount


class NodeStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    SKIPPED = "skipped"


class FlowStatus(str, Enum):
    IDLE = "idle"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    ABORTED = "aborted"


@dataclass(frozen=True)
class FlowNode:
    name: str
    node_id: str
    node_type: str
    stage: str
    depends: list[str]
    target_systems: list[str]
    fan_out: str
    completion: str
    command: str | None
    call_mode: str
    timeout_seconds: float
    raw: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ServiceTarget:
    system_name: str
    service_id: str
    instance_code: str
    beam_line_no: int | None = None
    beam_group_no: int | None = None


@dataclass
class ServiceInstance:
    system_name: str
    service_type: str
    system_code: str
    service_id: str
    tango_fqdn: str
    instance_code: str
    protocol: str
    selector: str
    health_state: str
    business_state: str
    current_task_id: str | None = None
    task_seq: int = 0
    history: list[dict[str, Any]] = field(default_factory=list)


@dataclass
class CommandRequest:
    shot_id: str
    stage_id: str
    flow_task_id: str
    node_id: str
    node_name: str
    command: str
    business_instruction: str
    caller: str
    flow_instance_id: str
    target_service_id: str
    target_instance_code: str
    beam_line_no: int | None = None
    beam_group_no: int | None = None
    selected_beam_lines: list[int] | None = None
    selected_beam_groups: list[int] | None = None
    params: dict[str, Any] = field(default_factory=dict)
    issued_at: str = ""
    timeout_ms: int | None = None

    def to_payload(self) -> dict[str, Any]:
        payload = {
            "shot_id": self.shot_id,
            "stage_id": self.stage_id,
            "flow_task_id": self.flow_task_id,
            "node_id": self.node_id,
            "node_name": self.node_name,
            "command": self.command,
            "business_instruction": self.business_instruction,
            "caller": self.caller,
            "flow_instance_id": self.flow_instance_id,
            "target_service_id": self.target_service_id,
            "target_instance_code": self.target_instance_code,
            "params": self.params,
            "issued_at": self.issued_at,
        }
        if self.beam_line_no is not None:
            payload["beam_line_no"] = self.beam_line_no
        if self.beam_group_no is not None:
            payload["beam_group_no"] = self.beam_group_no
        if self.selected_beam_lines is not None:
            payload["selected_beam_lines"] = self.selected_beam_lines
        if self.selected_beam_groups is not None:
            payload["selected_beam_groups"] = self.selected_beam_groups
        if self.timeout_ms is not None:
            payload["timeout_ms"] = self.timeout_ms
        return payload


@dataclass
class TaskCallback:
    flow_instance_id: str
    shot_id: str
    stage_id: str
    flow_task_id: str
    node_id: str
    node_name: str
    task_id: str
    command: str
    system_name: str
    service_id: str
    instance_code: str
    service_health_state: str
    business_state: str
    task_state: str
    result_code: int
    result_status: str
    updated_at: str
    beam_line_no: int | None = None
    beam_group_no: int | None = None
    progress: float | None = None
    error: dict[str, Any] | None = None
    payload: dict[str, Any] | None = None

    def to_payload(self) -> dict[str, Any]:
        payload = {
            "flow_instance_id": self.flow_instance_id,
            "shot_id": self.shot_id,
            "stage_id": self.stage_id,
            "flow_task_id": self.flow_task_id,
            "node_id": self.node_id,
            "node_name": self.node_name,
            "task_id": self.task_id,
            "command": self.command,
            "system_name": self.system_name,
            "service_id": self.service_id,
            "instance_code": self.instance_code,
            "service_health_state": self.service_health_state,
            "business_state": self.business_state,
            "task_state": self.task_state,
            "result_code": self.result_code,
            "result_status": self.result_status,
            "updated_at": self.updated_at,
        }
        if self.beam_line_no is not None:
            payload["beam_line_no"] = self.beam_line_no
        if self.beam_group_no is not None:
            payload["beam_group_no"] = self.beam_group_no
        if self.progress is not None:
            payload["progress"] = self.progress
        if self.error is not None:
            payload["error"] = self.error
        if self.payload is not None:
            payload["payload"] = self.payload
        return payload

