"""Excel-derived subsystem transitions with explicit simulation feedback."""

from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Mapping
from uuid import uuid4

import yaml


class StateMachineError(ValueError):
    """An action, event or task result is not applicable."""


@dataclass(frozen=True)
class CompletionEvidence:
    callback_success: bool
    observed_state: str
    conditions: Mapping[str, bool] = field(default_factory=dict)


@dataclass(frozen=True)
class StateSnapshot:
    main_state: str
    current_state: str
    business_state: str
    key_state: str
    key_state_description: str
    state_definition: str
    task_state: str
    active_action: str | None = None
    task_id: str | None = None


@dataclass(frozen=True)
class TransitionRecord:
    event: str
    action_id: str | None
    outcome: str | None
    before: StateSnapshot
    after: StateSnapshot


@dataclass
class SubsystemStateMachine:
    model: dict[str, Any]
    _state: dict[str, Any] = field(init=False)
    _active_action: str | None = field(default=None, init=False)
    _task_id: str | None = field(default=None, init=False)
    _history: list[TransitionRecord] = field(default_factory=list, init=False)

    def __post_init__(self) -> None:
        self.model = deepcopy(self.model)
        self._state = deepcopy(self.model['system']['initial'])
        StateSnapshot(**self._state)  # Validate snapshot shape before use.
        self._validate_state(self._state)
        ids = set()
        for section in ('actions', 'exceptions'):
            for entry in self.model[section]:
                if entry['id'] in ids:
                    raise StateMachineError(f"duplicate entry: {entry['id']}")
                ids.add(entry['id'])
                for key in ('key_state_description', 'state_definition', 'state_condition', 'flow_impact'):
                    if not entry.get(key):
                        raise StateMachineError(f"{entry['id']} missing {key}")
                if section == 'actions':
                    for outcome in ('success', 'failure'):
                        self._validate_state({**self._state, **entry[outcome]})
                    if not entry.get('completion', {}).get('observed_states'):
                        raise StateMachineError(f"{entry['id']} missing completion criteria")

    @classmethod
    def from_yaml(cls, path: str | Path) -> 'SubsystemStateMachine':
        with Path(path).open(encoding='utf-8') as stream:
            return cls(yaml.safe_load(stream))

    @property
    def system_name(self) -> str:
        return self.model['system']['name']

    @property
    def history(self) -> tuple[TransitionRecord, ...]:
        return tuple(self._history)

    def snapshot(self) -> StateSnapshot:
        return StateSnapshot(**self._state, active_action=self._active_action, task_id=self._task_id)

    def start(self, action_id: str) -> StateSnapshot:
        action = self._entry('actions', action_id)
        self._check_precondition(action)
        if self._active_action is not None:
            if not action.get('preempts_active_task', False):
                raise StateMachineError(f'action already executing: {self._active_action}')
            before = self.snapshot()
            interrupted = self._active_action
            self._state['task_state'] = 'cancelled'
            self._active_action = None
            self._record('task_cancelled', interrupted, 'preempted', before)
        before = self.snapshot()
        self._active_action = action_id
        self._task_id = uuid4().hex
        self._state.update(task_state='executing', key_state_description=action['key_state_description'],
                           state_definition=action['state_definition'])
        self._record('action_started', action_id, None, before)
        return self.snapshot()

    def complete_success(
        self, action_id: str | None = None, *, task_id: str,
        evidence: CompletionEvidence,
    ) -> StateSnapshot:
        action = self._active_definition(action_id, task_id)
        criteria = action['completion']
        valid = (
            isinstance(evidence, CompletionEvidence)
            and isinstance(evidence.conditions, Mapping)
            and evidence.callback_success is True
            and evidence.observed_state in criteria['observed_states']
            and all(evidence.conditions.get(key) is True for key in criteria['required_conditions'])
        )
        # A success callback without matching observations cannot assert readiness.
        return self._complete(action, 'success' if valid else 'failure',
                              'verified' if valid else 'invalid_feedback')

    def complete_failure(self, action_id: str | None = None, *, task_id: str) -> StateSnapshot:
        action = self._active_definition(action_id, task_id)
        return self._complete(action, 'failure', 'reported_failure')

    def report_exception(self, exception_id: str) -> StateSnapshot:
        exception = self._entry('exceptions', exception_id)
        return self._enter_exception(exception['key_state'], exception['state_definition'],
                                     'exception_reported', exception_id)

    def invalidate(self, event_id: str) -> StateSnapshot:
        """External monitoring reports loss of an L-column validity condition."""
        event = self._entry('validity_loss_events', event_id)
        for field in ('current_state', 'business_state', 'main_state'):
            allowed = event.get(f'applies_to_{field}')
            if allowed is not None and self._state[field] not in allowed:
                raise StateMachineError(f'{event_id} does not apply to current {field}')
        return self._enter_exception(event['current_state'], '异常处置', 'validity_lost', event_id)

    def recover_from_exception(self, *, conditions: Mapping[str, bool]) -> StateSnapshot:
        if self._active_action is not None or self._state['main_state'] != '异常':
            raise StateMachineError('recovery requires an inactive subsystem in 异常')
        policy = self.model['exception_policy']
        if not all(conditions.get(key) is True for key in policy['recovery_required_conditions']):
            raise StateMachineError('recovery requires cause clearance and verified safe reset')
        before = self.snapshot()
        self._state.update(policy['recovery_target'], task_state='idle')
        self._record('exception_recovered', None, 'verified_reset', before)
        return self.snapshot()

    def _active_definition(self, action_id: str | None, task_id: str) -> dict[str, Any]:
        if self._active_action is None or task_id != self._task_id:
            raise StateMachineError('result does not belong to the active task')
        if action_id is not None and action_id != self._active_action:
            raise StateMachineError(f'executing action is {self._active_action}, not {action_id}')
        return self._entry('actions', self._active_action)

    def _complete(self, action: dict[str, Any], outcome: str, reason: str) -> StateSnapshot:
        before = self.snapshot()
        self._state.update(action[outcome], state_definition=action['state_definition'],
                           key_state_description=action['key_state_description'],
                           task_state='succeeded' if outcome == 'success' else 'failed')
        self._active_action = None
        self._record('action_completed', action['id'], reason, before)
        return self.snapshot()

    def _enter_exception(self, state: str, definition: str, event: str, reason: str) -> StateSnapshot:
        before = self.snapshot()
        interrupted = self._active_action
        policy = self.model['exception_policy']
        self._state.update(main_state=policy['main_state'], business_state=policy['business_state'],
                           current_state=state, key_state=state, state_definition=definition,
                           key_state_description=state,
                           task_state=policy['task_state'])
        self._active_action = None
        self._record(event, interrupted, reason, before)
        return self.snapshot()

    def _check_precondition(self, action: dict[str, Any]) -> None:
        for field, allowed in action['precondition'].items():
            if self._state[field] not in allowed:
                raise StateMachineError(f"{action['id']} requires {field} in {allowed!r}, "
                                        f"current value is {self._state[field]!r}")

    def _entry(self, section: str, entry_id: str) -> dict[str, Any]:
        for entry in self.model[section]:
            if entry['id'] == entry_id:
                return entry
        raise StateMachineError(f'unknown {section} entry: {entry_id}')

    def _validate_state(self, state: dict[str, Any]) -> None:
        for key in ('main', 'business', 'task'):
            if state[f'{key}_state'] not in self.model['states'][key]:
                raise StateMachineError(f'invalid {key}_state')

    def _record(self, event: str, action: str | None, outcome: str | None, before: StateSnapshot) -> None:
        self._history.append(TransitionRecord(event, action, outcome, before, self.snapshot()))


def load_seed_source_state_machine(model_path: str | Path | None = None) -> SubsystemStateMachine:
    path = model_path or Path(__file__).resolve().parent / 'models' / 'excel-seed-source-state-machine.yaml'
    return SubsystemStateMachine.from_yaml(path)
