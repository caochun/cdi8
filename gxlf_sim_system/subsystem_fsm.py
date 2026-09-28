"""Declarative subsystem state machine used by the Excel-derived simulator models."""

from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml


class StateMachineError(ValueError):
    """Raised when an action or event cannot be applied to the current state."""


@dataclass(frozen=True)
class StateSnapshot:
    main_state: str
    current_state: str
    business_state: str
    key_state: str
    task_state: str
    active_action: str | None = None


@dataclass(frozen=True)
class TransitionRecord:
    event: str
    action_id: str | None
    outcome: str | None
    before: StateSnapshot
    after: StateSnapshot


@dataclass
class SubsystemStateMachine:
    """Execute one Excel-derived subsystem model without owning flow orchestration."""

    model: dict[str, Any]
    _state: dict[str, Any] = field(init=False)
    _active_action: str | None = field(default=None, init=False)
    _history: list[TransitionRecord] = field(default_factory=list, init=False)

    def __post_init__(self) -> None:
        system = self.model.get("system") or {}
        initial = deepcopy(system.get("initial") or {})
        required = {"main_state", "current_state", "business_state", "key_state", "task_state"}
        missing = required - set(initial)
        if missing:
            raise StateMachineError(f"initial state missing fields: {sorted(missing)}")
        self._state = initial
        self._validate_state(self._state)

    @classmethod
    def from_yaml(cls, path: str | Path) -> "SubsystemStateMachine":
        with Path(path).open("r", encoding="utf-8") as fh:
            model = yaml.safe_load(fh) or {}
        return cls(model)

    @property
    def system_name(self) -> str:
        return str((self.model.get("system") or {}).get("name") or "")

    @property
    def history(self) -> tuple[TransitionRecord, ...]:
        return tuple(self._history)

    def snapshot(self) -> StateSnapshot:
        return StateSnapshot(**self._state, active_action=self._active_action)

    def start(self, action_id: str) -> StateSnapshot:
        if self._active_action is not None:
            raise StateMachineError(f"action already executing: {self._active_action}")
        action = self._action(action_id)
        self._check_precondition(action)
        before = self.snapshot()
        self._active_action = action_id
        self._state["task_state"] = "executing"
        after = self.snapshot()
        self._record("action_started", action_id, None, before, after)
        return after

    def complete_success(self, action_id: str | None = None) -> StateSnapshot:
        action = self._active_action if action_id is None else action_id
        if action is None:
            raise StateMachineError("no action is executing")
        if self._active_action != action:
            raise StateMachineError(f"executing action is {self._active_action!r}, not {action!r}")
        definition = self._action(action)
        return self._complete(action, "success", definition["success"])

    def complete_failure(self, action_id: str | None = None) -> StateSnapshot:
        action = self._active_action if action_id is None else action_id
        if action is None:
            raise StateMachineError("no action is executing")
        if self._active_action != action:
            raise StateMachineError(f"executing action is {self._active_action!r}, not {action!r}")
        definition = self._action(action)
        return self._complete(action, "failure", definition["failure"])

    def report_exception(self, exception_id: str) -> StateSnapshot:
        if self._active_action is not None:
            raise StateMachineError("cannot report an independent exception while an action is executing")
        exception = self._exception(exception_id)
        before = self.snapshot()
        key_state = str(exception["key_state"])
        self._state.update(
            main_state=str((self.model.get("exception_policy") or {}).get("main_state", "异常")),
            current_state=key_state,
            key_state=key_state,
            business_state=str((self.model.get("exception_policy") or {}).get("business_state", "异常")),
            task_state="failed",
        )
        after = self.snapshot()
        self._record("exception_reported", None, exception_id, before, after)
        return after

    def recover_from_exception(self) -> StateSnapshot:
        if self._active_action is not None:
            raise StateMachineError("cannot recover while an action is executing")
        if self._state["main_state"] != "异常":
            raise StateMachineError("recovery is only valid from main_state=异常")
        target = (self.model.get("exception_policy") or {}).get("recovery_target") or {}
        before = self.snapshot()
        self._state.update(target, task_state="idle")
        after = self.snapshot()
        self._record("exception_recovered", None, "recovery", before, after)
        return after

    def _complete(self, action_id: str, outcome: str, result: dict[str, Any]) -> StateSnapshot:
        before = self.snapshot()
        self._state.update(result, task_state="succeeded" if outcome == "success" else "failed")
        self._active_action = None
        after = self.snapshot()
        self._record("action_completed", action_id, outcome, before, after)
        return after

    def _check_precondition(self, action: dict[str, Any]) -> None:
        precondition = action.get("precondition") or {}
        for field, expected in precondition.items():
            allowed = expected if isinstance(expected, list) else [expected]
            actual = self._state.get(field)
            if actual not in allowed:
                raise StateMachineError(
                    f"{action.get('id')} requires {field} in {allowed!r}, current value is {actual!r}"
                )

    def _action(self, action_id: str) -> dict[str, Any]:
        for action in self.model.get("actions") or []:
            if action.get("id") == action_id:
                return action
        raise StateMachineError(f"unknown action: {action_id}")

    def _exception(self, exception_id: str) -> dict[str, Any]:
        for exception in self.model.get("exceptions") or []:
            if exception.get("id") == exception_id:
                return exception
        raise StateMachineError(f"unknown exception: {exception_id}")

    def _validate_state(self, state: dict[str, Any]) -> None:
        states = self.model.get("states") or {}
        for field, values in states.items():
            state_field = {
                "main": "main_state",
                "business": "business_state",
                "task": "task_state",
            }.get(field, field)
            if state.get(state_field) not in values:
                raise StateMachineError(
                    f"invalid initial {state_field}: {state.get(state_field)!r}; expected one of {values!r}"
                )

    def _record(
        self,
        event: str,
        action_id: str | None,
        outcome: str | None,
        before: StateSnapshot,
        after: StateSnapshot,
    ) -> None:
        self._history.append(TransitionRecord(event, action_id, outcome, before, after))


def load_seed_source_state_machine(model_path: str | Path | None = None) -> SubsystemStateMachine:
    if model_path is None:
        model_path = Path(__file__).resolve().parent / "models" / "excel-seed-source-state-machine.yaml"
    return SubsystemStateMachine.from_yaml(model_path)
