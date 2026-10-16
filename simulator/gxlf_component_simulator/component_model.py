"""Build a complete component model from one SCXML document.

The JSON in the standard SCXML ``datamodel`` is model metadata embedded in the SCXML file:
labels, command bindings, confirmations and runtime context defaults.  This
module contains only generic dataclasses and validation; it has no component
specific definitions.
"""

from dataclasses import dataclass
from pathlib import Path
from typing import Optional, Union

from .scxml_machine import SCXMLMachine, TransitionError


@dataclass(frozen=True)
class ActionSpec:
    label: str
    requests: dict
    success_events: dict
    failure_events: dict
    success_key: str
    failure_key: str
    success_observation: Optional[Union[str, dict]] = None
    failure_observation: Optional[Union[str, dict]] = None


@dataclass(frozen=True)
class ConfirmationSpec:
    label: str
    source_outer: frozenset
    machine_event: str
    observation: str


class ComponentModel:
    def __init__(self, scxml_path):
        self.scxml_path = Path(scxml_path)
        self.machine = SCXMLMachine(self.scxml_path)
        metadata = self.machine.metadata
        if not metadata:
            raise TransitionError("SCXML datamodel 缺少完整组件模型定义")
        self.model_id = self._required(metadata, "model_id")
        self.model_version = metadata.get("model_version", 1)
        self.display_name = self._required(metadata, "display_name")
        self.outer_labels = metadata.get("outer_labels", {})
        self.inner_labels = metadata.get("inner_labels", {})
        self.context_defaults = metadata.get("context_defaults", {})
        self.actions = {
            key: ActionSpec(
                label=self._required(value, "label"),
                requests=value.get("requests", {}),
                success_events=value.get("success_events", {}),
                failure_events=value.get("failure_events", {}),
                success_key=self._required(value, "success_key"),
                failure_key=self._required(value, "failure_key"),
                success_observation=value.get("success_observation"),
                failure_observation=value.get("failure_observation"),
            )
            for key, value in metadata.get("actions", {}).items()
        }
        self.confirmations = {
            key: ConfirmationSpec(
                label=self._required(value, "label"),
                source_outer=frozenset(value.get("source_outer", [])),
                machine_event=self._required(value, "machine_event"),
                observation=self._required(value, "observation"),
            )
            for key, value in metadata.get("confirmations", {}).items()
        }
        self.states = {}
        for leaf in self.machine.leaves:
            outer = self.machine.outer(leaf)
            if outer not in self.outer_labels or leaf not in self.inner_labels:
                raise TransitionError(f"SCXML metadata 缺少状态标签：{leaf}")
            self.states[leaf] = (self.outer_labels[outer], self.inner_labels[leaf])
        for action, spec in self.actions.items():
            for state in set(spec.requests) | set(spec.success_events) | set(spec.failure_events):
                if state not in self.states:
                    raise TransitionError(f"动作 {action} 引用了未知叶子状态 {state}")
            for events, observation in ((spec.success_events, spec.success_observation),
                                        (spec.failure_events, spec.failure_observation)):
                if isinstance(observation, dict):
                    if set(observation) != set(events) or any(
                            not isinstance(value, str) or not value for value in observation.values()):
                        raise TransitionError(f"动作 {action} 的分状态反馈确认定义不完整")

    @staticmethod
    def _required(value, key):
        result = value.get(key)
        if not isinstance(result, str) or not result:
            raise TransitionError(f"SCXML metadata 缺少 {key}")
        return result

    @property
    def machine_events(self):
        return {transition.event for transition in self.machine.transitions()}

    def initial_context(self):
        import copy
        return copy.deepcopy(self.context_defaults)

    def state_info(self, state):
        try:
            return self.states[state]
        except KeyError as exc:
            raise TransitionError("未知组件状态：" + str(state)) from exc

    def outer_label(self, state):
        return self.state_info(state)[0]

    def inner_label(self, state):
        return self.state_info(state)[1]

    def request_transition(self, state, action):
        try:
            spec = self.actions[action]
        except KeyError as exc:
            raise TransitionError("未知动作：" + str(action)) from exc
        if state not in spec.requests:
            outer, inner = self.state_info(state)
            raise TransitionError(f"{outer}/{inner} 不接受 {spec.label}")
        event = spec.requests[state]
        try:
            target, _ = self.machine.dispatch(state, event)
        except TransitionError:
            target = state
        return target, event

    def completion_transition(self, state, action, outcome, observed_outer=None):
        if outcome not in {"success", "failure"}:
            raise TransitionError("反馈必须是 success 或 failure")
        try:
            spec = self.actions[action]
        except KeyError as exc:
            raise TransitionError("未知动作：" + str(action)) from exc
        events = spec.success_events if outcome == "success" else spec.failure_events
        if state not in events:
            raise TransitionError("反馈不对应当前任务可接受的状态")
        expected = self.expected_observation(state, action, outcome)
        if expected is not None and observed_outer != expected:
            raise TransitionError(f"该反馈还必须确认设备状态为 {expected}")
        event = events[state]
        target = state
        if event is not None:
            target, _ = self.machine.dispatch(state, event)
        key = spec.success_key if outcome == "success" else spec.failure_key
        return target, event or f"{action}.{outcome}", key

    def expected_observation(self, state, action, outcome):
        spec = self.actions[action]
        observation = spec.success_observation if outcome == "success" else spec.failure_observation
        return observation[state] if isinstance(observation, dict) else observation

    def confirmation_transition(self, state, event, observed_outer):
        try:
            spec = self.confirmations[event]
        except KeyError as exc:
            raise TransitionError("未知确认事件：" + str(event)) from exc
        outer = self.machine.outer(state)
        if outer not in spec.source_outer:
            raise TransitionError(f"{outer} 不接受 {spec.label}")
        if observed_outer != spec.observation:
            raise TransitionError(f"必须明确确认设备状态为 {spec.observation}")
        target, _ = self.machine.dispatch(state, spec.machine_event)
        return target, spec.machine_event, spec.label


MODEL_DIRECTORY = Path(__file__).resolve().with_name("models")
DATA_DIRECTORY = Path(__file__).resolve().with_name("data")
# The single-level executable model is the canonical seed model.
# Keep the descriptive name as an alias so all simulator entry points share one
# SCXML definition and cannot drift between two seed implementations.
SEED_MODEL = ComponentModel(MODEL_DIRECTORY / "seed-source-state-machine.scxml")
PREAMP_MODEL = ComponentModel(MODEL_DIRECTORY / "preamp-state-machine.scxml")
SHG_MODEL = ComponentModel(MODEL_DIRECTORY / "shg-injection-state-machine.scxml")
MULTIPASS_MODEL = ComponentModel(MODEL_DIRECTORY / "multipass-state-machine.scxml")
REFERENCE_MODEL = SEED_MODEL


def model_by_name(name):
    try:
        return {"seed": SEED_MODEL, "shg": SHG_MODEL, "preamp": PREAMP_MODEL,
                "multipass": MULTIPASS_MODEL, "reference": REFERENCE_MODEL}[name]
    except KeyError as exc:
        raise TransitionError("未知组件模型：" + str(name)) from exc


def model_by_id(model_id):
    for model in (SEED_MODEL, PREAMP_MODEL, SHG_MODEL, MULTIPASS_MODEL, REFERENCE_MODEL):
        if model.model_id == model_id:
            return model
    raise TransitionError("未知组件模型：" + str(model_id))


__all__ = ["ActionSpec", "ConfirmationSpec", "ComponentModel", "DATA_DIRECTORY", "SEED_MODEL", "PREAMP_MODEL", "SHG_MODEL", "MULTIPASS_MODEL", "REFERENCE_MODEL", "model_by_name", "model_by_id"]
