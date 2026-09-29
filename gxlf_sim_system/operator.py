"""Local operator session and append-only replay journal for the joint demo."""
from __future__ import annotations

from copy import deepcopy
from dataclasses import asdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
from threading import RLock

from .joint_fanout_experiment import load_laser_joint_fanout_experiment
from .simulation import simulated_success_evidence
from .subsystem_fsm import SubsystemStateMachine


class Journal:
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.events = self.read(self.path) if self.path.exists() else []

    @staticmethod
    def read(path):
        events = []
        with Path(path).open(encoding='utf-8') as stream:
            for line_no, line in enumerate(stream, 1):
                try:
                    event = json.loads(line)
                    if event['version'] != 1 or event['seq'] != line_no:
                        raise ValueError('invalid schema or sequence')
                    events.append(event)
                except (ValueError, KeyError, TypeError) as exc:
                    raise ValueError(f'日志第 {line_no} 行无效: {exc}') from exc
        return events

    def append(self, kind, run_id, **payload):
        event = dict(version=1, seq=len(self.events) + 1,
                     timestamp=datetime.now(timezone.utc).isoformat(),
                     kind=kind, run_id=run_id, **payload)
        # JSON normalization also detaches mutable payloads from live state.
        encoded = json.dumps(event, ensure_ascii=False, allow_nan=False)
        with self.path.open('a', encoding='utf-8') as stream:
            stream.write(encoded + '\n')
            stream.flush()
        self.events.append(json.loads(encoded))
        return event

    def replay(self, seq):
        if type(seq) is not int or not 1 <= seq <= len(self.events):
            raise ValueError('回放位置不存在')
        for event in reversed(self.events[:seq]):
            if event['kind'] == 'checkpoint':
                return deepcopy(event['state'])
        raise ValueError('该位置之前没有完整状态快照')


def build_flow():
    root = Path(__file__).resolve().parent / 'models'
    seed = root / 'excel-seed-source-state-machine.yaml'
    shg = root / 'excel-shg-injector-state-machine.yaml'
    machines = {
        'seed_source': {f'seed_{i:02d}': SubsystemStateMachine.from_yaml(seed) for i in range(1, 4)},
        'shg_injector': {'shg_01': SubsystemStateMachine.from_yaml(shg)},
    }
    return load_laser_joint_fanout_experiment(machines)


class OperatorSession:
    """All UI commands and watchdog ticks use the same lock; no direct UI FSM writes."""
    def __init__(self, log_path):
        self.lock = RLock()
        self.journal = Journal(log_path)
        self.flow = build_flow()
        self.cache = {}
        root = Path(__file__).resolve().parent / 'models'
        self.models = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in root.glob('*.yaml')}
        self._checkpoint('session_started')

    def machines(self):
        return {f'{group}:{name}': machine for group, items in self.flow.machines.items()
                for name, machine in items.items()}

    def state(self):
        flow = asdict(self.flow.snapshot())
        machines = self.machines()
        active = self.flow.snapshot().active
        pending = [] if active is None else [target for target, task in active.task_ids
                    if machines[target].snapshot().task_id == task
                    and machines[target].snapshot().active_action is not None]
        payload = dict(flow=flow, machines={key: asdict(m.snapshot()) for key, m in machines.items()},
                       pending=pending, nodes=[asdict(n) for n in self.flow.nodes])
        for node in payload['nodes']:
            node['label'] = '联合条件确认' if node['is_gate'] else next(
                a['business_action'] for a in next(iter(self.flow.machines[node['target']].values())).model['actions']
                if a['id'] == node['action'])
        return json.loads(json.dumps(payload, ensure_ascii=False))

    def _checkpoint(self, reason, **fields):
        self.journal.append('checkpoint', self.flow.snapshot().run_id,
                            reason=reason, state=self.state(), model_hashes=self.models, **fields)

    def _capture(self, positions, node_id, command_id):
        for target, machine in self.machines().items():
            for record in machine.history[positions.get(target, 0):]:
                self.journal.append('transition', self.flow.snapshot().run_id,
                    target=target, node_id=node_id, command_id=command_id,
                    task_id=record.after.task_id, action=record.action_id, **asdict(record))

    def _tick(self):
        positions = {key: len(m.history) for key, m in self.machines().items()}
        active = self.flow.snapshot().active
        result = self.flow.check_timeout()
        if result is not None:
            self._capture(positions, active.node_id, 'watchdog')
            self._checkpoint('timeout')

    def tick(self):
        with self.lock:
            self._tick()

    def view(self):
        with self.lock:
            return dict(state=self.state(), events=deepcopy(self.journal.events), log=str(self.journal.path))

    def command(self, request):
        with self.lock:
            if not isinstance(request, dict):
                raise ValueError('命令必须为对象')
            command_id = request.get('command_id')
            if not isinstance(command_id, str) or not command_id or len(command_id) > 128:
                raise ValueError('需要有效 command_id')
            fingerprint = json.dumps(request, sort_keys=True, ensure_ascii=False)
            if command_id in self.cache:
                previous, response = self.cache[command_id]
                if previous != fingerprint:
                    raise ValueError('同一 command_id 不得用于不同命令')
                return deepcopy(response)
            self._tick()  # A late result cannot outrun deadline evaluation.
            command = request.get('command')
            run_id = self.flow.snapshot().run_id
            positions = {key: len(m.history) for key, m in self.machines().items()}
            active = self.flow.snapshot().active
            node_id = active.node_id if active else None
            self.journal.append('command', run_id, command_id=command_id,
                                request=request, actor='local_operator')
            try:
                if request.get('run_id') != run_id:
                    raise ValueError('实验已切换，请刷新后操作')
                if command == 'new_run':
                    if self.flow.snapshot().status not in {'failed', 'succeeded'}:
                        raise ValueError('请先结束当前实验')
                    if self.flow.snapshot().compensation_required:
                        raise ValueError('请先完成补偿')
                    self.flow = build_flow()  # New simulation, never a real-device reset.
                    positions = {}
                elif command == 'dispatch':
                    dispatched = self.flow.dispatch_next()
                    node_id = dispatched.node_id if dispatched else None
                elif command in {'success', 'failure'}:
                    target = request.get('target')
                    if active is None or request.get('task_id') != dict(active.task_ids).get(target):
                        raise ValueError('反馈不匹配当前任务')
                    self.flow.complete(active, target, success=command == 'success',
                        evidence=simulated_success_evidence(active.action) if command == 'success' else None)
                elif command == 'fault':
                    self.flow.inject_fault(request.get('target'), request.get('exception', 'fault_lock'))
                elif command == 'interlock':
                    if self.flow.snapshot().interlock_triggered:
                        raise ValueError('联锁已经锁定')
                    self.flow.trigger_interlock(request.get('reason') or 'operator_interlock')
                elif command == 'compensate':
                    # Clearly synthetic, user-requested evidence; never inferred device safety.
                    self.flow.run_compensation({key: simulated_success_evidence('abort_reset')
                                                for key in self.machines()})
                else:
                    raise ValueError('未知命令')
                response = dict(ok=True)
            except (ValueError, KeyError) as exc:
                response = dict(ok=False, error=str(exc))
            self._capture(positions, node_id, command_id)
            self._checkpoint(command, command_id=command_id, outcome=response)
            self.cache[command_id] = (fingerprint, deepcopy(response))
            return response
