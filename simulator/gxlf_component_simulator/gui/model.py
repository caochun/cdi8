"""Read-only projection of the simulator's own transition definitions."""
from dataclasses import dataclass

from ..component_model import ComponentModel


@dataclass(frozen=True)
class Edge:
    source: str
    target: str
    event: str


def edges(profile: ComponentModel):
    # Every drawable device-state edge comes from SCXML. Request events that
    # leave the state unchanged are added as self-edges for task visibility.
    result = []
    machine = profile.machine
    for transition in machine.transitions():
        source = transition.source
        if source in machine.composites:
            source = "outer:" + profile.outer_label(machine.enter(source))
        result.append(Edge(source, machine.enter(transition.target), transition.event))
    for action_name, action in profile.actions.items():
        for source, event in action.requests.items():
            target, _ = profile.request_transition(source, action_name)
            if target == source and not any(edge.source == source and edge.target == target and edge.event == event for edge in result):
                result.append(Edge(source, target, event))
    return result


def validate_snapshot(value, profile):
    if not isinstance(value, dict) or value.get("model") != profile.model_id:
        raise ValueError("设备不是当前 SCXML 组件模型，已禁用操作")
    leaf = value.get("inner_state", value.get("state_id"))
    if leaf not in profile.states or value.get("outer_state_label") != profile.outer_label(leaf):
        raise ValueError("设备状态与本地状态图不一致，已禁用操作")
    if value.get("active_command_id") and not value.get("active_action"):
        raise ValueError("任务编号存在但缺少动作，已禁用操作")
    return value


def available_actions(snapshot, profile):
    if not snapshot or snapshot.get("active_command_id"):
        return []
    return [key for key, spec in profile.actions.items() if snapshot["state_id"] in spec.requests]


def available_confirmations(snapshot, profile):
    if not snapshot:
        return []
    return [key for key, spec in profile.confirmations.items() if snapshot.get("outer_state") in spec.source_outer]


def edge_matches(edge, event, profile):
    if not event or edge.event != event.get("event") or edge.target != event.get("target"):
        return False
    source = event.get("source")
    if edge.source == source:
        return True
    return edge.source.startswith("outer:") and source in profile.states and edge.source == "outer:" + profile.outer_label(source)
