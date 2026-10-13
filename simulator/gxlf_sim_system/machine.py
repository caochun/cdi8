"""SCXML-backed seed-source state machine and command bindings.

The SCXML document is the source of transition semantics. This module only
adds presentation labels and maps simulator commands to SCXML events. Command
execution is deliberately kept outside the device state: accepting a task does
not create a ``xxx-in-progress`` state.
"""
from dataclasses import dataclass
from pathlib import Path
from typing import Optional
import xml.etree.ElementTree as ET


class TransitionError(ValueError):
    """The event is not enabled or its evidence is not sufficient."""


MODEL_ID = "seed-hierarchical-v2"
MODEL_VERSION = 2
SCXML_PATH = Path(__file__).resolve().parents[2] / "docs" / "seed-source-state-machine.scxml"
SCXML_NS = "{http://www.w3.org/2005/07/scxml}"


@dataclass(frozen=True)
class Transition:
    source: str
    event: str
    target: str


@dataclass(frozen=True)
class StateNode:
    id: str
    parent: Optional[str]
    initial: Optional[str]
    children: tuple[str, ...]
    transitions: tuple[Transition, ...]


class SCXMLMachine:
    """Small, dependency-free SCXML 1.0 interpreter for this model subset."""

    def __init__(self, path=SCXML_PATH):
        self.scxml_path = Path(path)
        try:
            root = ET.parse(self.scxml_path).getroot()
        except (OSError, ET.ParseError) as exc:
            raise TransitionError(f"无法读取 SCXML 状态模型：{self.scxml_path}") from exc
        if root.tag != SCXML_NS + "scxml" or root.attrib.get("version") != "1.0":
            raise TransitionError("SCXML 模型必须是 W3C SCXML 1.0")
        self.root_id = "__root__"
        self.nodes = {}
        self._parse_children(root, self.root_id)
        self.root_initial = root.attrib.get("initial")
        if self.root_initial not in self.nodes:
            raise TransitionError("SCXML 根初始状态不存在")
        self.leaves = frozenset(state for state, node in self.nodes.items() if not node.children)
        self.composites = frozenset(set(self.nodes) - set(self.leaves))
        self.initial_leaf = self.enter(self.root_initial)

    def _parse_children(self, element, parent):
        children = []
        for state in element.findall(SCXML_NS + "state"):
            state_id = state.attrib.get("id")
            if not state_id or state_id in self.nodes:
                raise TransitionError("SCXML 包含重复或空状态编号")
            child_elements = state.findall(SCXML_NS + "state")
            child_ids = tuple(child.attrib["id"] for child in child_elements)
            initial = state.attrib.get("initial")
            initial_element = state.find(SCXML_NS + "initial")
            if initial is None and initial_element is not None:
                initial_transition = initial_element.find(SCXML_NS + "transition")
                initial = None if initial_transition is None else initial_transition.attrib.get("target")
            if child_ids and initial not in child_ids:
                raise TransitionError(f"复合状态 {state_id} 缺少有效初始子状态")
            transitions = tuple(
                Transition(state_id, item.attrib.get("event", ""), item.attrib.get("target", ""))
                for item in state.findall(SCXML_NS + "transition")
            )
            if any(not transition.event or not transition.target for transition in transitions):
                raise TransitionError(f"状态 {state_id} 含有不完整转换")
            self.nodes[state_id] = StateNode(state_id, parent, initial, child_ids, transitions)
            children.append(state_id)
            self._parse_children(state, state_id)
        if parent == self.root_id and not children:
            raise TransitionError("SCXML 没有顶层状态")

    def enter(self, state_id):
        if state_id not in self.nodes:
            raise TransitionError("SCXML 转换目标不存在：" + state_id)
        node = self.nodes[state_id]
        while node.children:
            state_id = node.initial
            node = self.nodes[state_id]
        return state_id

    def path(self, leaf):
        if leaf not in self.leaves:
            raise TransitionError("不是有效叶子状态：" + str(leaf))
        result = []
        current = leaf
        while current != self.root_id:
            result.append(current)
            current = self.nodes[current].parent
        return tuple(result)

    def outer(self, leaf):
        return self.path(leaf)[-1]

    def dispatch(self, leaf, event):
        """Dispatch an event from the leaf, bubbling to compound ancestors."""
        for source in self.path(leaf):
            for transition in self.nodes[source].transitions:
                if transition.event == event:
                    return self.enter(transition.target), transition
        raise TransitionError(f"当前状态 {leaf} 未启用事件 {event}")

    def transitions(self):
        return tuple(transition for node in self.nodes.values() for transition in node.transitions)


MACHINE = SCXMLMachine()

# SCXML ids are stable machine identifiers; Chinese text remains presentation concern.
OUTER_LABELS = {
    "notReady": "未就绪",
    "ready": "就绪",
    "emitting": "出光",
    "fault": "设备异常",
}
INNER_LABELS = {
    "unchecked": "待检查",
    "selfTestOk": "自检通过",
    "checkFailed": "功能检查未通过",
    "configIdle": "参数待确认",
    "configOk": "参数已确认",
    "collectIdle": "尚无本次采集结果",
    "collectOk": "采集完成",
    "collectFailed": "采集失败",
    "faultDetected": "异常待分类",
    "faultLock": "故障锁定",
    "commFault": "通信异常",
    "timeoutFault": "执行超时",
}
STATES = {leaf: (OUTER_LABELS[MACHINE.outer(leaf)], INNER_LABELS[leaf]) for leaf in MACHINE.leaves}


@dataclass(frozen=True)
class Action:
    label: str
    requests: dict
    success_events: dict
    failure_events: dict
    success_key: str
    failure_key: str
    success_observation: Optional[str] = None
    failure_observation: Optional[str] = None

    @property
    def request_states(self):
        return frozenset(self.requests)


ACTIONS = {
    "power_on_self_test": Action(
        "开机自检",
        {"unchecked": "selftest.requested", "checkFailed": "selftest.retry-requested"},
        {"unchecked": "selftest.passed", "checkFailed": "selftest.passed"},
        {"unchecked": None, "checkFailed": None},
        "自检通过", "自检失败"),
    "function_check": Action(
        "功能检查",
        {"selfTestOk": "function-check.requested", "checkFailed": "function-check.retry-requested"},
        {"selfTestOk": "function-check.passed", "checkFailed": "function-check.retry-passed"},
        {"selfTestOk": "function-check.failed", "checkFailed": None},
        "功能检查通过", "功能检查未通过", success_observation="就绪"),
    "parameter_dispatch": Action(
        "参数下发",
        {"configIdle": "parameter-dispatch.requested", "configOk": "configuration.reconfiguration-accepted"},
        {"configIdle": "configuration.validated", "configOk": "configuration.validated"},
        {"configIdle": None, "configOk": None},
        "参数配置正常", "参数下发失败"),
    "seed_source_emit": Action(
        "种子源出光",
        {"configOk": "emission.requested"},
        {"configOk": "emission.stable-confirmed"},
        {"configOk": None},
        "稳定出光确认", "出光失败且仍就绪", success_observation="出光", failure_observation="就绪"),
    "laser_parameter_collect": Action(
        "激光参数采集",
        {"collectIdle": "collection.requested", "collectOk": "collection.request-accepted", "collectFailed": "collection.retry-accepted"},
        {"collectIdle": "collection.completed", "collectOk": "collection.completed", "collectFailed": "collection.completed"},
        {"collectIdle": "collection.failed", "collectOk": "collection.failed", "collectFailed": "collection.failed"},
        "采集完成", "采集失败"),
    "shutdown": Action(
        "关机",
        {"configIdle": "shutdown.requested", "configOk": "shutdown.requested",
         "collectIdle": "shutdown.requested", "collectOk": "shutdown.requested", "collectFailed": "shutdown.requested"},
        {"configIdle": "shutdown.completed", "configOk": "shutdown.completed",
         "collectIdle": "shutdown.completed", "collectOk": "shutdown.completed", "collectFailed": "shutdown.completed"},
        {"configIdle": None, "configOk": None, "collectIdle": None, "collectOk": None, "collectFailed": None},
        "关机完成", "关机失败"),
}


@dataclass(frozen=True)
class Confirmation:
    label: str
    sources: frozenset
    machine_event: str
    observation: str


CONFIRMATIONS = {
    "standby_confirmed": Confirmation("正常关光完成且就绪确认", frozenset({"出光"}), "emission.stopped-confirmed", "就绪"),
    "shutdown_confirmed": Confirmation("关机完成且未就绪确认", frozenset({"就绪", "出光"}), "shutdown.completed", "未就绪"),
    "abort_reset_confirmed": Confirmation("中止复位完成且未就绪确认", frozenset({"就绪", "出光"}), "abort.completed", "未就绪"),
    "fault_confirmed": Confirmation("设备故障确认", frozenset({"未就绪", "就绪", "出光"}), "fault.confirmed", "设备异常"),
    "recovery_confirmed": Confirmation("恢复后未就绪确认", frozenset({"设备异常"}), "recovery.completed", "未就绪"),
}


def state_info(state):
    if state not in STATES:
        raise TransitionError("未知种子源状态：" + str(state))
    return STATES[state]


def request_transition(state, action):
    """Validate a task request and apply only request-time state invalidation."""
    if action not in ACTIONS:
        raise TransitionError("未知动作或 SCXML 未定义动作绑定：" + str(action))
    spec = ACTIONS[action]
    if state not in spec.requests:
        outer, inner = state_info(state)
        raise TransitionError(f"{outer}/{inner} 不接受 {spec.label}")
    event = spec.requests[state]
    if event in {"configuration.reconfiguration-accepted", "collection.request-accepted", "collection.retry-accepted"}:
        target, _ = MACHINE.dispatch(state, event)
        return target, event
    return state, event


def _completion_event(state, spec, outcome):
    events = spec.success_events if outcome == "success" else spec.failure_events
    if state not in events:
        raise TransitionError("反馈不对应当前任务可接受的状态")
    return events[state]


def expected_observation(state, action, outcome):
    spec = ACTIONS[action]
    return spec.success_observation if outcome == "success" else spec.failure_observation


def completion_transition(state, action, outcome, observed_business_state=None):
    if outcome not in {"success", "failure"}:
        raise TransitionError("反馈必须是 success 或 failure")
    if action not in ACTIONS:
        raise TransitionError("未知动作：" + str(action))
    spec = ACTIONS[action]
    event = _completion_event(state, spec, outcome)
    expected = expected_observation(state, action, outcome)
    if expected is not None and observed_business_state != expected:
        raise TransitionError(f"该反馈还必须确认设备状态为 {expected}")
    target = state
    if event is not None:
        target, _ = MACHINE.dispatch(state, event)
    key = spec.success_key if outcome == "success" else spec.failure_key
    return target, event or ("%s.%s" % (action, outcome)), key


def confirmation_transition(state, event, observed_business_state):
    if event not in CONFIRMATIONS:
        raise TransitionError("未知确认事件：" + str(event))
    spec = CONFIRMATIONS[event]
    outer = state_info(state)[0]
    if outer not in spec.sources:
        raise TransitionError(f"{outer} 不接受 {spec.label}")
    if observed_business_state != spec.observation:
        raise TransitionError(f"必须明确确认设备状态为 {spec.observation}")
    target, _ = MACHINE.dispatch(state, spec.machine_event)
    return target, spec.machine_event, spec.label
