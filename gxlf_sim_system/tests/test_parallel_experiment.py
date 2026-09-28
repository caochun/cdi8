from __future__ import annotations

from dataclasses import replace
import unittest

from gxlf_sim_system.parallel_experiment import (
    ParallelExperimentError, ParallelFanoutExperiment,
    load_seed_source_fanout_experiment,
)
from gxlf_sim_system.simulation import simulated_success_evidence
from gxlf_sim_system.subsystem_fsm import SubsystemStateMachine


MODEL = 'gxlf_sim_system/models/excel-seed-source-state-machine.yaml'


def instances():
    return {
        f'seed_{index:02d}': SubsystemStateMachine.from_yaml(MODEL)
        for index in range(1, 4)
    }


def complete_all(flow, dispatch, *, omit=None):
    result = None
    for target, _task_id in dispatch.task_ids:
        if target == omit:
            continue
        result = flow.complete(
            dispatch, target, success=True,
            evidence=simulated_success_evidence(dispatch.action),
        )
    return result


class ParallelFanoutExperimentTest(unittest.TestCase):
    def setUp(self):
        self.systems = instances()
        self.flow = load_seed_source_fanout_experiment(self.systems)

    def test_all_instances_are_dispatched_before_feedback(self):
        dispatch = self.flow.dispatch_next()
        self.assertEqual(set(target for target, _ in dispatch.task_ids), set(self.systems))
        self.assertEqual(len({task_id for _, task_id in dispatch.task_ids}), 3)
        self.assertTrue(all(machine.snapshot().task_state == 'executing' for machine in self.systems.values()))
        self.assertEqual(self.flow.snapshot().status, 'running')

    def test_all_success_aggregates_to_one_node_success(self):
        dispatch = self.flow.dispatch_next()
        result = complete_all(self.flow, dispatch)
        self.assertIsNotNone(result)
        self.assertEqual(result.status, 'succeeded')
        self.assertEqual(result.completed_targets, ('seed_01', 'seed_02', 'seed_03'))
        self.assertEqual(self.flow.snapshot().status, 'running')

        next_dispatch = self.flow.dispatch_next()
        result = complete_all(self.flow, next_dispatch)
        self.assertEqual(result.status, 'succeeded')
        self.assertEqual(self.flow.snapshot().status, 'succeeded')
        self.assertTrue(all(machine.snapshot().business_state == '就绪' for machine in self.systems.values()))

    def test_partial_feedback_keeps_node_running(self):
        dispatch = self.flow.dispatch_next()
        result = self.flow.complete(dispatch, 'seed_01', success=True,
                                    evidence=simulated_success_evidence(dispatch.action))
        self.assertIsNone(result)
        self.assertEqual(self.flow.snapshot().status, 'running')
        self.assertEqual(dict(self.flow.snapshot().node_states)['parallel_self_test'], 'running')
        with self.assertRaises(ParallelExperimentError):
            self.flow.dispatch_next()
        complete_all(self.flow, dispatch, omit='seed_01')

    def test_any_failed_instance_fails_the_whole_node(self):
        dispatch = self.flow.dispatch_next()
        result = self.flow.complete(dispatch, 'seed_02', success=False)
        self.assertEqual(result.status, 'failed')
        self.assertEqual(result.failed_target, 'seed_02')
        self.assertEqual(self.flow.snapshot().status, 'failed')
        self.assertEqual(dict(self.flow.snapshot().node_states)['parallel_self_test'], 'failed')
        self.assertTrue(all(machine.snapshot().active_action is None for machine in self.systems.values()))
        with self.assertRaises(ParallelExperimentError):
            self.flow.complete(dispatch, 'seed_01', success=True,
                               evidence=simulated_success_evidence(dispatch.action))

    def test_one_instance_exception_fails_fanout(self):
        dispatch = self.flow.dispatch_next()
        result = self.flow.report_exception('seed_03', 'communication_error')
        self.assertEqual(result.failed_target, 'seed_03')
        self.assertEqual(result.status, 'failed')
        self.assertEqual(self.systems['seed_03'].snapshot().business_state, '异常')
        self.assertTrue(all(machine.snapshot().active_action is None for machine in self.systems.values()))
        with self.assertRaises(ParallelExperimentError):
            self.flow.complete(dispatch, 'seed_01', success=True,
                               evidence=simulated_success_evidence(dispatch.action))

    def test_wrong_task_or_target_feedback_is_rejected(self):
        dispatch = self.flow.dispatch_next()
        wrong_task = replace(dispatch, task_ids=(('seed_01', 'wrong'), ('seed_02', dispatch.task_ids[1][1]), ('seed_03', dispatch.task_ids[2][1])))
        with self.assertRaises(ParallelExperimentError):
            self.flow.complete(wrong_task, 'seed_01', success=True,
                               evidence=simulated_success_evidence(dispatch.action))
        with self.assertRaises(ParallelExperimentError):
            self.flow.complete(dispatch, 'missing', success=True,
                               evidence=simulated_success_evidence(dispatch.action))

    def test_late_feedback_after_node_failure_is_rejected(self):
        dispatch = self.flow.dispatch_next()
        self.flow.complete(dispatch, 'seed_01', success=False)
        for target in ('seed_02', 'seed_03'):
            with self.assertRaises(ParallelExperimentError):
                self.flow.complete(dispatch, target, success=True,
                                   evidence=simulated_success_evidence(dispatch.action))

    def test_instances_have_independent_state_and_history(self):
        dispatch = self.flow.dispatch_next()
        self.flow.complete(dispatch, 'seed_01', success=True,
                           evidence=simulated_success_evidence(dispatch.action))
        self.assertEqual(self.systems['seed_01'].snapshot().current_state, '自检完成')
        self.assertEqual(self.systems['seed_02'].snapshot().task_state, 'executing')
        self.assertEqual(self.systems['seed_02'].snapshot().current_state, '未上电/离线')
        self.assertEqual(len(self.systems['seed_01'].history), 2)
        self.assertEqual(len(self.systems['seed_02'].history), 1)
        self.assertEqual(self.systems['seed_03'].snapshot().current_state, '未上电/离线')

    def test_model_rejects_non_all_success_and_bad_bindings(self):
        systems = instances()
        base = {
            'kind': 'ParallelFanoutExperiment', 'aggregation': 'all_success',
            'instances': list(systems), 'nodes': [{'id': 'n', 'action': 'power_on_self_test', 'phase': 'x'}],
        }
        with self.assertRaises(ParallelExperimentError):
            ParallelFanoutExperiment({**base, 'aggregation': 'partial_success'}, systems)
        with self.assertRaises(ParallelExperimentError):
            ParallelFanoutExperiment({**base, 'instances': ['seed_01']}, systems)

    def test_preflight_rejects_group_without_starting_any_instance(self):
        self.systems['seed_02'].start('power_on_self_test')
        flow = load_seed_source_fanout_experiment(self.systems)
        with self.assertRaises(ParallelExperimentError):
            flow.dispatch_next()
        self.assertTrue(self.systems['seed_01'].snapshot().active_action is None)
        self.assertTrue(self.systems['seed_03'].snapshot().active_action is None)


if __name__ == '__main__':
    unittest.main()
