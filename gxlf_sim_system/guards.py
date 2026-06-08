from __future__ import annotations

import threading
from dataclasses import dataclass, field
from typing import Any

from .domain import FlowNode, NodeStatus
from .service_sim import SimServiceRegistry


@dataclass(frozen=True)
class GuardResult:
    passed: bool
    guard_type: str
    key: str
    expected: Any
    actual: Any
    reason: str
    raw: dict[str, Any] = field(default_factory=dict)


@dataclass
class FlowRuntimeContext:
    flags: dict[str, Any] = field(default_factory=lambda: {
        "recipe_loaded": True,
        "central_control_healthy": True,
    })
    interlocks: dict[str, Any] = field(default_factory=lambda: {
        "safety": "normal",
    })
    strict_unknown_guards: bool = False

    def __post_init__(self) -> None:
        self._condition = threading.Condition()
        self._version = 0

    @property
    def version(self) -> int:
        with self._condition:
            return self._version

    def snapshot(self) -> dict[str, Any]:
        with self._condition:
            return {
                "flags": dict(self.flags),
                "interlocks": dict(self.interlocks),
                "version": self._version,
                "strict_unknown_guards": self.strict_unknown_guards,
            }

    def set_flag(self, key: str, value: Any) -> None:
        with self._condition:
            self.flags[key] = value
            self._version += 1
            self._condition.notify_all()

    def set_interlock(self, key: str, value: Any) -> None:
        with self._condition:
            self.interlocks[key] = value
            self._version += 1
            self._condition.notify_all()

    def notify_update(self) -> None:
        with self._condition:
            self._version += 1
            self._condition.notify_all()

    def wait_for_update(self, version: int, timeout_seconds: float = 0.5) -> int:
        with self._condition:
            if self._version == version:
                self._condition.wait(timeout_seconds)
            return self._version


class GuardEvaluator:
    LEGACY_CONTEXT_FLAGS = {
        "流程配方加载成功": ("recipe_loaded", True),
        "总控服务状态正常": ("central_control_healthy", True),
    }

    def __init__(self, registry: SimServiceRegistry, context: FlowRuntimeContext | None = None):
        self.registry = registry
        self.context = context or FlowRuntimeContext()

    def evaluate(self, node: FlowNode, node_statuses: dict[str, NodeStatus]) -> list[GuardResult]:
        guards = normalize_runtime_guards(node.raw.get("runtime_guards") or [])
        return [self._evaluate_guard(guard, node_statuses) for guard in guards]

    def _evaluate_guard(self, guard: dict[str, Any], node_statuses: dict[str, NodeStatus]) -> GuardResult:
        guard_type = str(guard.get("type") or "")
        if guard_type == "context_flag":
            key = str(guard.get("key") or "")
            expected = guard.get("expected", True)
            actual = self.context.flags.get(key)
            return self._result(guard, actual == expected, guard_type, key, expected, actual)

        if guard_type == "interlock_state":
            key = str(guard.get("key") or "")
            expected = guard.get("expected", "normal")
            actual = self.context.interlocks.get(key)
            return self._result(guard, actual == expected, guard_type, key, expected, actual)

        if guard_type == "node_state":
            key = str(guard.get("node") or "")
            expected = str(guard.get("expected") or "completed")
            actual = node_statuses.get(key)
            actual_value = actual.value if isinstance(actual, NodeStatus) else actual
            if expected == "started":
                started_states = {NodeStatus.RUNNING.value, NodeStatus.COMPLETED.value, NodeStatus.SKIPPED.value}
                return self._result(guard, actual_value in started_states, guard_type, key, expected, actual_value)
            return self._result(guard, actual_value == expected, guard_type, key, expected, actual_value)

        if guard_type == "service_state":
            system_name = str(guard.get("system") or "")
            field_name = str(guard.get("field") or "health_state")
            expected = guard.get("expected", "running")
            scope = str(guard.get("scope") or "all_instances")
            instances = self.registry.instances_by_system.get(system_name) or []
            values = [getattr(instance, field_name, None) for instance in instances]
            if not values:
                return self._result(guard, False, guard_type, system_name, expected, None, "no service instances")
            passed = all(value == expected for value in values) if scope == "all_instances" else any(value == expected for value in values)
            actual = values if len(values) > 1 else values[0]
            return self._result(guard, passed, guard_type, system_name, expected, actual)

        passed = not self.context.strict_unknown_guards
        return self._result(
            guard,
            passed,
            guard_type or "unknown",
            str(guard.get("check") or guard),
            "known guard",
            "unknown guard",
            "unknown guard passed in compatibility mode" if passed else "unknown guard",
        )

    def _result(
        self,
        guard: dict[str, Any],
        passed: bool,
        guard_type: str,
        key: str,
        expected: Any,
        actual: Any,
        reason: str | None = None,
    ) -> GuardResult:
        reason = reason or (f"{key} == {expected}" if passed else f"{key}: expected {expected}, got {actual}")
        return GuardResult(
            passed=passed,
            guard_type=guard_type,
            key=key,
            expected=expected,
            actual=actual,
            reason=reason,
            raw=guard,
        )


def normalize_runtime_guards(raw_guards: list[Any]) -> list[dict[str, Any]]:
    guards: list[dict[str, Any]] = []
    for item in raw_guards:
        if not isinstance(item, dict):
            continue
        if item.get("type"):
            guards.append(dict(item))
            continue
        if item.get("service"):
            guards.append({
                "type": "service_state",
                "system": item.get("service"),
                "field": "health_state",
                "expected": item.get("check", "running"),
                "scope": item.get("scope", "all_instances"),
                "legacy": True,
            })
            continue
        if item.get("interlock"):
            guards.append({
                "type": "interlock_state",
                "key": item.get("interlock"),
                "expected": item.get("check", "normal"),
                "legacy": True,
            })
            continue
        if item.get("node"):
            guards.append({
                "type": "node_state",
                "node": item.get("node"),
                "expected": item.get("check", "completed"),
                "legacy": True,
            })
            continue
        check = item.get("check")
        if check in GuardEvaluator.LEGACY_CONTEXT_FLAGS:
            key, expected = GuardEvaluator.LEGACY_CONTEXT_FLAGS[str(check)]
            guards.append({
                "type": "context_flag",
                "key": key,
                "expected": expected,
                "legacy": True,
                "check": check,
            })
            continue
        guards.append({**item, "type": "unknown", "legacy": True})
    return guards
