import concurrent.futures
import tempfile
import unittest
from pathlib import Path

from gxlf_component_simulator import (ComponentRuntime, MULTIPASS_MODEL, PREAMP_MODEL,
                                       REFERENCE_MODEL, SEED_MODEL, SHG_MODEL, TransitionError)


class ComponentRuntimeTest(unittest.TestCase):
    def test_state_revision_survives_restart_and_duplicate_feedback(self):
        with tempfile.TemporaryDirectory() as folder:
            database = Path(folder) / "revision.sqlite3"
            runtime = ComponentRuntime(SEED_MODEL, database)
            self.assertEqual(runtime.snapshot()["state_revision"], 0)
            request = {"command_id": "power", "action": "power_on_check"}
            accepted = runtime.execute(request)
            completed = runtime.simulate({"command_id": "power", "outcome": "success"})
            revision = completed["snapshot"]["state_revision"]
            self.assertGreater(revision, accepted["snapshot"]["state_revision"])
            runtime.close()
            restored = ComponentRuntime(SEED_MODEL, database)
            try:
                self.assertEqual(restored.snapshot()["state_revision"], revision)
                restored.execute(request)
                restored.simulate({"command_id": "power", "outcome": "success"})
                self.assertEqual(restored.snapshot()["state_revision"], revision)
            finally:
                restored.close()

    def drive(self, runtime, actions, prefix="task"):
        for index, (action, outcome) in enumerate(actions):
            command_id = f"{prefix}-{index}"
            runtime.execute({"command_id": command_id, "action": action})
            runtime.simulate({"command_id": command_id, "outcome": outcome})

    def test_seed_model_is_scxml_driven(self):
        runtime = ComponentRuntime(SEED_MODEL)
        self.addCleanup(runtime.close)
        self.assertEqual(runtime.snapshot()["state_id"], "statusUnknown")
        accepted = runtime.execute({"command_id": "power-check", "action": "power_on_check"})
        self.assertEqual(accepted["snapshot"]["state_id"], "statusUnknown")
        self.assertEqual(runtime.simulate({"command_id": "power-check", "outcome": "success"})["snapshot"]["state_id"], "powerOnChecked")
        self.drive(runtime, [("function_check", "success"), ("parameter_dispatch", "success"), ("seed_source_emit", "success")], "seed")
        self.assertEqual(runtime.snapshot()["outer_state_label"], "出光")
        self.drive(runtime, [("laser_parameter_collect", "failure"), ("laser_parameter_collect", "success")], "collect")
        self.assertEqual(runtime.snapshot()["state_id"], "collectionOk")

    def test_reference_model_is_executable_and_keeps_known_not_ready_states(self):
        runtime = ComponentRuntime(REFERENCE_MODEL)
        self.addCleanup(runtime.close)
        self.assertEqual(runtime.snapshot()["state_id"], "statusUnknown")
        self.assertEqual(set(REFERENCE_MODEL.actions), {
            "power_on_check", "function_check", "parameter_dispatch", "seed_source_emit",
            "laser_parameter_collect", "standby_reset", "abort_reset", "shutdown",
        })
        with self.assertRaises(TransitionError):
            runtime.execute({"command_id": "function-too-early", "action": "function_check"})
        self.drive(runtime, [("power_on_check", "success")], "reference-power")
        self.assertEqual(runtime.snapshot()["state_id"], "powerOnChecked")
        self.drive(runtime, [("function_check", "failure")], "reference-function-fail")
        self.assertEqual(runtime.snapshot()["state_id"], "qualificationInvalid")
        self.drive(runtime, [("power_on_check", "success"), ("function_check", "success"),
                             ("parameter_dispatch", "success"), ("seed_source_emit", "success")],
                   "reference-retry")
        self.assertEqual(runtime.snapshot()["state_id"], "stableEmission")
        self.drive(runtime, [("standby_reset", "success"), ("shutdown", "success")], "reference-stop")
        self.assertEqual(runtime.snapshot()["state_id"], "shutdownNormal")

        faulted = ComponentRuntime(REFERENCE_MODEL)
        self.addCleanup(faulted.close)
        faulted.report_event({"event_id": "reference-fault", "event": "fault_confirmed",
                              "observed_outer_state": "异常"})
        self.assertEqual(faulted.snapshot()["state_id"], "faultLocked")
        self.drive(faulted, [("abort_reset", "success")], "reference-recovery")
        self.assertEqual(faulted.snapshot()["state_id"], "statusUnknown")
        self.assertEqual(faulted.snapshot()["faults"], [])

    def test_preamp_model_and_fault_recovery(self):
        # The single-shot recipe is owned by the synchronization subsystem;
        # this component only exposes its energy-control states.
        self.assertNotIn("single_shot_recipe", PREAMP_MODEL.actions)
        runtime = ComponentRuntime(PREAMP_MODEL)
        self.addCleanup(runtime.close)
        self.drive(runtime, [("power_on_check", "success"), ("function_check", "success"), ("parameter_dispatch", "success"), ("preamp_wakeup", "success"), ("coarse_loop", "success"), ("fine_loop", "success"), ("energy_meter_clear", "success")], "preamp")
        self.assertEqual(runtime.snapshot()["state_id"], "energyMeterCleared")
        event = runtime.report_event({"event_id": "fault-1", "event": "fault_confirmed", "observed_outer_state": "异常"})
        self.assertEqual(event["snapshot"]["outer_state"], "fault")
        self.assertEqual(runtime.report_event({"event_id": "recover-1", "event": "recovery_confirmed", "observed_outer_state": "未就绪"})["snapshot"]["state_id"], "unchecked")

        lifecycle = ComponentRuntime(PREAMP_MODEL)
        self.addCleanup(lifecycle.close)
        self.drive(lifecycle, [
            ("power_on_check", "success"), ("function_check", "success"),
            ("parameter_dispatch", "success"), ("preamp_wakeup", "success"),
            ("coarse_loop", "success"), ("fine_loop", "success"),
            ("energy_meter_clear", "success"), ("energy_collect", "success"),
            ("standby_reset", "success"), ("shutdown", "success"),
        ], "preamp-lifecycle")
        self.assertEqual(lifecycle.snapshot()["state_id"], "shutdownNormal")

        # A reset returns to the ready/standby leaf, where a fresh parameter
        # dispatch must still be completable.  This guards the SCXML path that
        # previously caused Tango to wrap the missing transition as DevFailed.
        reconfigured = ComponentRuntime(PREAMP_MODEL)
        self.addCleanup(reconfigured.close)
        self.drive(reconfigured, [
            ("power_on_check", "success"), ("function_check", "success"),
            ("parameter_dispatch", "success"), ("preamp_wakeup", "success"),
            ("standby_reset", "success"),
        ], "preamp-reconfigure")
        self.assertEqual(reconfigured.snapshot()["state_id"], "standbyReady")
        self.drive(reconfigured, [("parameter_dispatch", "success")], "preamp-reconfigure-parameters")
        self.assertEqual(reconfigured.snapshot()["state_id"], "parameterOk")

    def test_shg_model_uses_the_same_runtime(self):
        runtime = ComponentRuntime(SHG_MODEL)
        self.addCleanup(runtime.close)
        self.drive(runtime, [
            ("power_on_check", "success"),
            ("function_check", "success"),
            ("parameter_dispatch", "success"),
            ("frequency_doubled_emit", "success"),
            ("laser_parameter_collect", "success"),
            ("standby_reset", "success"),
            ("shutdown", "success"),
        ], "shg")
        self.assertEqual(runtime.snapshot()["state_id"], "shutdownNormal")

    def test_multipass_model_covers_collimation_and_shot_readiness(self):
        runtime = ComponentRuntime(MULTIPASS_MODEL)
        self.addCleanup(runtime.close)
        self.drive(runtime, [("power_on_check", "success")], "multipass-power-check")
        self.assertEqual(runtime.snapshot()["state_id"], "powerOnOk")
        with self.assertRaises(TransitionError):
            runtime.execute({"command_id": "blocked-parameter", "action": "parameter_dispatch"})
        self.drive(runtime, [
            ("function_check", "success"),
            ("parameter_dispatch", "success"),
        ], "multipass-ready")
        reconfigure = runtime.execute({"command_id": "multipass-reconfigure", "action": "parameter_dispatch"})
        # A reconfiguration request first revokes the old parameter qualification.
        # The completion feedback is what makes the new configuration valid.
        self.assertEqual(reconfigure["snapshot"]["state_id"], "functionReady")
        self.assertEqual(runtime.simulate({"command_id": "multipass-reconfigure", "outcome": "failure"})["snapshot"]["state_id"], "functionReady")
        self.drive(runtime, [("parameter_dispatch", "success")], "multipass-retry")
        self.drive(runtime, [("optical_collimation", "success")], "multipass-collimation")
        self.assertEqual(runtime.snapshot()["outer_state_label"], "准直")
        self.assertEqual(runtime.snapshot()["inner_state_label"], "光路准直达标")
        self.drive(runtime, [("enter_shot_state", "success"), ("standby_reset", "success")], "multipass-shot")
        self.assertEqual(runtime.snapshot()["inner_state_label"], "复位/待机完成")
        self.assertEqual(runtime.snapshot()["outer_state_label"], "就绪")
        shutdown = runtime.execute({"command_id": "multipass-shutdown", "action": "shutdown"})
        self.assertEqual(runtime.simulate({"command_id": "multipass-shutdown", "outcome": "success"})["snapshot"]["state_id"], "shutdownNormal")
        self.assertEqual(shutdown["snapshot"]["inner_state_label"], "复位/待机完成")
        self.drive(runtime, [("power_on_check", "failure")], "multipass-restart-failure")
        self.assertEqual(runtime.snapshot()["state_id"], "unchecked")
        self.drive(runtime, [("power_on_check", "success")], "multipass-restart")
        self.assertEqual(runtime.snapshot()["state_id"], "powerOnOk")

    def test_multipass_invalidates_collimation_and_shot_state_without_faulting(self):
        runtime = ComponentRuntime(MULTIPASS_MODEL)
        self.addCleanup(runtime.close)
        self.drive(runtime, [
            ("power_on_check", "success"), ("function_check", "success"),
            ("parameter_dispatch", "success"), ("optical_collimation", "success"),
            ("enter_shot_state", "success"),
        ], "multipass-invalidations")
        self.assertEqual(runtime.snapshot()["state_id"], "shotReady")
        runtime.report_event({
            "event_id": "shot-invalidated", "event": "shot_state_invalidated",
            "observed_outer_state": "就绪",
        })
        self.assertEqual(runtime.snapshot()["state_id"], "parameterOk")
        self.drive(runtime, [("optical_collimation", "success")], "multipass-recollimate")
        runtime.report_event({
            "event_id": "collimation-invalidated", "event": "collimation_invalidated",
            "observed_outer_state": "就绪",
        })
        self.assertEqual(runtime.snapshot()["outer_state"], "ready")
        self.assertEqual(runtime.snapshot()["state_id"], "parameterOk")

    def test_multipass_fault_recovery_requires_new_power_check(self):
        runtime = ComponentRuntime(MULTIPASS_MODEL)
        self.addCleanup(runtime.close)
        runtime.report_event({
            "event_id": "multipass-fault", "event": "fault_confirmed",
            "observed_outer_state": "异常",
        })
        self.assertEqual(runtime.snapshot()["state_id"], "faultLock")
        self.drive(runtime, [("abort_reset", "success")], "multipass-recovery")
        self.assertEqual(runtime.snapshot()["state_id"], "unchecked")
        with self.assertRaises(TransitionError):
            runtime.execute({"command_id": "after-recovery", "action": "parameter_dispatch"})

    def test_all_components_require_power_then_function_before_parameters(self):
        for model in (SEED_MODEL, SHG_MODEL, PREAMP_MODEL, MULTIPASS_MODEL):
            with self.subTest(model=model.model_id):
                runtime = ComponentRuntime(model)
                self.addCleanup(runtime.close)
                for action in ("function_check", "parameter_dispatch", "power_on_self_test"):
                    with self.assertRaises(TransitionError):
                        runtime.execute({"command_id": "blocked-" + action, "action": action})
                self.drive(runtime, [("power_on_check", "failure")], "power-fail")
                self.assertEqual(runtime.snapshot()["state_id"], "qualificationInvalid" if model is SEED_MODEL else "unchecked")
                accepted = runtime.execute({"command_id": "power-ok", "action": "power_on_check"})
                self.assertEqual(accepted["status"], "accepted")
                self.assertEqual(accepted["snapshot"]["state_id"], "qualificationInvalid" if model is SEED_MODEL else "unchecked")
                runtime.simulate({"command_id": "power-ok", "outcome": "success"})
                self.assertEqual(runtime.snapshot()["outer_state"], "notReady")
                self.assertEqual(runtime.snapshot()["state_id"], "powerOnChecked" if model is SEED_MODEL else "powerOnOk")
                with self.assertRaises(TransitionError):
                    runtime.execute({"command_id": "parameter-too-early", "action": "parameter_dispatch"})
                self.drive(runtime, [("function_check", "failure")], "function-fail")
                self.assertEqual(runtime.snapshot()["outer_state"], "notReady")
                self.assertEqual(runtime.snapshot()["state_id"], "qualificationInvalid" if model is SEED_MODEL else "checkFailed")
                with self.assertRaises(TransitionError):
                    runtime.execute({"command_id": "parameter-after-failure", "action": "parameter_dispatch"})
                if model is SEED_MODEL:
                    self.drive(runtime, [("power_on_check", "success")], "function-retry-power")
                self.drive(runtime, [("function_check", "success")], "function-retry")
                self.assertEqual(runtime.snapshot()["outer_state"], "ready")
                self.assertEqual(runtime.snapshot()["state_id"], "qualified" if model is SEED_MODEL else "functionReady")
                self.drive(runtime, [("parameter_dispatch", "success")], "parameters")
                self.assertEqual(runtime.snapshot()["state_id"], "configured" if model is SEED_MODEL else "parameterOk")
                self.assertEqual(runtime.snapshot()["configuration"]["status"], "valid")

    def test_failed_power_recheck_revokes_previous_power_evidence(self):
        for model in (SEED_MODEL, SHG_MODEL, PREAMP_MODEL, MULTIPASS_MODEL):
            with self.subTest(model=model.model_id):
                runtime = ComponentRuntime(model)
                self.addCleanup(runtime.close)
                self.drive(runtime, [("power_on_check", "success"), ("function_check", "failure"),
                                     ("power_on_check", "failure")])
                self.assertEqual(runtime.snapshot()["state_id"], "qualificationInvalid" if model is SEED_MODEL else "unchecked")
                with self.assertRaises(TransitionError):
                    runtime.execute({"command_id": "cannot-reuse-old-power", "action": "function_check"})

    def test_power_qualification_survives_restart_without_becoming_ready(self):
        for model in (SEED_MODEL, SHG_MODEL, PREAMP_MODEL, MULTIPASS_MODEL):
            with self.subTest(model=model.model_id), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "component.sqlite3"
                runtime = ComponentRuntime(model, path)
                self.drive(runtime, [("power_on_check", "success")])
                runtime.close()
                recovered = ComponentRuntime(model, path)
                try:
                    self.assertEqual(recovered.snapshot()["outer_state"], "notReady")
                    self.assertEqual(recovered.snapshot()["state_id"], "powerOnChecked" if model is SEED_MODEL else "powerOnOk")
                    with self.assertRaises(TransitionError):
                        recovered.execute({"command_id": "blocked-after-restart", "action": "parameter_dispatch"})
                    self.drive(recovered, [("function_check", "success")], "after-restart")
                    self.assertEqual(recovered.snapshot()["state_id"], "qualified" if model is SEED_MODEL else "functionReady")
                finally:
                    recovered.close()

    def test_task_idempotency_and_persistence(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "component.sqlite3"
            first = ComponentRuntime(SEED_MODEL, path)
            request = {"command_id": "same", "action": "power_on_check", "parameters": {"recipe": "a"}}
            self.assertEqual(first.execute(request), first.execute(request))
            first.close()
            second = ComponentRuntime(SEED_MODEL, path)
            self.assertEqual(second.snapshot()["active_command_id"], "same")
            with self.assertRaises(TransitionError):
                second.execute({"command_id": "same", "action": "power_on_check", "parameters": {"recipe": "b"}})
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
                        return runtime.execute({"command_id": command_id, "action": "power_on_check"})["status"]
                    except TransitionError:
                        return "blocked"
                with concurrent.futures.ThreadPoolExecutor(2) as pool:
                    self.assertCountEqual(pool.map(submit, [(a, "a"), (b, "b")]), ["accepted", "blocked"])
            finally:
                a.close(); b.close()


if __name__ == "__main__":
    unittest.main()
