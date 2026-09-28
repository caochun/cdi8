"""One sequential experiment; commands and results are separate operations."""

from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from pathlib import Path
from uuid import uuid4

import yaml

from .subsystem_fsm import CompletionEvidence, StateMachineError, StateSnapshot, SubsystemStateMachine


class ExperimentError(ValueError):
    """Invalid model or operation on an experiment."""


@dataclass(frozen=True)
class Node:
    id: str
    action: str
    phase: str


@dataclass(frozen=True)
class Dispatch:
    run_id: str
    node_id: str
    action: str
    task_id: str


@dataclass(frozen=True)
class NodeResult:
    node_id: str
    status: str
    subsystem: StateSnapshot
    reason: str = ""


@dataclass(frozen=True)
class ExperimentSnapshot:
    run_id: str
    status: str
    phase: str | None
    active: Dispatch | None
    node_states: tuple[tuple[str, str], ...]
    results: tuple[NodeResult, ...]


class SequentialExperiment:
    """A run is single-use and stops at the first rejected or failed node.

    dispatch_next starts a subsystem action but does not mark it successful.
    complete delivers a simulated result for that dispatch. State changes are
    performed exclusively through the subsystem's action API.
    """

    def __init__(self, model: dict, subsystem: SubsystemStateMachine):
        if model.get("kind") != "SequentialExperiment":
            raise ExperimentError("expected kind=SequentialExperiment")
        if model.get("system_id") != subsystem.model["system"]["id"]:
            raise ExperimentError("flow target does not match subsystem")
        raw_nodes = model.get("nodes")
        if not isinstance(raw_nodes, list) or not raw_nodes:
            raise ExperimentError("flow must contain nodes")
        nodes = []
        ids = set()
        actions = {a["id"]: a for a in subsystem.model["actions"]}
        for raw in raw_nodes:
            if not isinstance(raw, dict) or any(
                not isinstance(raw.get(key), str) or not raw[key].strip()
                for key in ("id", "action", "phase")
            ):
                raise ExperimentError("each node requires id, action and phase strings")
            node = Node(raw["id"], raw["action"], raw["phase"])
            if node.id in ids:
                raise ExperimentError(f"duplicate node id: {node.id}")
            if node.action not in actions:
                raise ExperimentError(f"unknown subsystem action: {node.action}")
            ids.add(node.id)
            nodes.append(node)
        self.nodes = tuple(nodes)
        self.subsystem = subsystem
        self._expected = {
            n.id: deepcopy(actions[n.action]["success"]) for n in nodes
        }
        self._run_id = uuid4().hex
        self._status = "idle"
        self._index = 0
        self._active: Dispatch | None = None
        self._node_states = {n.id: "waiting" for n in nodes}
        self._results: list[NodeResult] = []

    def snapshot(self) -> ExperimentSnapshot:
        phase = None if self._status == "idle" else self.nodes[self._index].phase
        return ExperimentSnapshot(
            self._run_id, self._status, phase, self._active,
            tuple(self._node_states.items()), tuple(self._results),
        )

    def dispatch_next(self) -> Dispatch:
        if self._status in {"succeeded", "failed"}:
            raise ExperimentError(f"experiment already {self._status}")
        if self._active is not None:
            raise ExperimentError("wait for the active node's result")
        node = self.nodes[self._index]
        try:
            started = self.subsystem.start(node.action)
        except StateMachineError as exc:
            self._finish_node("failed", self.subsystem.snapshot(), str(exc))
            raise ExperimentError(f"{node.id}: command rejected: {exc}") from exc
        self._active = Dispatch(self._run_id, node.id, node.action, started.task_id)
        self._node_states[node.id] = "running"
        self._status = "running"
        return self._active

    def complete(
        self, dispatch: Dispatch, *, success: bool,
        evidence: CompletionEvidence | None = None,
    ) -> NodeResult:
        if type(success) is not bool:
            raise ExperimentError("success must be a boolean")
        if self._active is None or dispatch != self._active:
            raise ExperimentError("result does not belong to the active task")
        if success and not isinstance(evidence, CompletionEvidence):
            raise ExperimentError("success requires callback and observed state evidence")
        try:
            if success:
                state = self.subsystem.complete_success(
                    dispatch.action, task_id=dispatch.task_id, evidence=evidence,
                )
            else:
                state = self.subsystem.complete_failure(dispatch.action, task_id=dispatch.task_id)
        except StateMachineError as exc:
            return self._finish_node("failed", self.subsystem.snapshot(), str(exc))
        if not success:
            return self._finish_node("failed", state, state.current_state)
        expected = self._expected[dispatch.node_id]
        mismatches = [key for key, value in expected.items() if getattr(state, key) != value]
        if state.task_state != "succeeded" or state.active_action is not None or mismatches:
            return self._finish_node("failed", state, "subsystem result did not satisfy success criteria")
        return self._finish_node("succeeded", state)

    def report_exception(self, exception_id: str) -> NodeResult:
        """Deliver an external subsystem exception and immediately block the run."""
        if self._status in {"succeeded", "failed"}:
            raise ExperimentError(f"experiment already {self._status}")
        state = self.subsystem.report_exception(exception_id)
        return self._finish_node("failed", state, state.current_state)

    def _finish_node(self, status: str, state: StateSnapshot, reason: str = "") -> NodeResult:
        node = self.nodes[self._index]
        result = NodeResult(node.id, status, state, reason)
        self._results.append(result)
        self._node_states[node.id] = status
        self._active = None
        if status == "failed":
            self._status = "failed"
        elif self._index == len(self.nodes) - 1:
            self._status = "succeeded"
        else:
            self._status = "running"
            self._index += 1
        return result


def load_seed_source_experiment(subsystem: SubsystemStateMachine) -> SequentialExperiment:
    path = Path(__file__).resolve().parent / "models" / "seed-source-experiment.yaml"
    with path.open(encoding="utf-8") as stream:
        return SequentialExperiment(yaml.safe_load(stream), subsystem)
