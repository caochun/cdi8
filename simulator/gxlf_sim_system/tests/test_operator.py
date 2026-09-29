from dataclasses import asdict
from pathlib import Path
import json
import tempfile
from threading import Thread
import unittest
from unittest.mock import patch
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from uuid import uuid4

from gxlf_sim_system.operator import Journal, OperatorSession
from gxlf_sim_system.operator_server import make_server


class OperatorTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / 'events.jsonl'
        self.session = OperatorSession(self.path)

    def command(self, name, **kwargs):
        return self.session.command(dict(command_id=uuid4().hex, command=name,
                                         run_id=self.session.flow.snapshot().run_id, **kwargs))

    def test_full_run_and_replay_without_executing_again(self):
        for _ in range(14):
            self.assertTrue(self.command('dispatch')['ok'])
            active = self.session.flow.snapshot().active
            for target, task in reversed(active.task_ids):
                self.assertTrue(self.command('success', target=target, task_id=task)['ok'])
        self.assertEqual(self.session.flow.snapshot().status, 'succeeded')
        events = Journal.read(self.path)
        self.assertEqual(len([e for e in events if e['kind'] == 'transition']), 56)
        for state in self.session.state()['machines'].values():
            self.assertEqual(state['current_state'], '关机完成')
            self.assertEqual(state['business_state'], '未就绪')
        for target, machine in self.session.machines().items():
            records = [e for e in events if e['kind'] == 'transition' and e['target'] == target]
            self.assertEqual([e['after'] for e in records], [asdict(r.after) for r in machine.history])
        fresh = Journal(self.path)
        self.assertEqual(fresh.replay(len(events)), self.session.view()['state'])
        self.assertEqual(fresh.replay(1)['flow']['status'], 'idle')
        self.assertEqual(self.session.flow.snapshot().status, 'succeeded')

    def test_idempotent_command_and_conflicting_id(self):
        req = dict(command='dispatch', command_id='one', run_id=self.session.flow.snapshot().run_id)
        response = self.session.command(req)
        count = len(self.session.journal.events)
        self.assertEqual(self.session.command(req), response)
        self.assertEqual(len(self.session.journal.events), count)
        with self.assertRaises(ValueError):
            self.session.command({**req, 'command': 'interlock'})

    def test_rejected_feedback_is_logged_without_changing_instance(self):
        self.command('dispatch')
        before = self.session.state()
        result = self.command('success', target='seed_source:seed_01', task_id='wrong')
        self.assertFalse(result['ok'])
        self.assertEqual(self.session.state(), before)
        event = self.session.journal.events[-1]
        self.assertFalse(event['outcome']['ok'])
        self.assertEqual(self.session.journal.replay(event['seq']), before)

    def test_timeout_runs_without_ui_and_late_feedback_cannot_win(self):
        with patch('gxlf_sim_system.joint_fanout_experiment.time.monotonic', return_value=100):
            self.command('dispatch')
        active = self.session.flow.snapshot().active
        with patch('gxlf_sim_system.joint_fanout_experiment.time.monotonic', return_value=110):
            result = self.command('success', target=active.task_ids[0][0], task_id=active.task_ids[0][1])
        self.assertFalse(result['ok'])
        self.assertEqual(self.session.flow.snapshot().status, 'failed')
        self.assertTrue(any(e.get('reason') == 'timeout' for e in self.session.journal.events))

    def test_interlock_compensation_and_new_run_audited(self):
        self.command('dispatch')
        old_run = self.session.flow.snapshot().run_id
        self.assertTrue(self.command('interlock')['ok'])
        self.assertFalse(self.command('new_run')['ok'])
        self.assertTrue(self.command('compensate')['ok'])
        self.assertEqual(self.session.flow.snapshot().status, 'failed')
        records = [e for e in self.session.journal.events if e.get('action') == 'abort_reset']
        self.assertEqual(len(records), 8)
        self.assertTrue(self.command('new_run')['ok'])
        self.assertNotEqual(old_run, self.session.flow.snapshot().run_id)
        result = self.session.command(dict(command='dispatch', command_id='stale', run_id=old_run))
        self.assertFalse(result['ok'])
        self.assertEqual(self.session.flow.snapshot().status, 'idle')

    def test_fault_and_compensation_failure_are_replayable(self):
        self.command('dispatch')
        self.assertTrue(self.command('fault', target='seed_source:seed_02', exception='communication_error')['ok'])
        self.assertEqual(self.session.flow.snapshot().status, 'failed')
        self.command('interlock')
        machine = self.session.machines()['shg_injector:shg_01']
        with patch.object(machine, 'complete_success', side_effect=ValueError('补偿反馈丢失')):
            self.assertFalse(self.command('compensate')['ok'])
        self.assertTrue(self.session.flow.snapshot().compensation_required)
        self.assertEqual(self.session.journal.replay(len(self.session.journal.events)), self.session.view()['state'])
        self.assertTrue(any(e.get('action') == 'abort_reset' for e in self.session.journal.events))

    def test_concurrent_double_click_starts_one_node(self):
        req = dict(command='dispatch', command_id='double', run_id=self.session.flow.snapshot().run_id)
        threads = [Thread(target=self.session.command, args=(req,)) for _ in range(2)]
        for t in threads: t.start()
        for t in threads: t.join()
        self.assertEqual(len([e for e in self.session.journal.events if e['kind'] == 'command']), 1)
        self.assertEqual(len(self.session.machines()['seed_source:seed_01'].history), 1)

    def test_journal_reopen_and_corruption(self):
        old_count = len(self.session.journal.events)
        reopened = OperatorSession(self.path)
        self.assertEqual(reopened.journal.events[-1]['seq'], old_count + 1)
        with self.path.open('a') as f: f.write('{truncated')
        with self.assertRaisesRegex(ValueError, '日志第'):
            Journal(self.path)

    def test_http_readonly_replay_and_token_protection(self):
        server = make_server(self.session, port=0)
        worker = Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            base = f'http://127.0.0.1:{server.server_port}'
            with urlopen(base) as response: page = response.read().decode()
            self.assertIn('GXLF 实验操作台', page)
            token = page.split("const token='")[1].split("'")[0]
            data = dict(command='dispatch', command_id=uuid4().hex,
                        run_id=self.session.flow.snapshot().run_id)
            with self.assertRaises(HTTPError) as ctx:
                urlopen(Request(base+'/api/commands', data=json.dumps(data).encode()))
            self.assertEqual(ctx.exception.code, 403)
            req = Request(base+'/api/commands', data=json.dumps(data).encode(),
                          headers={'X-Operator-Token': token})
            with urlopen(req) as response: self.assertTrue(json.load(response)['ok'])
            before = self.session.state()
            with urlopen(base+'/api/replay?seq=1') as response:
                self.assertEqual(json.load(response)['flow']['status'], 'idle')
            self.assertEqual(self.session.state(), before)
            with urlopen(base+'/api/log') as response:
                self.assertEqual(len(response.read().splitlines()), len(self.session.journal.events))
        finally:
            server.shutdown()
            server.server_close()
            worker.join()


if __name__ == '__main__':
    unittest.main()
