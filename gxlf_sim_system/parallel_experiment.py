"""Parallel fan-out orchestration with all-success aggregation."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from uuid import uuid4

import yaml

from .subsystem_fsm import CompletionEvidence, StateMachineError, StateSnapshot, SubsystemStateMachine


class ParallelExperimentError(ValueError):
    """A fan-out flow or one of its grouped task results is invalid."""


@dataclass(frozen=True)
class FanoutNode:
    id: str
    action: str
    phase: str


@dataclass(frozen=True)
class FanoutDispatch:
    run_id: str
    node_id: str
    action: str
    task_ids: tuple[tuple[str, str], ...]


@dataclass(frozen=True)
class FanoutNodeResult:
    node_id: str
    status: str
    states: tuple[tuple[str, StateSnapshot], ...]
    completed_targets: tuple[str, ...]
    failed_target: str | None = None
    reason: str = ""


@dataclass(frozen=True)
class FanoutSnapshot:
    run_id: str
    status: str
    active: FanoutDispatch | None
    node_states: tuple[tuple[str, str], ...]
    results: tuple[FanoutNodeResult, ...]


class ParallelFanoutExperiment:
    """Execute each node against all configured instances concurrently.

    `all_success` is deliberately the only aggregation policy in this step:
    every target must report a verified success before the node advances.
    """

    def __init__(self, model: dict[str, Any], instances: dict[str, SubsystemStateMachine]):
        if model.get('kind') != 'ParallelFanoutExperiment':
            raise ParallelExperimentError('expected kind=ParallelFanoutExperiment')
        if model.get('aggregation') != 'all_success':
            raise ParallelExperimentError('only aggregation=all_success is supported')
        declared = list(model.get('instances') or [])
        if not declared or len(set(declared)) != len(declared) or set(declared) != set(instances):
            raise ParallelExperimentError('model and instance bindings must match exactly')
        nodes = []
        ids = set()
        for raw in model.get('nodes') or []:
            node = FanoutNode(str(raw.get('id') or ''), str(raw.get('action') or ''), str(raw.get('phase') or ''))
            if not node.id or not node.action or not node.phase or node.id in ids:
                raise ParallelExperimentError('nodes need unique id, action and phase')
            if any(node.action not in {a['id'] for a in machine.model['actions']} for machine in instances.values()):
                raise ParallelExperimentError(f'action {node.action} is not supported by every instance')
            ids.add(node.id)
            nodes.append(node)
        if not nodes:
            raise ParallelExperimentError('flow must contain nodes')
        self.nodes = tuple(nodes)
        self.instances = dict(instances)
        self._run_id = uuid4().hex
        self._status = 'idle'
        self._index = 0
        self._active: FanoutDispatch | None = None
        self._pending: dict[str, str] = {}
        self._completed: dict[str, StateSnapshot] = {}
        self._node_states = {node.id: 'waiting' for node in nodes}
        self._results: list[FanoutNodeResult] = []

    def snapshot(self) -> FanoutSnapshot:
        states = tuple((key, self.instances[key].snapshot()) for key in self.instances)
        return FanoutSnapshot(self._run_id, self._status, self._active,
                              tuple(self._node_states.items()), tuple(self._results))

    def dispatch_next(self) -> FanoutDispatch:
        if self._status in {'succeeded', 'failed'}:
            raise ParallelExperimentError(f'experiment already {self._status}')
        if self._active is not None:
            raise ParallelExperimentError('wait for every active target result')
        node = self.nodes[self._index]

        try:
            for machine in self.instances.values():
                machine.can_start(node.action)
        except StateMachineError as exc:
            self._node_states[node.id] = 'failed'
            self._status = 'failed'
            raise ParallelExperimentError(f'{node.id}: fan-out preflight failed: {exc}') from exc

        def start(item: tuple[str, SubsystemStateMachine]) -> tuple[str, StateSnapshot]:
            target, machine = item
            return target, machine.start(node.action)

        try:
            # Each instance owns its own lock-free FSM; the dispatch fan-out is concurrent.
            with ThreadPoolExecutor(max_workers=len(self.instances)) as executor:
                started = tuple(executor.map(start, self.instances.items()))
        except (StateMachineError, RuntimeError) as exc:
            self._node_states[node.id] = 'failed'
            self._status = 'failed'
            raise ParallelExperimentError(f'{node.id}: fan-out dispatch failed: {exc}') from exc

        task_ids = tuple((target, snapshot.task_id or '') for target, snapshot in started)
        self._active = FanoutDispatch(self._run_id, node.id, node.action, task_ids)
        self._pending = dict(task_ids)
        self._completed = {}
        self._node_states[node.id] = 'running'
        self._status = 'running'
        return self._active

    def complete(
        self,
        dispatch: FanoutDispatch,
        target: str,
        *,
        success: bool,
        evidence: CompletionEvidence | None = None,
    ) -> FanoutNodeResult | None:
        if self._active != dispatch or target not in self._pending:
            raise ParallelExperimentError('result does not belong to an active target')
        task_id = self._pending[target]
        if success and not isinstance(evidence, CompletionEvidence):
            raise ParallelExperimentError('success requires completion evidence')
        machine = self.instances[target]
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
        states = tuple((key, self._completed[key]) for key in self.instances)
        result = FanoutNodeResult(dispatch.node_id, 'succeeded', states,
                                  tuple(self._completed), reason='all_success')
        self._results.append(result)
        self._node_states[dispatch.node_id] = 'succeeded'
        self._active = None
        self._index += 1
        self._status = 'succeeded' if self._index == len(self.nodes) else 'running'
        return result

    def report_exception(self, target: str, exception_id: str) -> FanoutNodeResult:
        if self._active is None or target not in self._pending:
            raise ParallelExperimentError('exception target is not active')
        state = self.instances[target].report_exception(exception_id)
        return self._fail(target, state.current_state)

    def _fail(self, target: str, reason: str) -> FanoutNodeResult:
        node = self.nodes[self._index]
        # all_success means the group has ended; do not leave other targets executing.
        for other, machine in self.instances.items():
            if other != target and other in self._pending:
                machine.cancel_active_task('fanout_peer_failed')
        states = tuple((key, self.instances[key].snapshot()) for key in self.instances)
        result = FanoutNodeResult(node.id, 'failed', states, tuple(self._completed), target, reason)
        self._results.append(result)
        self._node_states[node.id] = 'failed'
        self._active = None
        self._pending = {}
        self._status = 'failed'
        return result


def load_seed_source_fanout_experiment(
    instances: dict[str, SubsystemStateMachine],
) -> ParallelFanoutExperiment:
    path = Path(__file__).resolve().parent / 'models' / 'seed-source-fanout-experiment.yaml'
    with path.open(encoding='utf-8') as stream:
        return ParallelFanoutExperiment(yaml.safe_load(stream), instances)
