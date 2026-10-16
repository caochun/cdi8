"""Small SCXML 1.0 interpreter used by every simulated component.

The SCXML document is the only source of transition semantics. This module
handles sequential compound states, initial states, event selection and the
entry/exit sets of internal and external transitions. Executable content and
guard expressions are outside this interpreter's supported subset.
"""

from dataclasses import dataclass
from pathlib import Path
from typing import Optional
import json
import xml.etree.ElementTree as ET


class TransitionError(ValueError):
    """The model, event or command is invalid."""


SCXML_NS = "{http://www.w3.org/2005/07/scxml}"


@dataclass(frozen=True)
class Transition:
    source: str
    event: str
    target: str
    type: str = "external"


@dataclass(frozen=True)
class TransitionStep:
    transition: Transition
    target: str
    exit_states: tuple[str, ...]
    entry_states: tuple[str, ...]


@dataclass(frozen=True)
class StateNode:
    id: str
    parent: Optional[str]
    initial: Optional[str]
    children: tuple[str, ...]
    transitions: tuple[Transition, ...]


class SCXMLMachine:
    """Dependency-free interpreter for the SCXML subset used by the models."""

    def __init__(self, path):
        self.scxml_path = Path(path)
        try:
            root = ET.parse(self.scxml_path).getroot()
        except (OSError, ET.ParseError) as exc:
            raise TransitionError(f"无法读取 SCXML 状态模型：{self.scxml_path}") from exc
        if root.tag != SCXML_NS + "scxml" or root.attrib.get("version") != "1.0":
            raise TransitionError("SCXML 模型必须是 W3C SCXML 1.0")
        self.root_id = "__root__"
        self.metadata = self._read_metadata(root)
        self.nodes = {}
        self._parse_children(root, self.root_id)
        self.root_initial = root.attrib.get("initial")
        if self.root_initial not in self.nodes:
            raise TransitionError("SCXML 根初始状态不存在")
        self.leaves = frozenset(state for state, node in self.nodes.items() if not node.children)
        self.composites = frozenset(set(self.nodes) - set(self.leaves))
        self.initial_leaf = self.enter(self.root_initial)
        for transition in self.transitions():
            self.enter(transition.target)

    @staticmethod
    def _read_metadata(root):
        datamodel = root.find(SCXML_NS + "datamodel")
        element = None if datamodel is None else datamodel.find(SCXML_NS + "data[@id='componentMetadata']")
        if element is None or not (element.text or "").strip():
            return {}
        try:
            value = json.loads(element.text)
        except json.JSONDecodeError as exc:
            raise TransitionError("SCXML gxlf:metadata 不是有效 JSON") from exc
        if not isinstance(value, dict):
            raise TransitionError("SCXML gxlf:metadata 必须是 JSON 对象")
        return value

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
                Transition(state_id, item.attrib.get("event", ""), item.attrib.get("target", ""),
                           item.attrib.get("type", "external"))
                for item in state.findall(SCXML_NS + "transition")
            )
            if any(not transition.event or not transition.target for transition in transitions):
                raise TransitionError(f"状态 {state_id} 含有不完整转换")
            if any(transition.type not in {"internal", "external"} for transition in transitions):
                raise TransitionError(f"状态 {state_id} 含有无效迁移类型")
            self.nodes[state_id] = StateNode(state_id, parent, initial, child_ids, transitions)
            children.append(state_id)
            self._parse_children(state, state_id)
        if parent == self.root_id and not children:
            raise TransitionError("SCXML 没有顶层状态")

    def enter(self, state_id):
        if state_id not in self.nodes:
            raise TransitionError("SCXML 转换目标不存在：" + str(state_id))
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

    def step(self, leaf, event):
        """Select a transition and compute its SCXML exit/entry order.

        For sequential states, the transition domain is the source compound
        state for internal transitions to a proper descendant. Otherwise it
        is the least common *proper* compound ancestor of source and target.
        Internal transitions from atomic states or to non-descendants follow
        external semantics, including exit/re-entry on self-transitions.
        """
        for source in self.path(leaf):
            for transition in self.nodes[source].transitions:
                if transition.event == event:
                    target = self.enter(transition.target)
                    ancestors = []
                    current = transition.target
                    while current != self.root_id:
                        current = self.nodes[current].parent
                        ancestors.append(current)
                    if (transition.type == "internal" and source in self.composites
                            and source in ancestors):
                        domain = source
                    else:
                        domain = self.nodes[source].parent
                        while domain not in ancestors:
                            domain = self.nodes[domain].parent
                    exits = []
                    current = leaf
                    while current != domain:
                        exits.append(current)
                        current = self.nodes[current].parent
                    entries = []
                    current = target
                    while current != domain:
                        entries.append(current)
                        current = self.nodes[current].parent
                    return TransitionStep(transition, target, tuple(exits), tuple(reversed(entries)))
        raise TransitionError(f"当前状态 {leaf} 未启用事件 {event}")

    def dispatch(self, leaf, event):
        step = self.step(leaf, event)
        return step.target, step.transition

    def transitions(self):
        return tuple(transition for node in self.nodes.values() for transition in node.transitions)
