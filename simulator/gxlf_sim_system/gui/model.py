"""Read-only projection of the simulator's own transition definitions."""
from dataclasses import dataclass

from ..machine import ACTIONS, CONFIRMATIONS, STATES


@dataclass(frozen=True)
class Edge:
    source: str
    target: str
    event: str


def edges():
    result = []
    for action in ACTIONS.values():
        result.extend(Edge(source, action.running, event) for source, event in action.requests.items())
        result.extend((Edge(action.running, action.success, action.success_event),
                       Edge(action.running, action.failure, action.failure_event)))
    for event in CONFIRMATIONS.values():
        result.extend(Edge("business:" + source, event.target, event.label) for source in sorted(event.sources))
    return result


def validate_snapshot(value):
    if not isinstance(value, dict) or value.get("model") != "seed-hierarchical-v1":
        raise ValueError("设备不是当前种子源模型，已禁用操作")
    leaf = value.get("state_id")
    if leaf not in STATES or value.get("business_state") != STATES[leaf][0]:
        raise ValueError("设备状态与本地状态图不一致，已禁用操作")
    active = value.get("active_command_id")
    running = {a.running: key for key, a in ACTIONS.items()}
    if leaf in running:
        if not isinstance(active, str) or not active or value.get("active_action") != running[leaf]:
            raise ValueError("执行状态缺少匹配的任务编号或动作，已禁用操作")
    elif active or value.get("active_action"):
        raise ValueError("非执行状态却包含活动任务，已禁用操作")
    return value


def available_actions(snapshot):
    if not snapshot or snapshot.get("active_command_id"):
        return []
    return [key for key, spec in ACTIONS.items() if snapshot["state_id"] in spec.requests]


def available_confirmations(snapshot):
    if not snapshot:
        return []
    return [key for key, spec in CONFIRMATIONS.items() if snapshot["business_state"] in spec.sources]


def edge_matches(edge, event):
    if not event or edge.event != event.get("event") or edge.target != event.get("target"):
        return False
    source = event.get("source")
    return edge.source == source or (source in STATES and edge.source == "business:" + STATES[source][0])
