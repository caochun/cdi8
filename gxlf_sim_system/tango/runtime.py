"""Durable single-device behavior; command IDs are idempotency keys."""
from dataclasses import asdict
import json
from pathlib import Path
import sqlite3
from threading import RLock

from ..subsystem_fsm import SubsystemStateMachine, StateMachineError
from ..simulation import simulated_success_evidence


class DeviceRuntime:
    def __init__(self, model_path, database):
        self.lock = RLock()
        Path(database).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(database, check_same_thread=False)
        self.db.execute('CREATE TABLE IF NOT EXISTS device_state (id INTEGER PRIMARY KEY, snapshot TEXT NOT NULL)')
        self.db.execute('CREATE TABLE IF NOT EXISTS commands (id TEXT PRIMARY KEY, action TEXT, result TEXT NOT NULL)')
        self.machine = SubsystemStateMachine.from_yaml(model_path)
        row = self.db.execute('SELECT snapshot FROM device_state WHERE id=1').fetchone()
        if row:
            state = json.loads(row[0])
            # Simulator restart restoration; workflow code never touches these internals.
            self.machine._active_action = state.pop('active_action')
            self.machine._task_id = state.pop('task_id')
            self.machine._state = state
            self.machine._validate_state(state)

    def snapshot(self):
        with self.lock:
            return asdict(self.machine.snapshot())

    def _save(self, command_id, action, status):
        result = dict(command_id=command_id, action=action, status=status, snapshot=self.snapshot())
        self.db.execute('INSERT OR REPLACE INTO device_state VALUES(1,?)', (json.dumps(self.snapshot()),))
        self.db.execute('INSERT OR REPLACE INTO commands VALUES(?,?,?)', (command_id, action, json.dumps(result)))
        self.db.commit()
        return result

    def _get(self, command_id):
        row = self.db.execute('SELECT result FROM commands WHERE id=?', (command_id,)).fetchone()
        return json.loads(row[0]) if row else None

    def execute(self, request):
        with self.lock:
            command_id, action = request['command_id'], request['action']
            if not isinstance(command_id, str) or not command_id or len(command_id)>128:
                raise ValueError('invalid command_id')
            previous = self._get(command_id)
            if previous:
                if previous['action'] not in (None, action):
                    raise ValueError('command id reused for another action')
                return previous
            try:
                self.machine.start(action)
            except StateMachineError:
                return self._save(command_id, action, 'rejected')
            return self._save(command_id, action, 'accepted')

    def result(self, command_id):
        with self.lock:
            result = self._get(command_id)
            if result is None:
                raise ValueError('unknown command')
            return result

    def cancel(self, command_id):
        with self.lock:
            result = self._get(command_id)
            if result and result['status'] != 'accepted':
                return result
            if result and self.machine.snapshot().task_id == result['snapshot']['task_id']:
                self.machine.cancel_active_task('central_cancel')
            return self._save(command_id, result['action'] if result else None, 'cancelled')

    def simulate(self, request):
        with self.lock:
            command_id, outcome = request['command_id'], request['outcome']
            result = self.result(command_id)
            if result['status'] != 'accepted':
                return result
            action = result['action']
            if outcome == 'success':
                state = self.machine.complete_success(task_id=result['snapshot']['task_id'],
                    evidence=simulated_success_evidence(action))
                status = state.task_state
            elif outcome == 'failure':
                self.machine.complete_failure(task_id=result['snapshot']['task_id']);status='failed'
            elif outcome in ('communication_error', 'fault_lock'):
                self.machine.report_exception(outcome);status='failed'
            else:
                raise ValueError('unknown simulation outcome')
            return self._save(command_id, action, status)
