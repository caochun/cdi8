import concurrent.futures
import re
import sqlite3
import tempfile
import unittest
from pathlib import Path

from gxlf_sim_system import SeedRuntime, TransitionError
from gxlf_sim_system.cli import demo
from gxlf_sim_system.machine import ACTIONS, CONFIRMATIONS, MACHINE, MODEL_ID, STATES


class SeedTest(unittest.TestCase):
    def setUp(self):
        self.runtime = SeedRuntime()
        self.addCleanup(self.runtime.close)
        self.counter = 0

    def start(self, action):
        self.counter += 1
        return self.runtime.execute({"command_id": f"task-{self.counter}", "action": action})

    def do(self, action, outcome="success", **extra):
        result = self.start(action)
        return self.runtime.simulate({"command_id": result["command_id"], "outcome": outcome, **extra})

    def ready(self):
        self.do("power_on_self_test")
        self.do("function_check")

    def emitting(self):
        self.ready()
        self.do("parameter_dispatch")
        self.do("seed_source_emit")

    def event(self, name, observation):
        self.counter += 1
        return self.runtime.report_event({"event_id": f"event-{self.counter}", "event": name,
                                          "observed_business_state": observation})

    def test_scxml_initial_state_and_task_separation(self):
        snapshot = self.runtime.snapshot()
        self.assertEqual(snapshot["model"], MODEL_ID)
        self.assertEqual(snapshot["state_id"], "unchecked")
        accepted = self.start("power_on_self_test")
        self.assertEqual(accepted["snapshot"]["state_id"], "unchecked")
        self.assertEqual(accepted["snapshot"]["inner_state_label"], "待检查")
        self.assertEqual(accepted["snapshot"]["task_state"], "executing")
        result = self.runtime.simulate({"command_id": accepted["command_id"], "outcome": "success"})
        self.assertEqual(result["snapshot"]["state_id"], "selfTestOk")

    def test_qualification_and_config_states(self):
        self.assertEqual(self.do("power_on_self_test", "failure")["snapshot"]["state_id"], "unchecked")
        self.do("power_on_self_test")
        self.assertEqual(self.do("function_check", "failure")["snapshot"]["state_id"], "checkFailed")
        self.do("function_check")
        accepted = self.start("parameter_dispatch")
        self.assertEqual(accepted["snapshot"]["state_id"], "configIdle")
        self.assertEqual(accepted["snapshot"]["configuration"]["status"], "pending")
        result = self.runtime.simulate({"command_id": accepted["command_id"], "outcome": "success"})
        self.assertEqual(result["snapshot"]["state_id"], "configOk")
        self.assertEqual(result["snapshot"]["configuration"]["status"], "valid")

    def test_emission_and_collection_do_not_create_progress_states(self):
        self.emitting()
        accepted = self.start("laser_parameter_collect")
        self.assertEqual(accepted["snapshot"]["state_id"], "collectIdle")
        failed = self.runtime.simulate({"command_id": accepted["command_id"], "outcome": "failure", "data_qualification": "invalid"})
        self.assertEqual(failed["snapshot"]["state_id"], "collectFailed")
        self.assertEqual(failed["snapshot"]["outer_state_label"], "出光")
        self.assertEqual(failed["snapshot"]["data_qualification"], "invalid")
        result = self.do("laser_parameter_collect", data_qualification="valid")
        self.assertEqual(result["snapshot"]["state_id"], "collectOk")
        self.assertEqual(result["snapshot"]["data_qualification"], "valid")

    def test_confirmations_and_fault_interrupt_task(self):
        self.emitting()
        task = self.start("laser_parameter_collect")["command_id"]
        event = self.event("fault_confirmed", "设备异常")
        self.assertEqual(event["snapshot"]["outer_state_label"], "设备异常")
        self.assertEqual(event["snapshot"]["state_id"], "faultDetected")
        self.assertEqual(self.runtime.result(task)["status"], "interrupted")
        self.event("recovery_confirmed", "未就绪")
        self.assertEqual(self.runtime.snapshot()["state_id"], "unchecked")

    def test_standby_shutdown_and_abort(self):
        self.emitting()
        self.event("standby_confirmed", "就绪")
        self.assertEqual(self.runtime.snapshot()["state_id"], "configIdle")
        self.event("shutdown_confirmed", "未就绪")
        self.assertEqual(self.runtime.snapshot()["state_id"], "unchecked")
        self.ready()
        self.event("abort_reset_confirmed", "未就绪")
        self.assertEqual(self.runtime.snapshot()["state_id"], "unchecked")

    def test_shutdown_is_a_task_without_shutdown_device_state(self):
        self.emitting()
        accepted = self.start("shutdown")
        self.assertEqual(accepted["snapshot"]["state_id"], "collectIdle")
        self.assertEqual(accepted["snapshot"]["active_action"], "shutdown")
        result = self.runtime.simulate({"command_id": accepted["command_id"], "outcome": "success"})
        self.assertEqual(result["snapshot"]["state_id"], "unchecked")
        self.assertEqual(result["snapshot"]["configuration"]["status"], "unknown")

    def test_idempotency_and_late_feedback(self):
        request = {"command_id": "same", "action": "power_on_self_test", "parameters": {"recipe": "a"}}
        self.assertEqual(self.runtime.execute(request), self.runtime.execute(request))
        result = self.runtime.simulate({"command_id": "same", "outcome": "success"})
        self.assertEqual(self.runtime.simulate({"command_id": "same", "outcome": "success"}), result)
        with self.assertRaises(TransitionError):
            self.runtime.execute({**request, "parameters": {"recipe": "b"}})

    def test_demo_and_invalid_requests(self):
        trace = demo(self.runtime)
        self.assertEqual(trace[-2]["result"]["snapshot"]["state_id"], "collectFailed")
        self.assertEqual(trace[-1]["result"]["snapshot"]["state_id"], "collectOk")
        before = self.runtime.snapshot()
        with self.assertRaises(TransitionError):
            self.start("seed_source_emit")
        self.assertEqual(self.runtime.snapshot(), before)


class PersistenceTest(unittest.TestCase):
    def test_restart_keeps_v2_state_and_active_task(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "seed.sqlite3"
            runtime = SeedRuntime(path)
            accepted = runtime.execute({"command_id": "persist", "action": "power_on_self_test"})
            runtime.close()
            runtime = SeedRuntime(path)
            self.assertEqual(runtime.snapshot()["state_id"], "unchecked")
            self.assertEqual(runtime.snapshot()["active_command_id"], "persist")
            self.assertEqual(runtime.simulate({"command_id": "persist", "outcome": "success"})["snapshot"]["state_id"], "selfTestOk")
            runtime.close()

    def test_competing_connections_and_legacy_rejection(self):
        with tempfile.TemporaryDirectory() as tmp:
            a, b = SeedRuntime(Path(tmp) / "seed.sqlite3"), SeedRuntime(Path(tmp) / "seed.sqlite3")
            try:
                def execute(pair):
                    runtime, command_id = pair
                    try:
                        return runtime.execute({"command_id": command_id, "action": "power_on_self_test"})["status"]
                    except TransitionError:
                        return "blocked"
                with concurrent.futures.ThreadPoolExecutor(2) as pool:
                    self.assertCountEqual(pool.map(execute, [(a, "a"), (b, "b")]), ["accepted", "blocked"])
            finally:
                a.close(); b.close()
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "legacy.sqlite3"
            db = sqlite3.connect(path); db.execute("CREATE TABLE commands(id TEXT)"); db.commit()
            with self.assertRaises(TransitionError): SeedRuntime(path)
            db.close()


class SCXMLConformanceTest(unittest.TestCase):
    def test_all_targets_and_initial_states_resolve(self):
        self.assertEqual(MACHINE.initial_leaf, "unchecked")
        self.assertTrue(MACHINE.leaves.issubset(STATES))
        for transition in MACHINE.transitions():
            self.assertIn(transition.target, MACHINE.nodes)
        self.assertEqual(set(CONFIRMATIONS), {"standby_confirmed", "shutdown_confirmed", "abort_reset_confirmed", "fault_confirmed", "recovery_confirmed"})
        self.assertEqual(set(ACTIONS), {"power_on_self_test", "function_check", "parameter_dispatch", "seed_source_emit", "laser_parameter_collect", "shutdown"})

    def test_mermaid_has_exactly_the_same_state_transitions_as_scxml(self):
        path = Path(__file__).resolve().parents[3] / "docs" / "diagrams" / "seed-source-state-machine-draft.mmd"
        edges = set(re.findall(r"^\s*(\w+)\s+-->\s+(\w+):\s+(.+?)\s*$", path.read_text(), re.M))
        scxml = {(t.source, t.target, t.event) for t in MACHINE.transitions()}
        self.assertEqual(edges, scxml)


if __name__ == "__main__":
    unittest.main()
