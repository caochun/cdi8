"""Joint flow combining a fan-out subsystem group and a single subsystem."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from dataclasses import dataclass
from pathlib import Path
import time
from typing import Any
from uuid import uuid4

import yaml

from .subsystem_fsm import CompletionEvidence, StateMachineError, StateSnapshot, SubsystemStateMachine


class JointFanoutExperimentError(ValueError):
    """A joint fan-out flow or result cannot be accepted."""


@dataclass(frozen=True)
class JointFanoutNode:
    id: str
    target: str | None
    action: str | None
    phase: str
    is_gate: bool = False
    requires: dict[str, dict[str, Any]] | None = None
    timeout_seconds: float | None = None


@dataclass(frozen=True)
class JointFanoutDispatch:
    run_id: str
    node_id: str
    action: str
    task_ids: tuple[tuple[str, str], ...]


@dataclass(frozen=True)
class JointFanoutResult:
    node_id: str
    status: str
    states: tuple[tuple[str, StateSnapshot], ...]
    completed_targets: tuple[str, ...] = ()
    failed_target: str | None = None
    reason: str = ""


@dataclass(frozen=True)
class JointFanoutSnapshot:
    run_id: str
    status: str
    active: JointFanoutDispatch | None
    node_states: tuple[tuple[str, str], ...]
    results: tuple[JointFanoutResult, ...]
    interlock_triggered: bool = False
    compensation_required: bool = False


class JointFanoutSequentialExperiment:
    """Sequential nodes; seed_source nodes fan out, shg_injector is single."""

    def __init__(self, model: dict[str, Any], machines: dict[str, dict[str, SubsystemStateMachine]]):
        if model.get('kind') != 'JointFanoutSequentialExperiment':
            raise JointFanoutExperimentError('expected kind=JointFanoutSequentialExperiment')
        systems = model.get('systems') or {}
        if set(systems) != set(machines):
            raise JointFanoutExperimentError('model and machine bindings must match')
        self.machines = {name: dict(instances) for name, instances in machines.items()}
        self.targets: dict[str, tuple[str, ...]] = {}
        for name, config in systems.items():
            if name not in self.machines or not self.machines[name]:
                raise JointFanoutExperimentError(f'missing machine instances for {name}')
            sample = next(iter(self.machines[name].values()))
            if config.get('model_id') != sample.model['system']['id']:
                raise JointFanoutExperimentError(f'model binding mismatch for {name}')
            mode = config.get('mode')
            if mode == 'fanout':
                values = tuple(config.get('instances') or ())
            elif mode == 'single':
                values = (str(config.get('instance') or ''),)
            else:
                raise JointFanoutExperimentError(f'unsupported target mode for {name}')
            if not values or len(set(values)) != len(values):
                raise JointFanoutExperimentError(f'invalid target instances for {name}')
            if set(values) != set(self.machines[name]):
                raise JointFanoutExperimentError(f'model and machine instances must match for {name}')
            self.targets[name] = values
        nodes = []
        ids = set()
        for raw in model.get('nodes') or []:
            is_gate = raw.get('type') == 'gate'
            node = JointFanoutNode(
                id=str(raw.get('id') or ''), target=None if is_gate else str(raw.get('target') or ''),
                action=None if is_gate else str(raw.get('action') or ''), phase=str(raw.get('phase') or ''),
                is_gate=is_gate, requires=deepcopy(raw.get('requires')) if is_gate else None,
                timeout_seconds=float(raw['timeout_seconds']) if raw.get('timeout_seconds') is not None else None,
            )
            if not node.id or not node.phase or node.id in ids:
                raise JointFanoutExperimentError('nodes need unique id and phase')
            if is_gate:
                self._validate_gate_shape(node)
            elif node.target not in self.targets:
                raise JointFanoutExperimentError(f'unknown node target: {node.target}')
            elif node.action not in {a['id'] for a in self.machines[node.target][self.targets[node.target][0]].model['actions']}:
                raise JointFanoutExperimentError(f'unknown action {node.target}.{node.action}')
            ids.add(node.id)
            nodes.append(node)
        if not nodes:
            raise JointFanoutExperimentError('flow must contain nodes')
        self.nodes = tuple(nodes)
        self._run_id = uuid4().hex
        self._status = 'idle'
        self._index = 0
        self._active: JointFanoutDispatch | None = None
        self._pending: dict[str, str] = {}
        self._completed: dict[str, StateSnapshot] = {}
        self._node_states = {node.id: 'waiting' for node in nodes}
        self._results: list[JointFanoutResult] = []
        runtime = model.get('runtime') or {}
        self._default_timeout_seconds = float(runtime.get('default_timeout_seconds', 30.0))
        self._compensation_plan = tuple(deepcopy(runtime.get('compensation') or ()))
        self._active_started_at: float | None = None
        self._interlock_triggered = False
        self._compensation_required = False
        self._compensation_results: list[dict[str, Any]] = []

    def snapshot(self) -> JointFanoutSnapshot:
        return JointFanoutSnapshot(self._run_id, self._status, self._active,
                                   tuple(self._node_states.items()), tuple(self._results),
                                   self._interlock_triggered, self._compensation_required)

    def dispatch_next(self) -> JointFanoutDispatch | None:
        if self._status in {'succeeded', 'failed'}:
            raise JointFanoutExperimentError(f'experiment already {self._status}')
        if self._active is not None:
            raise JointFanoutExperimentError('wait for active result')
        while self._index < len(self.nodes):
            node = self.nodes[self._index]
            if node.is_gate:
                result = self._evaluate_gate(node)
                if result.status == 'failed':
                    raise JointFanoutExperimentError(f'{node.id}: {result.reason}')
                continue
            targets = self.targets[node.target]  # type: ignore[index]
            machine_items = [(f'{node.target}:{instance}', self.machines[node.target][instance]) for instance in targets]  # type: ignore[index]
            try:
                for _instance, machine in machine_items:
                    machine.can_start(node.action)  # type: ignore[arg-type]
                with ThreadPoolExecutor(max_workers=len(machine_items)) as executor:
                    started = tuple(executor.map(lambda item: (item[0], item[1].start(node.action)), machine_items))  # type: ignore[arg-type]
            except StateMachineError as exc:
                self._node_states[node.id] = 'failed'
                self._status = 'failed'
                raise JointFanoutExperimentError(f'{node.id}: dispatch failed: {exc}') from exc
            task_ids = tuple((target, snapshot.task_id or '') for target, snapshot in started)
            self._active = JointFanoutDispatch(self._run_id, node.id, node.action, task_ids)
            self._active_started_at = time.monotonic()
            self._pending = dict(task_ids)
            self._completed = {}
            self._node_states[node.id] = 'running'
            self._status = 'running'
            return self._active

    def check_timeout(self, now: float | None = None) -> JointFanoutResult | None:
        """Evaluate the active node deadline; ``now`` makes tests deterministic."""
        if self._active is None or self._active_started_at is None:
            return None
        node = self.nodes[self._index]
        timeout = node.timeout_seconds if node.timeout_seconds is not None else self._default_timeout_seconds
        current = time.monotonic() if now is None else now
        if current - self._active_started_at < timeout:
            return None
        target = next(iter(self._pending), None)
        if target is None:
            return None
        subsystem_name, instance = target.split(':', 1)
        state = self.machines[subsystem_name][instance].report_exception('execution_timeout')
        return self._fail(target, state.current_state)

    def inject_fault(self, target: str, exception_id: str = 'fault_lock') -> JointFanoutResult:
        """Inject a declared subsystem exception into an active target."""
        if self._active is None or target not in self._pending:
            raise JointFanoutExperimentError('fault target is not active')
        subsystem_name, instance = target.split(':', 1)
        state = self.machines[subsystem_name][instance].report_exception(exception_id)
        return self._fail(target, state.current_state)

    def trigger_interlock(self, reason: str = 'safety_interlock') -> JointFanoutResult:
        """Trip the global interlock and make compensation mandatory."""
        self._interlock_triggered = True
        self._compensation_required = True
        if self._active is None:
            states = tuple((f'{name}:{instance}', self.machines[name][instance].snapshot())
                           for name, targets in self.targets.items() for instance in targets)
            result = JointFanoutResult('interlock', 'failed', states, reason=reason)
            self._results.append(result)
            self._status = 'failed'
            return result
        target = next(iter(self._pending))
        subsystem_name, instance = target.split(':', 1)
        self.machines[subsystem_name][instance].report_exception('fault_lock')
        return self._fail(target, reason)

    def run_compensation(self, evidence_by_target: dict[str, CompletionEvidence]) -> tuple[dict[str, Any], ...]:
        """Execute configured compensation actions by priority.

        Compensation does not change the failed flow result; it only returns
        devices to the declared safe state and records each verified action.
        """
        if not self._compensation_required:
            raise JointFanoutExperimentError('no compensation is required')
        records: list[dict[str, Any]] = []
        for item in sorted(self._compensation_plan, key=lambda value: int(value.get('priority', 0))):
            target_system = str(item.get('target'))
            action = str(item.get('action'))
            for instance in self.targets.get(target_system, ()):
                target = f'{target_system}:{instance}'
                evidence = evidence_by_target.get(target)
                if evidence is None:
                    raise JointFanoutExperimentError(f'missing compensation evidence for {target}')
                machine = self.machines[target_system][instance]
                try:
                    started = machine.start(action)
                    state = machine.complete_success(task_id=started.task_id, evidence=evidence)
                except StateMachineError as exc:
                    raise JointFanoutExperimentError(f'compensation failed for {target}: {exc}') from exc
                record = {'target': target, 'action': action,
                          'priority': int(item.get('priority', 0)),
                          'status': 'succeeded' if state.task_state == 'succeeded' else 'failed'}
                records.append(record)
                if record['status'] != 'succeeded':
                    raise JointFanoutExperimentError(f'compensation failed for {target}')
        self._compensation_results.extend(records)
        self._compensation_required = False
        return tuple(records)
        self._status = 'succeeded'
        return None

    def complete(self, dispatch: JointFanoutDispatch, target: str, *, success: bool,
                 evidence: CompletionEvidence | None = None) -> JointFanoutResult | None:
        if self._active != dispatch or target not in self._pending:
            raise JointFanoutExperimentError('result does not belong to active target')
        if success and not isinstance(evidence, CompletionEvidence):
            raise JointFanoutExperimentError('success requires completion evidence')
        subsystem_name, instance = target.split(':', 1)
        machine = self.machines[subsystem_name][instance]
        task_id = self._pending[target]
        try:
            state = (machine.complete_success(dispatch.action, task_id=task_id, evidence=evidence)
                     if success else machine.complete_failure(dispatch.action, task_id=task_id))
        except StateMachineError as exc:
            return self._fail(target, str(exc))
        self._pending.pop(target)
        self._completed[target] = state
        if not success or state.task_state != 'succeeded':
            return self._fail(target, state.current_state)
        if self._pending:
            return None
        states = tuple(self._completed.items())
        return self._finish('succeeded', states, tuple(self._completed), reason='all_success')

    def report_exception(self, target: str, exception_id: str) -> JointFanoutResult:
        if self._active is None or target not in self._pending:
            raise JointFanoutExperimentError('exception target is not active')
        subsystem_name, instance = target.split(':', 1)
        state = self.machines[subsystem_name][instance].report_exception(exception_id)
        return self._fail(target, state.current_state)

    def _validate_gate_shape(self, node: JointFanoutNode) -> None:
        if set(node.requires or {}) != set(self.targets):
            raise JointFanoutExperimentError(f'gate {node.id} must require every subsystem')
        for name, requirements in (node.requires or {}).items():
            if requirements.get('mode') not in {'all', 'one'}:
                raise JointFanoutExperimentError(f'gate {node.id}: invalid mode for {name}')
            for field in ('main_state', 'current_state', 'business_state'):
                if field not in requirements:
                    raise JointFanoutExperimentError(f'gate {node.id}: missing {name}.{field}')

    def _evaluate_gate(self, node: JointFanoutNode) -> JointFanoutResult:
        states = []
        mismatches = []
        for name, requirements in (node.requires or {}).items():
            target_mismatches = []
            for instance in self.targets[name]:
                state = self.machines[name][instance].snapshot()
                states.append((f'{name}:{instance}', state))
                instance_mismatches = []
                for field in ('main_state', 'current_state', 'business_state'):
                    if getattr(state, field) != requirements[field]:
                        instance_mismatches.append(f'{field}={getattr(state, field)!r}')
                if instance_mismatches:
                    target_mismatches.append(f'{name}:{instance}({", ".join(instance_mismatches)})')
            if requirements['mode'] == 'all':
                mismatches.extend(target_mismatches)
            elif requirements['mode'] == 'one' and len(target_mismatches) == len(self.targets[name]):
                mismatches.extend(target_mismatches)
        if mismatches:
            result = self._finish('failed', tuple(states), (), 'joint gate blocked: ' + '; '.join(mismatches))
            return result
        return self._finish('succeeded', tuple(states), (), 'all joint fan-out preconditions satisfied')

    def _fail(self, target: str, reason: str) -> JointFanoutResult:
        for other in tuple(self._pending):
            if other == target:
                continue
            subsystem_name, instance = other.split(':', 1)
            self.machines[subsystem_name][instance].cancel_active_task('joint_peer_failed')
        states = tuple((f'{name}:{instance}', self.machines[name][instance].snapshot())
                       for name, targets in self.targets.items() for instance in targets)
        return self._finish('failed', states, tuple(self._completed), reason, target)

    def _finish(self, status: str, states: tuple[tuple[str, StateSnapshot], ...],
                completed: tuple[str, ...], reason: str, failed_target: str | None = None) -> JointFanoutResult:
        node = self.nodes[self._index]
        result = JointFanoutResult(node.id, status, states, completed, failed_target, reason)
        self._results.append(result)
        self._node_states[node.id] = status
        self._active = None
        self._active_started_at = None
        self._pending = {}
        if status == 'failed':
            self._status = 'failed'
        else:
            self._index += 1
            self._status = 'succeeded' if self._index == len(self.nodes) else 'running'
        return result


def load_laser_joint_fanout_experiment(
    machines: dict[str, dict[str, SubsystemStateMachine]],
) -> JointFanoutSequentialExperiment:
    path = Path(__file__).resolve().parent / 'models' / 'laser-joint-fanout-experiment.yaml'
    with path.open(encoding='utf-8') as stream:
        return JointFanoutSequentialExperiment(yaml.safe_load(stream), machines)
