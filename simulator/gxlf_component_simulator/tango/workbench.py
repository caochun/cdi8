"""Replaceable Tango environments. Each environment owns one Device Server."""
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import RLock
import json
import uuid

from ..component_model import DATA_DIRECTORY
from ..component_runtime import identifier
from ..workbench import Trials, load_model, describe


class Workbench:
    def __init__(self, bridge, context=None, directory=None):
        self.bridge = bridge
        self.context = context
        self.directory = Path(directory or DATA_DIRECTORY / 'workbench-environments')
        self.directory.mkdir(parents=True, exist_ok=True)
        self.trials = Trials()
        self.lock = RLock()
        self.current = {'id': 'builtin', 'devices': [], 'status': 'ready'}

    def configure(self, payload):
        from tango.test_context import MultiDeviceTestContext
        from .device import ComponentDevice
        from .preparation_context import PreparationContextDevice
        from .control_bridge import TangoBridge
        rows = payload.get('devices', [])
        if not rows or len(rows) > 200:
            raise ValueError('请配置 1–200 个模拟设备实例')
        with self.lock:
            generation = uuid.uuid4().hex
            directory = self.directory / generation
            directory.mkdir()
            definitions, bindings, ids, descriptions = [], {}, set(), []
            for row in rows:
                device_id = identifier(row['id'])
                component = identifier(row['component'])
                if device_id in ids or component == 'context':
                    raise ValueError('设备编号重复或使用了保留的 context 类型')
                ids.add(device_id)
                model_dir = directory / device_id
                model_dir.mkdir()
                model = load_model(row['xml'], model_dir)
                delay = int(row.get('delayMs', 500))
                if not 0 <= delay <= 3_600_000:
                    raise ValueError('反馈延迟必须在 0–3600000 毫秒之间')
                name = 'gxlf/' + component + '/' + device_id
                definitions.append({'name': name, 'properties': {
                    'component_model': model.model_id, 'model_path': str(model.scxml_path.resolve()),
                    'model_revision': row['revision'],
                    'database_path': str((model_dir / 'state.sqlite3').resolve()),
                    'auto_complete': bool(row.get('autoComplete', False)),
                    'auto_complete_delay_ms': delay}})
                bindings[device_id] = {'component': component, 'name': name}
                descriptions.append({**{k: row[k] for k in ('id', 'component', 'revision')},
                                     'capabilities': describe(model)})
            conditions = payload.get('conditions', {})
            if not isinstance(conditions, dict) or any(not isinstance(k, str) or not k or not isinstance(v, bool) for k, v in conditions.items()):
                raise ValueError('外部条件必须是命名布尔值')
            context = MultiDeviceTestContext([
                {'class': ComponentDevice, 'devices': definitions},
                {'class': PreparationContextDevice, 'devices': [{'name': 'gxlf/context/01', 'properties': {
                    'auto_complete': bool(payload.get('autoComplete', False)), 'auto_complete_delay_ms': 500,
                    'extra_conditions': json.dumps(payload.get('conditions', {}))}}]},
            ], process=True, host='127.0.0.1', timeout=20)
            try:
                context.start()
                mapped = {key: {'component': item['component'], 'address': context.get_device_access(item['name'])}
                          for key, item in bindings.items()}
                mapped['context_01'] = {'component': 'context', 'address': context.get_device_access('gxlf/context/01')}
                bridge = TangoBridge(mapped)
                # Populate snapshots before announcing this environment to subscribers.
                for key in mapped:
                    bridge.publish(key, snapshot=bridge.snapshot(key), origin='environment')
            except Exception:
                context.stop()
                raise
            previous_bridge, previous_context = self.bridge, self.context
            self.bridge, self.context = bridge, context
            self.current = {'id': generation, 'devices': descriptions, 'status': 'ready'}
            previous_bridge.close()
            if previous_context:
                previous_context.stop()
            return self.current

    def close(self):
        self.bridge.close()
        if self.context:
            self.context.stop()
