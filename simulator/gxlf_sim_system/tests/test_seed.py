import concurrent.futures
import json
from pathlib import Path
import re
import sqlite3
import tempfile
import unittest

from gxlf_sim_system import SeedRuntime, TransitionError
from gxlf_sim_system.cli import demo
from gxlf_sim_system.machine import ACTIONS, CONFIRMATIONS, STATES


class SeedTest(unittest.TestCase):
    def setUp(self):
        self.runtime = SeedRuntime()
        self.addCleanup(self.runtime.close)
        self.counter = 0

    def start(self, action):
        self.counter += 1
        command_id = f"task-{self.counter}"
        return self.runtime.execute({"command_id": command_id, "action": action})

    def do(self, action, outcome="success"):
        result = self.start(action)
        return self.runtime.simulate({"command_id": result["command_id"], "outcome": outcome})

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

    def test_initial_and_self_test_success_remain_not_ready(self):
        self.assertEqual(self.runtime.snapshot()["state_id"], "Idle")
        accepted = self.start("power_on_self_test")
        self.assertEqual(accepted["snapshot"]["current_state"], "自检中")
        self.assertIsNone(accepted["key_state"])
        self.runtime.simulate({"command_id": accepted["command_id"], "outcome": "success"})
        self.assertEqual(self.runtime.snapshot()["business_state"], "未就绪")
        self.assertEqual(self.runtime.snapshot()["state_id"], "TestOK")

    def test_self_test_and_function_check_failures_can_retry(self):
        self.assertEqual(self.do("power_on_self_test", "failure")["snapshot"]["state_id"], "TestFailed")
        self.do("power_on_self_test")
        self.assertEqual(self.do("function_check", "failure")["snapshot"]["state_id"], "CheckFailed")
        self.assertEqual(self.runtime.snapshot()["business_state"], "未就绪")
        self.assertEqual(self.do("function_check")["snapshot"]["state_id"], "ConfigIdle")

    def test_function_check_requires_business_ready_evidence(self):
        self.do("power_on_self_test")
        command = self.start("function_check")["command_id"]
        with self.assertRaises(TransitionError):
            self.runtime.complete({"command_id": command, "outcome": "success"})
        self.assertEqual(self.runtime.snapshot()["state_id"], "Checking")
        self.runtime.complete({"command_id": command, "outcome": "success", "observed_business_state": "就绪"})
        self.assertEqual(self.runtime.snapshot()["business_state"], "就绪")

    def test_parameter_failure_is_not_device_fault_and_reconfiguration_is_allowed(self):
        self.ready()
        result = self.do("parameter_dispatch", "failure")
        self.assertEqual(result["snapshot"]["business_state"], "就绪")
        self.assertEqual(result["snapshot"]["state_id"], "ConfigFailed")
        with self.assertRaises(TransitionError):
            self.start("seed_source_emit")
        self.do("parameter_dispatch")
        self.assertEqual(self.do("parameter_dispatch")["snapshot"]["state_id"], "ConfigOK")

    def test_emission_failure_needs_confirmation_still_ready_and_can_retry(self):
        self.ready()
        self.do("parameter_dispatch")
        command = self.start("seed_source_emit")["command_id"]
        for state in (None, "出光", "异常"):
            with self.assertRaises(TransitionError):
                self.runtime.complete({"command_id": command, "outcome": "failure", "observed_business_state": state})
        self.runtime.complete({"command_id": command, "outcome": "failure", "observed_business_state": "就绪"})
        self.assertEqual(self.runtime.snapshot()["state_id"], "StartFailed")
        # This edge is absent in the approved diagram, even though it might seem convenient.
        with self.assertRaises(TransitionError):
            self.start("parameter_dispatch")
        self.assertEqual(self.do("seed_source_emit")["snapshot"]["state_id"], "CollectIdle")

    def test_emission_success_requires_stable_emission_confirmation(self):
        self.ready()
        self.do("parameter_dispatch")
        command = self.start("seed_source_emit")["command_id"]
        with self.assertRaises(TransitionError):
            self.runtime.complete({"command_id": command, "outcome": "success", "observed_business_state": "就绪"})
        self.assertEqual(self.runtime.snapshot()["state_id"], "Starting")
        self.runtime.simulate({"command_id": command, "outcome": "success"})
        self.assertEqual(self.runtime.snapshot()["business_state"], "出光")

    def test_collection_success_failure_and_retries_all_preserve_emission(self):
        self.emitting()
        for outcome, leaf, key in [("failure", "CollectFailed", "采集失败"), ("success", "CollectOK", "采集正常"), ("success", "CollectOK", "采集正常")]:
            accepted = self.start("laser_parameter_collect")
            self.assertEqual(accepted["snapshot"]["business_state"], "出光")
            self.assertEqual(accepted["snapshot"]["state_id"], "Collecting")
            result = self.runtime.simulate({"command_id": accepted["command_id"], "outcome": outcome})
            self.assertEqual(result["snapshot"]["business_state"], "出光")
            self.assertEqual(result["snapshot"]["state_id"], leaf)
            self.assertEqual(result["key_state"], key)

    def test_confirmed_standby_enters_ready_initial_substate(self):
        self.emitting()
        self.event("standby_confirmed", "就绪")
        self.assertEqual(self.runtime.snapshot()["state_id"], "ConfigIdle")
        with self.assertRaises(TransitionError):
            self.start("seed_source_emit")
        self.do("parameter_dispatch")

    def test_shutdown_and_abort_confirmations_from_ready_and_emitting(self):
        for event in ("shutdown_confirmed", "abort_reset_confirmed"):
            for emitting in (False, True):
                self.ready()
                if emitting:
                    self.do("parameter_dispatch")
                    self.do("seed_source_emit")
                self.event(event, "未就绪")
                self.assertEqual(self.runtime.snapshot()["state_id"], "Idle")

    def test_fault_is_separate_and_late_reply_cannot_revive_interrupted_task(self):
        self.emitting()
        command = self.start("laser_parameter_collect")["command_id"]
        self.event("fault_confirmed", "异常")
        self.assertEqual(self.runtime.snapshot()["state_id"], "Fault")
        self.assertEqual(self.runtime.result(command)["status"], "interrupted")
        with self.assertRaises(TransitionError):
            self.start("laser_parameter_collect")
        self.event("recovery_confirmed", "未就绪")
        new = self.start("power_on_self_test")["command_id"]
        self.assertEqual(self.runtime.simulate({"command_id": command, "outcome": "success"})["status"], "interrupted")
        self.assertEqual(self.runtime.snapshot()["active_command_id"], new)
        self.assertEqual(self.runtime.snapshot()["state_id"], "Testing")

    def test_outer_transition_interrupts_nested_task_atomically(self):
        self.emitting()
        command = self.start("laser_parameter_collect")["command_id"]
        result = self.event("shutdown_confirmed", "未就绪")
        self.assertEqual(result["interrupted_command_id"], command)
        self.assertEqual(self.runtime.result(command)["snapshot"]["state_id"], "Idle")
        self.assertIsNone(self.runtime.snapshot()["active_action"])

    def test_illegal_requests_do_not_change_state_or_history(self):
        for action in ("function_check", "parameter_dispatch", "seed_source_emit", "laser_parameter_collect", "shutdown", "abort_reset", "standby_reset", "unknown"):
            before = self.runtime.snapshot()
            with self.assertRaises(TransitionError):
                self.start(action)
            self.assertEqual(self.runtime.snapshot(), before)
            self.assertEqual(self.runtime.history(), [])

    def test_confirmations_require_correct_evidence_and_source_state(self):
        with self.assertRaises(TransitionError):
            self.event("shutdown_confirmed", "未就绪")
        with self.assertRaises(TransitionError):
            self.event("recovery_confirmed", "未就绪")
        self.ready()
        with self.assertRaises(TransitionError):
            self.event("shutdown_confirmed", "出光")
        self.assertEqual(self.runtime.snapshot()["business_state"], "就绪")

    def test_idempotent_commands_and_feedback_do_not_repeat_transitions(self):
        request = {"command_id": "same", "action": "power_on_self_test", "parameters": {"recipe": "a"}}
        self.assertEqual(self.runtime.execute(request), self.runtime.execute(request))
        feedback = {"command_id": "same", "outcome": "success"}
        result = self.runtime.complete(feedback)
        self.assertEqual(self.runtime.complete(feedback), result)
        self.assertEqual(self.runtime.execute(request), result)
        self.assertEqual(len(self.runtime.history()), 2)
        for conflict in ({**request, "action": "function_check"}, {**request, "parameters": {"recipe": "b"}}):
            with self.assertRaises(TransitionError):
                self.runtime.execute(conflict)
        with self.assertRaises(TransitionError):
            self.runtime.complete({"command_id": "same", "outcome": "failure"})

    def test_old_success_result_does_not_complete_new_task(self):
        old = self.do("power_on_self_test")
        new = self.start("function_check")
        self.assertEqual(self.runtime.simulate({"command_id": old["command_id"], "outcome": "success"}), old)
        self.assertEqual(self.runtime.snapshot()["active_command_id"], new["command_id"])
        with self.assertRaises(TransitionError):
            self.runtime.simulate({"command_id": "unknown", "outcome": "success"})

    def test_duplicate_confirmations_do_not_affect_later_tasks(self):
        self.ready()
        payload = {"event_id": "shutdown", "event": "shutdown_confirmed", "observed_business_state": "未就绪"}
        result = self.runtime.report_event(payload)
        self.start("power_on_self_test")
        self.assertEqual(self.runtime.report_event(payload), result)
        self.assertEqual(self.runtime.snapshot()["state_id"], "Testing")
        with self.assertRaises(TransitionError):
            self.runtime.report_event({**payload, "event": "abort_reset_confirmed"})

    def test_unsupported_timeout_and_cancel_are_not_invented(self):
        command = self.start("power_on_self_test")["command_id"]
        with self.assertRaises(TransitionError):
            self.runtime.simulate({"command_id": command, "outcome": "timeout"})
        self.assertEqual(self.runtime.snapshot()["state_id"], "Testing")

    def test_demo_exposes_failed_collection_without_stopping_emission(self):
        trace = demo(self.runtime)
        self.assertEqual(trace[-2]["result"]["snapshot"]["state_id"], "CollectFailed")
        self.assertEqual(trace[-2]["result"]["snapshot"]["business_state"], "出光")
        self.assertEqual(trace[-1]["result"]["snapshot"]["state_id"], "CollectOK")


class PersistenceTest(unittest.TestCase):
    def test_restart_keeps_active_task_results_and_event_history(self):
        with tempfile.TemporaryDirectory() as tmp:
            db = Path(tmp) / "seed.sqlite3"
            runtime = SeedRuntime(db)
            accepted = runtime.execute({"command_id": "persist", "action": "power_on_self_test"})
            runtime.close()
            runtime = SeedRuntime(db)
            self.assertEqual(runtime.snapshot()["state_id"], "Testing")
            self.assertEqual(runtime.execute({"command_id": "persist", "action": "power_on_self_test"}), accepted)
            result = runtime.simulate({"command_id": "persist", "outcome": "success"})
            runtime.close()
            runtime = SeedRuntime(db)
            try:
                self.assertEqual(runtime.result("persist"), result)
                self.assertEqual(runtime.snapshot()["state_id"], "TestOK")
                self.assertEqual(len(runtime.history()), 2)
            finally:
                runtime.close()

    def test_competing_connections_cannot_start_two_tasks(self):
        with tempfile.TemporaryDirectory() as tmp:
            a = SeedRuntime(Path(tmp) / "seed.sqlite3")
            b = SeedRuntime(Path(tmp) / "seed.sqlite3")
            def execute(pair):
                runtime, id = pair
                try:
                    return runtime.execute({"command_id": id, "action": "power_on_self_test"})["status"]
                except TransitionError:
                    return "blocked"
            try:
                with concurrent.futures.ThreadPoolExecutor(2) as executor:
                    results = list(executor.map(execute, [(a, "a"), (b, "b")]))
                self.assertCountEqual(results, ["accepted", "blocked"])
                self.assertEqual(len(a.history()), 1)
            finally:
                a.close()
                b.close()

    def test_legacy_database_is_not_silently_reinterpreted(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "legacy.sqlite3"
            db = sqlite3.connect(path)
            db.execute("CREATE TABLE commands(id TEXT)")
            db.execute("INSERT INTO commands VALUES('old-command')")
            db.commit()
            with self.assertRaises(TransitionError):
                SeedRuntime(path)
            self.assertEqual(db.execute("SELECT id FROM commands").fetchone()[0], "old-command")
            self.assertEqual(db.execute("SELECT count(*) FROM sqlite_master WHERE name='seed_state'").fetchone()[0], 0)
            db.close()


class DiagramConformanceTest(unittest.TestCase):
    def test_all_declared_transitions_match_the_reviewed_mermaid(self):
        path = Path(__file__).resolve().parents[3] / "docs/diagrams/seed-source-state-machine-draft.mmd"
        if not path.exists():
            self.skipTest("Mermaid source is available in the repository checkout")
        edges = set(re.findall(r"^\s*(\w+) --> (\w+): (.+)$", path.read_text(), re.M))
        entries = {"NotReady": "Idle", "Ready": "ConfigIdle", "Emitting": "CollectIdle"}
        # Targets on composite boundaries enter their explicitly drawn initial substate.
        expected = {(a, entries.get(b, b), event) for a, b, event in edges}
        actual = set()
        for spec in ACTIONS.values():
            actual.update((source, spec.running, event) for source, event in spec.requests.items())
            actual.add((spec.running, spec.success, spec.success_event))
            actual.add((spec.running, spec.failure, spec.failure_event))
        parent_ids = {"未就绪": "NotReady", "就绪": "Ready", "出光": "Emitting", "异常": "Fault"}
        for spec in CONFIRMATIONS.values():
            actual.update((parent_ids[source], spec.target, spec.label) for source in spec.sources)
        self.assertEqual(expected, actual)
        labels = dict((id, label) for label, id in re.findall(r'state "([^"]+)" as (\w+)', path.read_text()))
        self.assertEqual(set(labels) - set(entries), set(STATES))
        for id, (_, label) in STATES.items():
            self.assertEqual(label, labels[id])


if __name__ == "__main__":
    unittest.main()
