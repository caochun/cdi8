import concurrent.futures
import tempfile
import unittest
from pathlib import Path

from gxlf_sim_system import ComponentRuntime, PREAMP_MODEL, SEED_MODEL, SHG_MODEL, TransitionError


class ComponentRuntimeTest(unittest.TestCase):
    def drive(self, runtime, actions, prefix="task"):
        for index, (action, outcome) in enumerate(actions):
            command_id = f"{prefix}-{index}"
            runtime.execute({"command_id": command_id, "action": action})
            runtime.simulate({"command_id": command_id, "outcome": outcome})

    def test_seed_model_is_scxml_driven(self):
        runtime = ComponentRuntime(SEED_MODEL)
        self.addCleanup(runtime.close)
        self.assertEqual(runtime.snapshot()["state_id"], "unchecked")
        accepted = runtime.execute({"command_id": "selftest", "action": "power_on_self_test"})
        self.assertEqual(accepted["snapshot"]["state_id"], "unchecked")
        self.assertEqual(runtime.simulate({"command_id": "selftest", "outcome": "success"})["snapshot"]["state_id"], "selfTestOk")
        self.drive(runtime, [("function_check", "success"), ("parameter_dispatch", "success"), ("seed_source_emit", "success")], "seed")
        self.assertEqual(runtime.snapshot()["outer_state_label"], "出光")
        self.drive(runtime, [("laser_parameter_collect", "failure"), ("laser_parameter_collect", "success")], "collect")
        self.assertEqual(runtime.snapshot()["state_id"], "collectOk")

    def test_preamp_model_and_fault_recovery(self):
        runtime = ComponentRuntime(PREAMP_MODEL)
        self.addCleanup(runtime.close)
        self.drive(runtime, [("power_on_self_test", "success"), ("parameter_dispatch", "success"), ("preamp_wakeup", "success"), ("coarse_loop", "success"), ("fine_loop", "success"), ("energy_meter_clear", "success")], "preamp")
        self.assertEqual(runtime.snapshot()["state_id"], "energyMeterCleared")
        event = runtime.report_event({"event_id": "fault-1", "event": "fault_confirmed", "observed_outer_state": "设备异常"})
        self.assertEqual(event["snapshot"]["outer_state"], "fault")
        self.assertEqual(runtime.report_event({"event_id": "recover-1", "event": "recovery_confirmed", "observed_outer_state": "未就绪"})["snapshot"]["state_id"], "baseNotReady")

    def test_shg_model_uses_the_same_runtime(self):
        runtime = ComponentRuntime(SHG_MODEL)
        self.addCleanup(runtime.close)
        self.drive(runtime, [
            ("power_on_self_test", "success"),
            ("function_check", "success"),
            ("parameter_dispatch", "success"),
            ("frequency_doubled_emit", "success"),
            ("laser_parameter_collect", "success"),
            ("standby_reset", "success"),
            ("shutdown", "success"),
        ], "shg")
        self.assertEqual(runtime.snapshot()["state_id"], "shutdownNormal")

    def test_task_idempotency_and_persistence(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "component.sqlite3"
            first = ComponentRuntime(SEED_MODEL, path)
            request = {"command_id": "same", "action": "power_on_self_test", "parameters": {"recipe": "a"}}
            self.assertEqual(first.execute(request), first.execute(request))
            first.close()
            second = ComponentRuntime(SEED_MODEL, path)
            self.assertEqual(second.snapshot()["active_command_id"], "same")
            with self.assertRaises(TransitionError):
                second.execute({"command_id": "same", "action": "power_on_self_test", "parameters": {"recipe": "b"}})
            second.close()

    def test_database_isolation_and_model_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "component.sqlite3"
            seed = ComponentRuntime(SEED_MODEL, path)
            seed.close()
            with self.assertRaises(TransitionError):
                ComponentRuntime(PREAMP_MODEL, path)

    def test_concurrent_requests_share_one_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "component.sqlite3"
            a, b = ComponentRuntime(SEED_MODEL, path), ComponentRuntime(SEED_MODEL, path)
            try:
                def submit(pair):
                    runtime, command_id = pair
                    try:
                        return runtime.execute({"command_id": command_id, "action": "power_on_self_test"})["status"]
                    except TransitionError:
                        return "blocked"
                with concurrent.futures.ThreadPoolExecutor(2) as pool:
                    self.assertCountEqual(pool.map(submit, [(a, "a"), (b, "b")]), ["accepted", "blocked"])
            finally:
                a.close(); b.close()


if __name__ == "__main__":
    unittest.main()
