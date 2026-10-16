"""Executable contracts for the canonical single-level seed-source model."""
import itertools
import unittest

from gxlf_component_simulator import ComponentRuntime, SEED_MODEL, TransitionError


class SeedStateContractTest(unittest.TestCase):
    def runtime(self):
        runtime = ComponentRuntime(SEED_MODEL)
        self.addCleanup(runtime.close)
        return runtime

    def perform(self, runtime, action, outcome="success", **feedback):
        command_id = f"task-{len(runtime.history())}"
        runtime.execute({"command_id": command_id, "action": action})
        return runtime.simulate({"command_id": command_id, "outcome": outcome, **feedback})["snapshot"]

    def confirm(self, runtime, event):
        spec = SEED_MODEL.confirmations[event]
        return runtime.report_event({
            "event_id": f"event-{len(runtime.history())}",
            "event": event,
            "observed_outer_state": spec.observation,
        })["snapshot"]

    def prepare(self, runtime, emit=False):
        for action in ("power_on_check", "function_check", "parameter_dispatch"):
            self.perform(runtime, action)
        if emit:
            self.perform(runtime, "seed_source_emit")

    def test_initial_power_and_function_qualification(self):
        runtime = self.runtime()
        self.assertEqual(runtime.snapshot()["state_id"], "statusUnknown")
        with self.assertRaises(TransitionError):
            runtime.execute({"command_id": "too-early", "action": "function_check"})
        self.assertEqual(self.perform(runtime, "power_on_check")["state_id"], "powerOnChecked")
        self.assertEqual(self.perform(runtime, "function_check", "failure")["state_id"], "qualificationInvalid")
        self.assertEqual(self.perform(runtime, "power_on_check")["state_id"], "powerOnChecked")
        self.assertEqual(self.perform(runtime, "function_check")["state_id"], "qualified")

    def test_full_emission_collection_and_shutdown_flow(self):
        runtime = self.runtime()
        self.prepare(runtime)
        self.assertEqual(self.perform(runtime, "seed_source_emit")["state_id"], "stableEmission")
        self.assertEqual(self.perform(runtime, "laser_parameter_collect")["state_id"], "collectionOk")
        self.assertEqual(self.perform(runtime, "standby_reset")["state_id"], "standbyReady")
        self.assertEqual(self.perform(runtime, "shutdown")["state_id"], "shutdownNormal")
        self.assertEqual(runtime.snapshot()["context"]["power_status"], "off")

    def test_configuration_and_emission_confirmations(self):
        runtime = self.runtime()
        self.prepare(runtime)
        self.assertEqual(self.confirm(runtime, "configuration_invalidated")["state_id"], "qualified")
        self.perform(runtime, "parameter_dispatch")
        self.perform(runtime, "seed_source_emit")
        self.assertEqual(self.confirm(runtime, "emission_lost_confirmed")["state_id"], "configured")
        self.assertEqual(self.confirm(runtime, "qualification_invalidated")["state_id"], "qualificationInvalid")

    def test_fault_priority_interrupts_tasks_and_recovers(self):
        for events in itertools.permutations(("fault_confirmed", "communication_fault_confirmed", "timeout_confirmed")):
            with self.subTest(events=events):
                runtime = self.runtime()
                for event in events:
                    self.confirm(runtime, event)
                snapshot = runtime.snapshot()
                self.assertEqual(snapshot["state_id"], "faultLocked")
                self.assertEqual(set(snapshot["faults"]), {"locked", "communication", "timeout"})
                recovered = self.confirm(runtime, "recovery_confirmed")
                self.assertEqual(recovered["state_id"], "statusUnknown")
                self.assertEqual(recovered["faults"], [])

    def test_late_completion_is_interrupted_by_fault(self):
        runtime = self.runtime()
        self.prepare(runtime, emit=True)
        runtime.execute({"command_id": "collect", "action": "laser_parameter_collect"})
        self.confirm(runtime, "fault_confirmed")
        result = runtime.simulate({"command_id": "collect", "outcome": "success"})
        self.assertEqual(result["status"], "interrupted")
        self.assertEqual(result["snapshot"]["state_id"], "faultLocked")


if __name__ == "__main__":
    unittest.main()
