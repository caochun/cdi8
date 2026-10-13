"""Read-only projection of the simulator's own transition definitions."""
from dataclasses import dataclass

from ..machine import ACTIONS, CONFIRMATIONS, MACHINE, MODEL_ID, STATES, request_transition


@dataclass(frozen=True)
class Edge:
    source: str
    target: str
    event: str


def edges():
    # Every drawable device-state edge comes from SCXML. Request events that
    # leave the state unchanged are added as self-edges for task visibility.
    result = []
    for transition in MACHINE.transitions():
        source = transition.source
        if source in MACHINE.composites:
            source = "business:" + STATES[MACHINE.enter(source)][0]
        result.append(Edge(source, MACHINE.enter(transition.target), transition.event))
    for action_name, action in ACTIONS.items():
        for source, event in action.requests.items():
            target, _ = request_transition(source, action_name)
            if target == source and not any(edge.source == source and edge.target == target and edge.event == event for edge in result):
                result.append(Edge(source, target, event))
    return result


def validate_snapshot(value):
    if not isinstance(value, dict) or value.get("model") != MODEL_ID:
        raise ValueError("设备不是当前种子源模型，已禁用操作")
    leaf = value.get("inner_state", value.get("state_id"))
    if leaf not in STATES or value.get("outer_state_label", value.get("business_state")) != STATES[leaf][0]:
        raise ValueError("设备状态与本地状态图不一致，已禁用操作")
    if value.get("active_command_id") and not value.get("active_action"):
        raise ValueError("任务编号存在但缺少动作，已禁用操作")
    return value


def available_actions(snapshot):
    if not snapshot or snapshot.get("active_command_id"):
        return []
    return [key for key, spec in ACTIONS.items() if snapshot["state_id"] in spec.requests]


def available_confirmations(snapshot):
    if not snapshot:
        return []
    return [key for key, spec in CONFIRMATIONS.items() if snapshot.get("outer_state_label", snapshot["business_state"]) in spec.sources]


def edge_matches(edge, event):
    if not event or edge.event != event.get("event") or edge.target != event.get("target"):
        return False
    source = event.get("source")
    if edge.source == source:
        return True
    return edge.source.startswith("business:") and source in STATES and edge.source == "business:" + STATES[source][0]
