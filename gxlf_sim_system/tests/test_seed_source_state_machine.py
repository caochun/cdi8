from __future__ import annotations

from dataclasses import replace
import unittest

from gxlf_sim_system.simulation import simulated_success_evidence
from gxlf_sim_system.subsystem_fsm import (
    CompletionEvidence, StateMachineError, load_seed_source_state_machine,
)

PATH = ['power_on_self_test', 'function_check', 'parameter_dispatch',
        'seed_source_emit', 'laser_parameter_collect', 'standby_reset']
RECOVERY = dict(cause_cleared=True, reset_verified=True, safe_position=True, resources_released=True)


def execute(machine, action):
    started = machine.start(action)
    return machine.complete_success(task_id=started.task_id, evidence=simulated_success_evidence(action))


class SeedSourceStateMachineTest(unittest.TestCase):
    def setUp(self):
        self.machine = load_seed_source_state_machine()

    def ready_for(self, action):
        for previous in PATH[:PATH.index(action)]:
            execute(self.machine, previous)

    def test_excel_golden_path_preserves_D_H_K_O_separately(self):
        expected = [
            ('未就绪', '自检完成', '未就绪', '未上电/离线', '启动中、准备中'),
            ('就绪', '功能检查完成', '就绪', '业务就绪完成（具备倍频注入/旁路判定能力）', '业务就绪确认'),
            ('正常', '参数下发完成', '就绪', '参数配置正常', '结果确认/保持有效'),
            ('正常', '出光完成', '出光', '稳定出光正常', '结果确认/保持有效'),
            ('正常', '采集完成', '出光', '采集正常', '数据采集/后处理'),
            ('就绪', '复位/待机完成', '就绪', '复位/待机完成', '待机'),
        ]
        self.assertEqual(self.machine.snapshot().current_state, '未上电/离线')
        for action, fields in zip(PATH, expected):
            result = execute(self.machine, action)
            self.assertEqual((result.main_state, result.current_state, result.business_state,
                              result.key_state_description, result.state_definition), fields)
            self.assertEqual(result.key_state, result.current_state)
            self.assertEqual(self.machine.history[-1].after.state_definition, fields[-1])

    def test_preconditions_still_enforced(self):
        with self.assertRaises(StateMachineError):
            self.machine.start('seed_source_emit')
        execute(self.machine, 'power_on_self_test')
        with self.assertRaises(StateMachineError):
            self.machine.start('parameter_dispatch')

    def test_every_failure_invalidates_business_qualification(self):
        for action in PATH + ['abort_reset', 'shutdown']:
            with self.subTest(action=action):
                self.setUp()
                if action in PATH:
                    self.ready_for(action)
                started = self.machine.start(action)
                state = self.machine.complete_failure(task_id=started.task_id)
                self.assertEqual((state.main_state, state.business_state, state.task_state),
                                 ('异常', '异常', 'failed'))
                self.assertIsNone(state.active_action)

    def test_callback_alone_or_wrong_target_cannot_assert_success(self):
        samples = [CompletionEvidence(True, '自检完成'),
                   CompletionEvidence(False, '自检完成', simulated_success_evidence(PATH[0]).conditions),
                   CompletionEvidence(True, '错误状态', simulated_success_evidence(PATH[0]).conditions)]
        for evidence in samples:
            with self.subTest(evidence=evidence):
                self.setUp()
                started = self.machine.start(PATH[0])
                state = self.machine.complete_success(task_id=started.task_id, evidence=evidence)
                self.assertEqual(state.task_state, 'failed')
                self.assertEqual(state.business_state, '异常')
                self.assertEqual(self.machine.history[-1].outcome, 'invalid_feedback')

    def test_missing_each_readiness_condition_prevents_ready(self):
        for key in ('service_running', 'self_test_passed', 'function_check_passed', 'capabilities_ok',
                    'interlock_ok', 'safe_position', 'no_uncleared_fault', 'resources_released'):
            with self.subTest(condition=key):
                self.setUp()
                execute(self.machine, PATH[0])
                evidence = simulated_success_evidence('function_check')
                conditions = dict(evidence.conditions)
                del conditions[key]
                started = self.machine.start('function_check')
                state = self.machine.complete_success(task_id=started.task_id,
                                                       evidence=replace(evidence, conditions=conditions))
                self.assertEqual(state.business_state, '异常')

    def test_each_generic_exception_interrupts_executing_task(self):
        for exception in ('fault_lock', 'communication_error', 'execution_timeout'):
            with self.subTest(exception=exception):
                self.setUp()
                started = self.machine.start(PATH[0])
                result = self.machine.report_exception(exception)
                self.assertEqual((result.main_state, result.business_state, result.task_state),
                                 ('异常', '异常', 'failed'))
                self.assertEqual(result.state_definition, '异常处置')
                self.assertIsNone(result.active_action)
                self.assertEqual(self.machine.history[-1].action_id, PATH[0])
                with self.assertRaises(StateMachineError):
                    self.machine.complete_success(task_id=started.task_id,
                                                   evidence=simulated_success_evidence(PATH[0]))

    def test_unknown_exception_does_not_cancel_active_task(self):
        started = self.machine.start(PATH[0])
        with self.assertRaises(StateMachineError):
            self.machine.report_exception('unknown')
        self.assertEqual(self.machine.snapshot(), started)

    def test_abort_preempts_and_preserves_main_business_distinction(self):
        old = self.machine.start(PATH[0])
        abort = self.machine.start('abort_reset')
        cancelled = self.machine.history[-2]
        self.assertEqual(cancelled.event, 'task_cancelled')
        self.assertEqual(cancelled.after.task_state, 'cancelled')
        self.assertEqual(cancelled.after.task_id, old.task_id)
        with self.assertRaises(StateMachineError):
            self.machine.complete_failure(task_id=old.task_id)
        state = self.machine.complete_success(task_id=abort.task_id,
                                               evidence=simulated_success_evidence('abort_reset'))
        self.assertEqual((state.main_state, state.business_state), ('就绪', '未就绪'))
        self.assertEqual(state.state_definition, '待机')

    def test_ordinary_shutdown_does_not_silently_preempt(self):
        before = self.machine.start(PATH[0])
        with self.assertRaises(StateMachineError):
            self.machine.start('shutdown')
        self.assertEqual(self.machine.snapshot(), before)

    def test_recovery_requires_all_evidence_and_does_not_grant_ready(self):
        self.machine.report_exception('communication_error')
        before = self.machine.snapshot()
        for key in RECOVERY:
            incomplete = dict(RECOVERY)
            incomplete[key] = False
            with self.subTest(condition=key), self.assertRaises(StateMachineError):
                self.machine.recover_from_exception(conditions=incomplete)
            self.assertEqual(self.machine.snapshot(), before)
        state = self.machine.recover_from_exception(conditions=RECOVERY)
        self.assertEqual((state.main_state, state.business_state), ('未就绪', '未就绪'))
        with self.assertRaises(StateMachineError):
            self.machine.start('parameter_dispatch')
        execute(self.machine, 'shutdown')
        execute(self.machine, PATH[0])
        self.assertEqual(execute(self.machine, PATH[1]).business_state, '就绪')

    def test_old_result_cannot_complete_a_new_attempt_of_same_action(self):
        old = self.machine.start('shutdown')
        self.machine.report_exception('fault_lock')
        self.machine.recover_from_exception(conditions=RECOVERY)
        new = self.machine.start('shutdown')
        with self.assertRaises(StateMachineError):
            self.machine.complete_success(task_id=old.task_id,
                                           evidence=simulated_success_evidence('shutdown'))
        self.assertEqual(self.machine.snapshot(), new)

    def test_shutdown_feedback_aliases_normalize_to_excel_result(self):
        for observed in ('关机完成', '关机成功', '下电成功'):
            with self.subTest(observed=observed):
                started = self.machine.start('shutdown')
                state = self.machine.complete_success(task_id=started.task_id,
                    evidence=CompletionEvidence(True, observed, {'power_off_confirmed': True}))
                self.assertEqual(state.current_state, '关机完成')
                self.assertEqual(state.state_definition, '关机')

    def test_readiness_configuration_and_emission_can_lose_validity(self):
        for actions, event in [(PATH[:2], 'readiness_lost'),
                               (PATH[:3], 'configuration_changed'),
                               (PATH[:5], 'emission_lost')]:
            with self.subTest(event=event):
                self.setUp()
                for action in actions:
                    execute(self.machine, action)
                state = self.machine.invalidate(event)
                self.assertEqual((state.main_state, state.business_state), ('异常', '异常'))
                self.assertEqual(state.state_definition, '异常处置')

    def test_invalid_validity_event_does_not_change_state(self):
        before = self.machine.snapshot()
        with self.assertRaises(StateMachineError):
            self.machine.invalidate('emission_lost')
        self.assertEqual(self.machine.snapshot(), before)

    def test_every_excel_row_has_its_original_semantic_classification(self):
        entries = self.machine.model['actions'] + self.machine.model['exceptions']
        self.assertEqual([e['excel_row'] for e in entries], list(range(3, 14)))
        self.assertEqual([e['state_definition'] for e in entries], [
            '启动中、准备中', '业务就绪确认', '结果确认/保持有效', '结果确认/保持有效',
            '数据采集/后处理', '待机', '待机', '关机', '异常处置', '异常处置', '异常处置'])
        for entry in entries:
            for key in ('state_condition', 'flow_impact', 'business_state_description'):
                self.assertTrue(entry[key])


if __name__ == '__main__':
    unittest.main()
