"""Sequential orchestration and joint gates for multiple subsystem machines."""

from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from uuid import uuid4

import yaml

from .subsystem_fsm import CompletionEvidence, StateMachineError, StateSnapshot, SubsystemStateMachine


class CompositeExperimentError(ValueError):
    """A composite flow or one of its gates cannot progress."""


@dataclass(frozen=True)
class CompositeNode:
    id: str
    phase: str
    target: str | None = None
    action: str | None = None
    requires: dict[str, dict[str, str]] | None = None


@dataclass(frozen=True)
class CompositeDispatch:
    run_id: str
    node_id: str
    target: str
    action: str
    task_id: str


@dataclass(frozen=True)
class CompositeNodeResult:
    node_id: str
    status: str
    states: tuple[tuple[str, StateSnapshot], ...]
    reason: str = ""


@dataclass(frozen=True)
class CompositeSnapshot:
    run_id: str
    status: str
    active: CompositeDispatch | None
    node_states: tuple[tuple[str, str], ...]
    results: tuple[CompositeNodeResult, ...]


class CompositeSequentialExperiment:
    """Run several independent FSMs in sequence, with explicit AND gates."""

    def __init__(self, model: dict[str, Any], subsystems: dict[str, SubsystemStateMachine]):
        if model.get('kind') != 'CompositeSequentialExperiment':
            raise CompositeExperimentError('expected kind=CompositeSequentialExperiment')
        declared = model.get('systems') or {}
        if set(declared) != set(subsystems):
            raise CompositeExperimentError('model and subsystem bindings must have identical keys')
        for key, subsystem_id in declared.items():
            if subsystem_id != subsystems[key].model['system']['id']:
                raise CompositeExperimentError(f'system binding mismatch for {key}')
        nodes = []
        ids = set()
        for raw in model.get('nodes') or []:
            node = CompositeNode(
                id=str(raw.get('id') or ''), phase=str(raw.get('phase') or ''),
                target=raw.get('target'), action=raw.get('action'),
                requires=deepcopy(raw.get('requires')),
            )
            if not node.id or not node.phase or node.id in ids:
                raise CompositeExperimentError('nodes need unique id and phase')
            if node.requires:
                if node.target or node.action:
                    raise CompositeExperimentError(f'gate {node.id} cannot target an action')
                unknown = set(node.requires) - set(subsystems)
                if unknown:
                    raise CompositeExperimentError(f'gate {node.id} references unknown systems {unknown}')
            elif not node.target or not node.action or node.target not in subsystems:
                raise CompositeExperimentError(f'action node {node.id} needs a known target and action')
            elif node.action not in {a['id'] for a in subsystems[node.target].model['actions']}:
                raise CompositeExperimentError(f'unknown action {node.target}.{node.action}')
            ids.add(node.id)
            nodes.append(node)
        if not nodes:
            raise CompositeExperimentError('flow must contain nodes')
        self.nodes = tuple(nodes)
        self.subsystems = subsystems
        self._run_id = uuid4().hex
        self._status = 'idle'
        self._index = 0
        self._active: CompositeDispatch | None = None
        self._node_states = {n.id: 'waiting' for n in nodes}
        self._results: list[CompositeNodeResult] = []

    def snapshot(self) -> CompositeSnapshot:
        return CompositeSnapshot(self._run_id, self._status, self._active,
                                 tuple(self._node_states.items()), tuple(self._results))

    def dispatch_next(self) -> CompositeDispatch:
        if self._status in {'succeeded', 'failed'}:
            raise CompositeExperimentError(f'experiment already {self._status}')
        if self._active is not None:
            raise CompositeExperimentError('wait for the active node result')
        while self._index < len(self.nodes):
            node = self.nodes[self._index]
            if node.requires is not None:
                self._check_gate(node)
                states = tuple((key, self.subsystems[key].snapshot()) for key in sorted(node.requires))
                self._finish('succeeded', states, 'all joint preconditions satisfied')
                continue
            try:
                started = self.subsystems[node.target].start(node.action)  # type: ignore[index]
            except StateMachineError as exc:
                self._finish('failed', ((node.target, self.subsystems[node.target].snapshot()),), str(exc))  # type: ignore[index]
                raise CompositeExperimentError(f'{node.id}: command rejected: {exc}') from exc
            dispatch = CompositeDispatch(self._run_id, node.id, node.target, node.action, started.task_id)  # type: ignore[arg-type]
            self._active = dispatch
            self._node_states[node.id] = 'running'
            self._status = 'running'
            return dispatch
        self._status = 'succeeded'
        raise CompositeExperimentError('experiment has no dispatchable nodes')

    def complete(self, dispatch: CompositeDispatch, *, success: bool, evidence: CompletionEvidence | None = None) -> CompositeNodeResult:
        if type(success) is not bool or self._active != dispatch:
            raise CompositeExperimentError('result does not belong to the active task')
        if success and not isinstance(evidence, CompletionEvidence):
            raise CompositeExperimentError('success requires completion evidence')
        subsystem = self.subsystems[dispatch.target]
        try:
            state = (subsystem.complete_success(dispatch.action, task_id=dispatch.task_id, evidence=evidence)
                     if success else subsystem.complete_failure(dispatch.action, task_id=dispatch.task_id))
        except StateMachineError as exc:
            return self._finish('failed', ((dispatch.target, subsystem.snapshot()),), str(exc))
        status = 'succeeded' if success and state.task_state == 'succeeded' else 'failed'
        return self._finish(status, ((dispatch.target, state),), '' if status == 'succeeded' else state.current_state)

    def report_exception(self, target: str, exception_id: str) -> CompositeNodeResult:
        if target not in self.subsystems or self._status in {'succeeded', 'failed'}:
            raise CompositeExperimentError('invalid target or terminal experiment')
        state = self.subsystems[target].report_exception(exception_id)
        return self._finish('failed', ((target, state),), state.current_state)

    def _check_gate(self, node: CompositeNode) -> None:
        mismatches = []
        for target, requirements in (node.requires or {}).items():
            state = self.subsystems[target].snapshot()
            for field, expected in requirements.items():
                if getattr(state, field) != expected:
                    mismatches.append(f'{target}.{field}={getattr(state, field)!r}, expected {expected!r}')
        if mismatches:
            states = tuple((key, self.subsystems[key].snapshot()) for key in sorted(node.requires or {}))
            self._finish('failed', states, 'joint gate blocked: ' + '; '.join(mismatches))
            raise CompositeExperimentError(f'{node.id}: joint gate blocked')

    def _finish(self, status: str, states: tuple[tuple[str, StateSnapshot], ...], reason: str) -> CompositeNodeResult:
        node = self.nodes[self._index]
        result = CompositeNodeResult(node.id, status, states, reason)
        self._results.append(result)
        self._node_states[node.id] = status
        self._active = None
        if status == 'failed':
            self._status = 'failed'
        else:
            self._index += 1
            self._status = 'succeeded' if self._index == len(self.nodes) else 'running'
        return result


def load_laser_joint_experiment(subsystems: dict[str, SubsystemStateMachine]) -> CompositeSequentialExperiment:
    path = Path(__file__).resolve().parent / 'models' / 'laser-joint-experiment.yaml'
    with path.open(encoding='utf-8') as stream:
        return CompositeSequentialExperiment(yaml.safe_load(stream), subsystems)
