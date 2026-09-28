from __future__ import annotations

from dataclasses import replace
import unittest

from gxlf_sim_system.composite_experiment import (
    CompositeExperimentError, CompositeSequentialExperiment, load_laser_joint_experiment,
)
from gxlf_sim_system.simulation import simulated_success_evidence
from gxlf_sim_system.subsystem_fsm import StateMachineError, SubsystemStateMachine


SEED_MODEL = 'gxlf_sim_system/models/excel-seed-source-state-machine.yaml'
SHG_MODEL = 'gxlf_sim_system/models/excel-shg-injector-state-machine.yaml'


def make_systems():
    return {
        'seed_source': SubsystemStateMachine.from_yaml(SEED_MODEL),
        'shg_injector': SubsystemStateMachine.from_yaml(SHG_MODEL),
    }


def succeed(flow):
    dispatch = flow.dispatch_next()
    result = flow.complete(dispatch, success=True, evidence=simulated_success_evidence(dispatch.action))
    if result.status != 'succeeded':
        raise AssertionError(result.reason)
    return result


def prepare_seed_only(systems):
    seed = systems['seed_source']
    for action in ('power_on_self_test', 'function_check', 'parameter_dispatch'):
        started = seed.start(action)
        seed.complete_success(task_id=started.task_id, evidence=simulated_success_evidence(action))


class CompositeExperimentTest(unittest.TestCase):
    def setUp(self):
        self.systems = make_systems()
        self.flow = load_laser_joint_experiment(self.systems)

    def test_joint_golden_path_requires_both_systems(self):
        for _ in range(6):
            succeed(self.flow)
        result = succeed(self.flow)
        self.assertEqual(result.node_id, 'seed_emit')
        gate_result = next(r for r in self.flow.snapshot().results if r.node_id == 'laser_ready_gate')
        self.assertEqual(gate_result.status, 'succeeded')
        self.assertEqual(dict(self.flow.snapshot().node_states)['laser_ready_gate'], 'succeeded')
        gate_states = dict(gate_result.states)
        self.assertEqual(gate_states['seed_source'].current_state, '参数下发完成')
        self.assertEqual(gate_states['shg_injector'].current_state, '参数下发完成')
        self.assertEqual(result.node_id, 'seed_emit')
        self.assertEqual(succeed(self.flow).node_id, 'shg_emit')
        self.assertEqual(self.flow.snapshot().status, 'succeeded')

    def test_gate_blocks_when_seed_source_is_not_ready(self):
        # Complete both configuration nodes, then invalidate only seed configuration.
        for _ in range(6):
            succeed(self.flow)
        self.systems['seed_source'].invalidate('configuration_changed')
        with self.assertRaises(CompositeExperimentError):
            self.flow.dispatch_next()
        self.assertEqual(self.flow.snapshot().status, 'failed')
        self.assertIn('seed_source', self.flow.snapshot().results[-1].reason)

    def test_gate_blocks_when_shg_is_not_ready(self):
        for _ in range(6):
            succeed(self.flow)
        self.systems['shg_injector'].invalidate('configuration_changed')
        with self.assertRaises(CompositeExperimentError):
            self.flow.dispatch_next()
        self.assertEqual(self.flow.snapshot().status, 'failed')
        self.assertIn('shg_injector', self.flow.snapshot().results[-1].reason)

    def test_joint_gate_is_and_not_or(self):
        for _ in range(6):
            succeed(self.flow)
        self.systems['shg_injector'].invalidate('configuration_changed')
        with self.assertRaises(CompositeExperimentError):
            self.flow.dispatch_next()
        self.assertEqual(self.flow.snapshot().status, 'failed')

    def test_one_system_exception_invalidates_joint_run(self):
        for _ in range(2):
            succeed(self.flow)
        result = self.flow.report_exception('shg_injector', 'communication_error')
        self.assertEqual(result.status, 'failed')
        self.assertEqual(self.flow.snapshot().status, 'failed')
        self.assertEqual(self.systems['shg_injector'].snapshot().business_state, '异常')
        with self.assertRaises(CompositeExperimentError):
            self.flow.dispatch_next()

    def test_action_results_are_bound_to_the_target_system(self):
        dispatch = self.flow.dispatch_next()
        wrong = replace(dispatch, target='shg_injector')
        with self.assertRaises(CompositeExperimentError):
            self.flow.complete(wrong, success=True, evidence=simulated_success_evidence(dispatch.action))
        self.assertEqual(self.flow.snapshot().active, dispatch)
        self.flow.complete(dispatch, success=True, evidence=simulated_success_evidence(dispatch.action))

    def test_invalid_binding_and_gate_shape_are_rejected(self):
        systems = make_systems()
        base = {
            'kind': 'CompositeSequentialExperiment', 'systems': {
                'seed_source': 'laser_seed_source', 'shg_injector': 'shg_injector',
            }, 'nodes': [{'id': 'gate', 'phase': 'x', 'requires': {
                'seed_source': {'main_state': '就绪'},
            }}],
        }
        invalid = [
            {**base, 'systems': {'seed_source': 'other', 'shg_injector': 'shg_injector'}},
            {**base, 'nodes': [{**base['nodes'][0], 'target': 'seed_source'}]},
            {**base, 'nodes': [{**base['nodes'][0], 'requires': {'unknown': {'main_state': '就绪'}}}]},
            {**base, 'nodes': [{'id': 'action', 'phase': 'x', 'target': 'seed_source'}]},
        ]
        for model in invalid:
            with self.subTest(model=model), self.assertRaises(CompositeExperimentError):
                CompositeSequentialExperiment(model, systems)

    def test_every_system_keeps_an_independent_state_history(self):
        succeed(self.flow)
        self.assertEqual(len(self.systems['seed_source'].history), 2)
        self.assertEqual(len(self.systems['shg_injector'].history), 0)


if __name__ == '__main__':
    unittest.main()
