from __future__ import annotations

from copy import deepcopy
from dataclasses import replace
import unittest

from gxlf_sim_system.experiment import (
    ExperimentError, SequentialExperiment, load_seed_source_experiment,
)
from gxlf_sim_system.subsystem_fsm import load_seed_source_state_machine


class ExperimentTest(unittest.TestCase):
    def setUp(self):
        self.machine = load_seed_source_state_machine()
        self.flow = load_seed_source_experiment(self.machine)

    def run_success(self, flow):
        for _ in flow.nodes:
            result = flow.complete(flow.dispatch_next(), success=True)
            self.assertEqual(result.status, "succeeded")

    def test_full_experiment_and_next_run_use_same_subsystem(self):
        self.assertEqual(self.flow.snapshot().status, "idle")
        self.run_success(self.flow)
        snapshot = self.flow.snapshot()
        self.assertEqual(snapshot.status, "succeeded")
        self.assertEqual([r.subsystem.current_state for r in snapshot.results], [
            "自检完成", "功能检查完成", "参数下发完成", "出光完成",
            "采集完成", "复位/待机完成", "关机完成",
        ])
        self.assertEqual(snapshot.results[4].subsystem.business_state, "出光")
        self.assertEqual(self.machine.snapshot().main_state, "未就绪")
        self.assertEqual(len(self.machine.history), 14)
        next_flow = load_seed_source_experiment(self.machine)
        self.assertNotEqual(next_flow.snapshot().run_id, snapshot.run_id)
        self.run_success(next_flow)
        self.assertEqual(len(self.machine.history), 28)

    def test_dispatch_alone_never_advances_or_reports_success(self):
        dispatch = self.flow.dispatch_next()
        self.assertEqual(self.machine.snapshot().task_state, "executing")
        self.assertEqual(dict(self.flow.snapshot().node_states)["check"], "waiting")
        with self.assertRaises(ExperimentError):
            self.flow.dispatch_next()
        self.assertEqual(self.flow.snapshot().results, ())
        self.flow.complete(dispatch, success=True)
        self.assertEqual(self.flow.dispatch_next().node_id, "check")

    def test_failure_at_each_node_blocks_downstream(self):
        for failure_index in range(7):
            with self.subTest(node_index=failure_index):
                machine = load_seed_source_state_machine()
                flow = load_seed_source_experiment(machine)
                for _ in range(failure_index):
                    flow.complete(flow.dispatch_next(), success=True)
                result = flow.complete(flow.dispatch_next(), success=False)
                self.assertEqual(result.status, "failed")
                self.assertEqual(result.subsystem.main_state, "异常")
                self.assertEqual(flow.snapshot().status, "failed")
                self.assertEqual(len(flow.snapshot().results), failure_index + 1)
                downstream = flow.snapshot().node_states[failure_index + 1:]
                self.assertTrue(all(status == "waiting" for _, status in downstream))
                with self.assertRaises(ExperimentError):
                    flow.dispatch_next()

    def test_precondition_rejection_leaves_subsystem_unchanged(self):
        self.machine.report_exception("communication_error")
        before = self.machine.snapshot()
        with self.assertRaises(ExperimentError):
            self.flow.dispatch_next()
        self.assertEqual(self.flow.snapshot().status, "failed")
        self.assertEqual(self.machine.snapshot(), before)
        self.assertIn("requires", self.flow.snapshot().results[0].reason)

    def test_stale_duplicate_and_foreign_results_cannot_advance(self):
        first = self.flow.dispatch_next()
        for wrong in (replace(first, task_id="other"), replace(first, run_id="other")):
            with self.assertRaises(ExperimentError):
                self.flow.complete(wrong, success=True)
        self.flow.complete(first, success=True)
        second = self.flow.dispatch_next()
        before = self.machine.snapshot()
        with self.assertRaises(ExperimentError):
            self.flow.complete(first, success=True)
        self.assertEqual(self.machine.snapshot(), before)
        self.assertEqual(self.flow.snapshot().active, second)

    def test_terminal_run_is_single_use(self):
        last = None
        for _ in self.flow.nodes:
            last = self.flow.dispatch_next()
            self.flow.complete(last, success=True)
        with self.assertRaises(ExperimentError):
            self.flow.dispatch_next()
        with self.assertRaises(ExperimentError):
            self.flow.complete(last, success=True)

    def test_success_requires_expected_subsystem_state(self):
        dispatch = self.flow.dispatch_next()
        original = self.machine.complete_success

        def wrong_feedback(action):
            return replace(original(action), current_state="错误反馈")

        self.machine.complete_success = wrong_feedback
        result = self.flow.complete(dispatch, success=True)
        self.assertEqual(result.status, "failed")
        self.assertEqual(self.flow.snapshot().status, "failed")

    def test_externally_interrupted_task_does_not_advance(self):
        dispatch = self.flow.dispatch_next()
        self.machine.complete_failure()
        result = self.flow.complete(dispatch, success=True)
        self.assertEqual(result.status, "failed")
        self.assertEqual(result.subsystem.main_state, "异常")

    def test_invalid_result_type_is_rejected_without_state_change(self):
        dispatch = self.flow.dispatch_next()
        before = self.machine.snapshot()
        with self.assertRaises(ExperimentError):
            self.flow.complete(dispatch, success="false")
        self.assertEqual(self.machine.snapshot(), before)
        self.assertEqual(self.flow.snapshot().active, dispatch)

    def test_invalid_model_rejected_before_any_command(self):
        good = {
            "kind": "SequentialExperiment", "system_id": "laser_seed_source",
            "nodes": [{"id": "one", "action": "power_on_self_test", "phase": "准备"}],
        }
        bad_models = [
            {**good, "kind": "Unknown"}, {**good, "system_id": "other"},
            {**good, "nodes": []}, {**good, "nodes": good["nodes"] * 2},
            {**good, "nodes": [{"id": "x", "action": "missing", "phase": "准备"}]},
            {**good, "nodes": [{"id": "x"}]},
        ]
        for model in bad_models:
            with self.subTest(model=model), self.assertRaises(ExperimentError):
                SequentialExperiment(deepcopy(model), self.machine)
        self.assertEqual(self.machine.history, ())


if __name__ == "__main__":
    unittest.main()
