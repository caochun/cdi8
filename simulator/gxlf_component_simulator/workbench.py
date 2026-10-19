"""Model validation and isolated single-device trials used by the development UI."""
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import RLock
import json
import uuid
import xml.etree.ElementTree as ET

from .component_model import ComponentModel, DATA_DIRECTORY
from .component_runtime import ComponentRuntime, identifier

SUPPORTED = {'scxml', 'state', 'initial', 'transition', 'datamodel', 'data'}


def load_model(xml, directory):
    """Reject unsupported semantics instead of silently drawing an executable model."""
    if not isinstance(xml, str) or len(xml.encode()) > 1_000_000:
        raise ValueError('模型 XML 为空或超过 1 MB')
    if '<!DOCTYPE' in xml.upper() or '<!ENTITY' in xml.upper():
        raise ValueError('模型不支持 DTD 或外部实体')
    root = ET.fromstring(xml)
    parents = {child: parent for parent in root.iter() for child in parent}
    for node in root.iter():
        tag = node.tag.rsplit('}', 1)[-1]
        if tag not in SUPPORTED:
            raise ValueError('当前 SCXML 执行器不支持元素：' + tag)
        if tag == 'transition':
            initial = parents.get(node) is not None and parents[node].tag.rsplit('}', 1)[-1] == 'initial'
            if 'cond' in node.attrib or (not initial and not node.get('event')) or not node.get('target'):
                raise ValueError('当前执行器要求带 event 和单一 target 的无条件迁移')
            if len(node.get('target').split()) != 1 or (not initial and (len(node.get('event').split()) != 1 or '*' in node.get('event'))):
                raise ValueError('当前执行器只支持单一目标和精确事件名称')
        if tag == 'data' and (node.get('id') != 'componentMetadata' or node.get('src') or node.get('expr')):
            raise ValueError('当前执行器仅支持 componentMetadata JSON 数据')
        if tag in {'state', 'scxml'} and any(k in node.attrib for k in ('src', 'invoke')):
            raise ValueError('当前执行器不支持外部状态内容')
    path = Path(directory) / 'model.scxml'
    path.write_text(xml, encoding='utf-8')
    model = ComponentModel(path)
    for action, spec in model.actions.items():
        for state in spec.requests:
            for outcome, events in [('success', spec.success_events), ('failure', spec.failure_events)]:
                requested, _ = model.request_transition(state, action)
                if requested not in events:
                    raise ValueError(f'动作 {action} 从 {state} 受理后缺少 {outcome} 反馈定义')
                event = events[requested]
                if event is not None:
                    model.machine.dispatch(requested, event)
    for confirmation, spec in model.confirmations.items():
        if not spec.source_outer or not spec.source_outer.issubset(model.outer_labels):
            raise ValueError('确认事件引用了未知外层状态：' + confirmation)
        if spec.machine_event not in model.machine_events:
            raise ValueError('确认事件引用了未定义迁移：' + confirmation)
    return model


def describe(model):
    return {'modelId': model.model_id, 'displayName': model.display_name,
            'initialState': model.machine.initial_leaf, 'states': sorted(model.states),
            'actions': {key: {'label': value.label, 'states': sorted(value.requests)}
                        for key, value in model.actions.items()},
            'events': sorted(model.machine_events),
            'confirmations': {key: {'label': value.label, 'observation': value.observation, 'source_outer': sorted(value.source_outer)}
                              for key, value in model.confirmations.items()}}


class Trials:
    def __init__(self, directory=None):
        self.directory = Path(directory or DATA_DIRECTORY / 'workbench-trials')
        self.directory.mkdir(parents=True, exist_ok=True)
        self.sessions = {}
        self.lock = RLock()

    def validate(self, xml):
        try:
            with TemporaryDirectory() as directory:
                return {'valid': True, 'diagnostics': [], 'capabilities': describe(load_model(xml, directory))}
        except (ValueError, ET.ParseError, KeyError, TypeError) as exc:
            diagnostic = {'message': str(exc), 'severity': 'error'}
            if isinstance(exc, ET.ParseError):
                diagnostic.update(line=exc.position[0], column=exc.position[1] + 1)
            return {'valid': False, 'diagnostics': [diagnostic]}

    def create(self, xml):
        with self.lock:
            session_id = uuid.uuid4().hex
            directory = self.directory / session_id
            directory.mkdir()
            model = load_model(xml, directory)
            runtime = ComponentRuntime(model, directory / 'state.sqlite3')
            self.sessions[session_id] = runtime
            return self.view(session_id)

    def view(self, session_id):
        runtime = self.sessions[identifier(session_id)]
        snapshot = runtime.snapshot()
        return {'id': session_id, 'xml': runtime.model.scxml_path.read_text(),
                'snapshot': snapshot, 'history': runtime.history(100),
                'capabilities': describe(runtime.model),
                'availableActions': [key for key, spec in runtime.model.actions.items()
                                     if snapshot['state_id'] in spec.requests]}

    def operate(self, session_id, operation, payload):
        with self.lock:
            runtime = self.sessions[identifier(session_id)]
            method = {'commands': runtime.execute, 'simulate': runtime.simulate,
                      'events': runtime.report_event}.get(operation)
            if method is None:
                raise ValueError('未知试运行操作')
            if operation == 'commands':
                payload = {**payload, 'command_id': uuid.uuid4().hex}
            method(payload)
            return self.view(session_id)
