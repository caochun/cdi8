from __future__ import annotations

from dataclasses import replace
import unittest

from gxlf_sim_system.joint_fanout_experiment import (
    JointFanoutExperimentError, JointFanoutSequentialExperiment,
    load_laser_joint_fanout_experiment,
)
from gxlf_sim_system.simulation import simulated_success_evidence
from gxlf_sim_system.subsystem_fsm import SubsystemStateMachine


SEED = 'gxlf_sim_system/models/excel-seed-source-state-machine.yaml'
SHG = 'gxlf_sim_system/models/excel-shg-injector-state-machine.yaml'


def machines():
    return {
        'seed_source': {f'seed_{i:02d}': SubsystemStateMachine.from_yaml(SEED) for i in range(1, 4)},
        'shg_injector': {'shg_01': SubsystemStateMachine.from_yaml(SHG)},
    }


def finish(flow):
    dispatch = flow.dispatch_next()
    assert dispatch is not None
    result = None
    for target, _ in dispatch.task_ids:
        result = flow.complete(dispatch, target, success=True,
                               evidence=simulated_success_evidence(dispatch.action))
    if result is not None and result.status != 'succeeded':
        raise AssertionError(result.reason)
    return dispatch, result


class JointFanoutExperimentTest(unittest.TestCase):
    def setUp(self):
        self.machines = machines()
        self.flow = load_laser_joint_fanout_experiment(self.machines)

    def test_seed_fanout_and_single_shg_joint_golden_path(self):
        # Six action nodes: seed/shg self-test, check and configure.
        for _ in range(6):
            dispatch, result = finish(self.flow)
            if result is not None:
                self.assertEqual(result.completed_targets, ('shg_injector:shg_01',)
                                 if dispatch.node_id.startswith('shg_') else result.completed_targets)
        finish(self.flow)  # seed emit fan-out
        # Advancing to seed_emit evaluates the gate first.
        self.assertEqual(dict(self.flow.snapshot().node_states)['laser_ready_gate'], 'succeeded')
        finish(self.flow)  # single SHG emit
        self.assertEqual(self.flow.snapshot().status, 'succeeded')

    def test_any_seed_instance_failure_breaks_joint_flow(self):
        dispatch = self.flow.dispatch_next()
        self.assertEqual(dispatch.node_id, 'seed_self_test')
        result = self.flow.complete(dispatch, 'seed_source:seed_02', success=False)
        self.assertEqual(result.status, 'failed')
        self.assertEqual(result.failed_target, 'seed_source:seed_02')
        self.assertEqual(self.flow.snapshot().status, 'failed')
        self.assertTrue(all(machine.snapshot().active_action is None
                            for machine in self.machines['seed_source'].values()))

    def test_shg_failure_breaks_joint_flow_after_seed_group_success(self):
        finish(self.flow)
        dispatch = self.flow.dispatch_next()
        self.assertEqual(dispatch.node_id, 'shg_self_test')
        result = self.flow.complete(dispatch, 'shg_injector:shg_01', success=False)
        self.assertEqual(result.failed_target, 'shg_injector:shg_01')
        self.assertEqual(self.flow.snapshot().status, 'failed')

    def test_gate_requires_all_seed_instances_and_the_single_shg(self):
        for _ in range(6):
            finish(self.flow)
        self.machines['seed_source']['seed_03'].invalidate('configuration_changed')
        with self.assertRaises(JointFanoutExperimentError):
            self.flow.dispatch_next()
        self.assertEqual(self.flow.snapshot().status, 'failed')
        reason = self.flow.snapshot().results[-1].reason
        self.assertIn('seed_source:seed_03', reason)

    def test_gate_does_not_use_or_semantics(self):
        for _ in range(6):
            finish(self.flow)
        self.machines['shg_injector']['shg_01'].invalidate('configuration_changed')
        with self.assertRaises(JointFanoutExperimentError):
            self.flow.dispatch_next()
        self.assertEqual(self.flow.snapshot().status, 'failed')
        self.assertIn('shg_injector:shg_01', self.flow.snapshot().results[-1].reason)

    def test_late_result_from_cancelled_seed_is_rejected(self):
        dispatch = self.flow.dispatch_next()
        self.flow.complete(dispatch, 'seed_source:seed_01', success=False)
        with self.assertRaises(JointFanoutExperimentError):
            self.flow.complete(dispatch, 'seed_source:seed_03', success=True,
                               evidence=simulated_success_evidence(dispatch.action))

    def test_instances_are_not_shared(self):
        dispatch = self.flow.dispatch_next()
        self.flow.complete(dispatch, 'seed_source:seed_01', success=True,
                           evidence=simulated_success_evidence(dispatch.action))
        self.assertEqual(self.machines['seed_source']['seed_01'].snapshot().current_state, '自检完成')
        self.assertEqual(self.machines['seed_source']['seed_02'].snapshot().current_state, '未上电/离线')
        self.assertEqual(self.machines['seed_source']['seed_03'].snapshot().current_state, '未上电/离线')

    def test_model_binding_requires_all_declared_instances(self):
        invalid = machines()
        del invalid['seed_source']['seed_03']
        with self.assertRaises(JointFanoutExperimentError):
            load_laser_joint_fanout_experiment(invalid)

    def test_timeout_fails_active_node_and_cancels_peer_tasks(self):
        dispatch = self.flow.dispatch_next()
        self.flow._active_started_at -= 11
        result = self.flow.check_timeout(now=self.flow._active_started_at + 11)
        self.assertEqual(result.status, 'failed')
        self.assertEqual(result.failed_target, 'seed_source:seed_01')
        self.assertEqual(self.machines['seed_source']['seed_01'].snapshot().current_state, '执行超时')
        self.assertTrue(all(machine.snapshot().active_action is None
                            for machine in self.machines['seed_source'].values()))

    def test_explicit_fault_injection_fails_group(self):
        self.flow.dispatch_next()
        result = self.flow.inject_fault('seed_source:seed_02', 'fault_lock')
        self.assertEqual(result.failed_target, 'seed_source:seed_02')
        self.assertEqual(self.machines['seed_source']['seed_02'].snapshot().current_state, '故障锁定')

    def test_interlock_requires_and_executes_compensation(self):
        self.flow.dispatch_next()
        result = self.flow.trigger_interlock('personnel_detected')
        self.assertEqual(result.status, 'failed')
        snapshot = self.flow.snapshot()
        self.assertTrue(snapshot.interlock_triggered)
        self.assertTrue(snapshot.compensation_required)
        evidence = {
            f'seed_source:seed_{index:02d}': simulated_success_evidence('abort_reset')
            for index in range(1, 4)
        }
        evidence['shg_injector:shg_01'] = simulated_success_evidence('abort_reset')
        records = self.flow.run_compensation(evidence)
        self.assertEqual(len(records), 4)
        self.assertFalse(self.flow.snapshot().compensation_required)
        self.assertEqual(self.flow.snapshot().status, 'failed')

    def test_compensation_cannot_be_claimed_without_evidence(self):
        self.flow.dispatch_next()
        self.flow.trigger_interlock()
        with self.assertRaises(JointFanoutExperimentError):
            self.flow.run_compensation({})


if __name__ == '__main__':
    unittest.main()
