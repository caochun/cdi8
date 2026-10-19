"""Executable model validation and isolated trials, independent of Tango."""
from pathlib import Path
import tempfile
import unittest
from gxlf_component_simulator.component_model import MODEL_DIRECTORY
from gxlf_component_simulator.workbench import Trials


class WorkbenchTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.trials = Trials(self.directory.name)
        self.xml = (MODEL_DIRECTORY / 'seed-source-state-machine.scxml').read_text()

    def tearDown(self):
        for runtime in self.trials.sessions.values():
            runtime.close()
        self.directory.cleanup()

    def test_rejects_unsupported_semantics_instead_of_silently_ignoring(self):
        for addition in ['<parallel id="p"/>', '<script>throw new Error()</script>', '<state id="bad"><transition event="x" target="bad" cond="true"/></state>']:
            result = self.trials.validate(self.xml.replace('</scxml>', addition + '</scxml>'))
            self.assertFalse(result['valid'], result)
            self.assertTrue(result['diagnostics'])
        self.assertTrue(self.trials.validate(self.xml)['valid'])

    def test_trials_have_independent_states_and_keep_their_original_model(self):
        first = self.trials.create(self.xml)
        second = self.trials.create(self.xml)
        accepted = self.trials.operate(first['id'], 'commands', {'action': 'power_on_check'})
        command = accepted['snapshot']['active_command_id']
        self.assertIsNotNone(command)
        done = self.trials.operate(first['id'], 'simulate', {'command_id': command, 'outcome': 'success'})
        self.assertEqual(done['snapshot']['state_id'], 'powerOnChecked')
        self.assertEqual(self.trials.view(second['id'])['snapshot']['state_id'], second['snapshot']['state_id'])
        self.assertEqual(done['xml'], self.xml)
        self.assertGreater(len(done['history']), 0)

    def test_xml_errors_have_editor_positions(self):
        result = self.trials.validate('<scxml>\n<state></scxml>')
        self.assertFalse(result['valid'])
        self.assertEqual(result['diagnostics'][0]['line'], 2)


try:
    import tango
except ImportError:
    tango = None


@unittest.skipIf(tango is None, 'PyTango not installed')
class WorkbenchEnvironmentTest(unittest.TestCase):
    def test_environment_uses_revision_files_single_server_and_fresh_state(self):
        from gxlf_component_simulator.tango.workbench import Workbench
        class InitialBridge:
            def close(self):
                pass
        with tempfile.TemporaryDirectory() as directory:
            environment = Workbench(InitialBridge(), directory=directory)
            xml = (MODEL_DIRECTORY / 'seed-source-state-machine.scxml').read_text()
            config = {'conditions': {'alignment_ready': False}, 'devices': [
                {'id': 'custom_01', 'component': 'custom', 'revision': 'revision-one', 'xml': xml},
                {'id': 'custom_02', 'component': 'custom', 'revision': 'revision-one', 'xml': xml}]}
            try:
                first = environment.configure(config)
                self.assertEqual(environment.bridge.snapshot('custom_01')['model_revision'], 'revision-one')
                devices = environment.bridge.catalog()
                self.assertEqual(len({d['address'].split('/gxlf/')[0] for d in devices}), 1)
                self.assertFalse(environment.bridge.snapshot('context_01')['conditions']['alignment_ready'])
                environment.bridge.call('custom_01', 'Execute', {'command_id': 'trial', 'action': 'power_on_check'})
                environment.bridge.call('custom_01', 'Simulate', {'command_id': 'trial', 'outcome': 'success'})
                second = environment.configure(config)
                self.assertNotEqual(first['id'], second['id'])
                self.assertEqual(environment.bridge.snapshot('custom_01')['state_id'], 'statusUnknown')
            finally:
                environment.close()
