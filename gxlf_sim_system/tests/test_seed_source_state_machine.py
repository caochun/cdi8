from __future__ import annotations

import unittest

from gxlf_sim_system.subsystem_fsm import StateMachineError, load_seed_source_state_machine


class SeedSourceStateMachineTest(unittest.TestCase):
    def test_excel_golden_path(self) -> None:
        machine = load_seed_source_state_machine()

        machine.start("power_on_self_test")
        self.assertEqual(machine.complete_success().current_state, "自检完成")

        machine.start("function_check")
        self.assertEqual(machine.complete_success().business_state, "就绪")

        machine.start("parameter_dispatch")
        self.assertEqual(machine.complete_success().key_state, "参数配置正常")

        machine.start("seed_source_emit")
        self.assertEqual(machine.complete_success().business_state, "出光")

        machine.start("laser_parameter_collect")
        self.assertEqual(machine.complete_success().current_state, "采集完成")

        machine.start("standby_reset")
        snapshot = machine.complete_success()
        self.assertEqual(snapshot.main_state, "就绪")
        self.assertEqual(snapshot.business_state, "就绪")
        self.assertEqual(snapshot.key_state, "复位/待机完成")

    def test_preconditions_are_enforced(self) -> None:
        machine = load_seed_source_state_machine()
        with self.assertRaises(StateMachineError):
            machine.start("seed_source_emit")

        machine.start("power_on_self_test")
        machine.complete_success()
        with self.assertRaises(StateMachineError):
            machine.start("parameter_dispatch")

    def test_action_failure_preserves_excel_business_projection(self) -> None:
        machine = load_seed_source_state_machine()
        machine.start("power_on_self_test")
        snapshot = machine.complete_failure()
        self.assertEqual(snapshot.main_state, "异常")
        self.assertEqual(snapshot.current_state, "自检异常")
        self.assertEqual(snapshot.business_state, "未就绪")
        self.assertEqual(snapshot.task_state, "failed")

    def test_generic_exception_and_explicit_recovery(self) -> None:
        machine = load_seed_source_state_machine()
        snapshot = machine.report_exception("communication_error")
        self.assertEqual(snapshot.main_state, "异常")
        self.assertEqual(snapshot.current_state, "通信异常")
        self.assertEqual(snapshot.business_state, "异常")

        snapshot = machine.recover_from_exception()
        self.assertEqual(snapshot.main_state, "就绪")
        self.assertEqual(snapshot.current_state, "复位/待机完成")
        self.assertEqual(snapshot.task_state, "idle")

    def test_abort_and_shutdown_are_unconditional_actions(self) -> None:
        machine = load_seed_source_state_machine()
        machine.start("abort_reset")
        snapshot = machine.complete_success()
        self.assertEqual(snapshot.business_state, "未就绪")

        machine.start("shutdown")
        snapshot = machine.complete_success()
        self.assertEqual(snapshot.main_state, "未就绪")
        self.assertEqual(snapshot.current_state, "关机完成")


if __name__ == "__main__":
    unittest.main()
