"""Real Tango event/command bridge integration, optional without PyTango."""
import json
from pathlib import Path
import tempfile
from threading import Thread
import time
import unittest
from urllib.request import Request, urlopen

try:
    import tango
except ImportError:
    tango = None


@unittest.skipIf(tango is None, 'PyTango not installed')
class ControlBridgeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from tango.test_context import MultiDeviceTestContext
        from gxlf_component_simulator.tango.device import ComponentDevice
        from gxlf_component_simulator.tango.preparation_context import PreparationContextDevice
        from gxlf_component_simulator.tango.control_bridge import TangoBridge, create_server
        cls.directory = tempfile.TemporaryDirectory()
        cls.context = MultiDeviceTestContext([
            {'class': ComponentDevice, 'devices': [{'name': 'gxlf/seed/01', 'properties': {
                'component_model': 'seed-source-reference-v2',
                'database_path': str(Path(cls.directory.name) / 'seed.sqlite3')}}]},
            {'class': PreparationContextDevice, 'devices': [{'name': 'gxlf/context/01'}]},
        ], process=True, host='127.0.0.1', timeout=20)
        cls.context.__enter__()
        try:
            cls.bridge = TangoBridge({'seed_01': {'component': 'seed', 'address': cls.context.get_device_access('gxlf/seed/01')},
                                      'context_01': {'component': 'context', 'address': cls.context.get_device_access('gxlf/context/01')}})
            cls.server = create_server(cls.bridge, 0)
            cls.worker = Thread(target=cls.server.serve_forever, daemon=True)
            cls.worker.start()
            cls.url = 'http://127.0.0.1:' + str(cls.server.server_port)
        except Exception:
            cls.context.__exit__(None, None, None)
            cls.directory.cleanup()
            raise

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.bridge.close()
        cls.server.server_close()
        cls.context.__exit__(None, None, None)
        cls.directory.cleanup()

    def request(self, path, data=None):
        with urlopen(Request(self.url+path, data=json.dumps(data).encode() if data is not None else None,
                             headers={'Content-Type': 'application/json'}), timeout=5) as response:
            return json.load(response)

    def until(self, stream, predicate):
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            value = json.loads(stream.readline())
            if predicate(value):
                return value
        self.fail('Expected Tango event was not received')

    def test_commands_produce_real_change_events_and_replay_after_reconnect(self):
        with urlopen(self.url+'/events', timeout=10) as stream:
            self.until(stream, lambda e: e['type'] == 'ready')
            request = {'command_id': 'bridge-power-check', 'action': 'power_on_check', 'parameters': {}}
            self.assertEqual(self.request('/devices/seed_01/commands', request)['status'], 'accepted')
            accepted = self.until(stream, lambda e: e.get('snapshot', {}).get('active_command_id') == 'bridge-power-check')
            self.assertEqual(accepted['origin'], 'tango_event')
            self.request('/devices/seed_01/simulate', {'command_id': 'bridge-power-check', 'outcome': 'success'})
            done = self.until(stream, lambda e: e.get('snapshot', {}).get('state_id') == 'powerOnChecked')
            self.assertEqual(done['origin'], 'tango_event')
            result = self.request('/devices/seed_01/commands/bridge-power-check')
            self.assertEqual(result['status'], 'succeeded')
            # Lost response retry must return the original task without a new action.
            self.assertEqual(self.request('/devices/seed_01/commands', request)['status'], 'succeeded')
        with urlopen(self.url+'/events', timeout=10) as stream:
            replay = self.until(stream, lambda e: e.get('device_id') == 'seed_01')
            self.assertEqual(replay['snapshot']['state_id'], 'powerOnChecked')
            self.until(stream, lambda e: e['type'] == 'ready')

    def test_external_conditions_are_explicit_and_emit_events(self):
        initial = self.request('/devices/context_01')['conditions']
        self.assertFalse(any(initial.values()))
        with urlopen(self.url+'/events', timeout=10) as stream:
            self.until(stream, lambda e: e['type'] == 'ready')
            self.request('/devices/context_01/conditions', {'safety_permit': True})
            event = self.until(stream, lambda e: e.get('snapshot', {}).get('conditions', {}).get('safety_permit'))
            self.assertEqual(event['origin'], 'tango_event')
            self.request('/devices/context_01/commands', {'command_id': 'repeat-01', 'action': 'preamp_repeat'})
            self.assertFalse(self.request('/devices/context_01')['conditions']['preamp_repeat_ready'])
            self.request('/devices/context_01/complete', {'command_id': 'repeat-01', 'outcome': 'success'})
            self.assertTrue(self.request('/devices/context_01')['conditions']['preamp_repeat_ready'])


if __name__ == '__main__':
    unittest.main()
