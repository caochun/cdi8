"""Projection checks plus optional native Qt/Taurus tests against a real Tango device."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import time
import unittest

from gxlf_sim_system import SeedRuntime
from gxlf_sim_system.cli import demo
from gxlf_sim_system.gui.model import available_actions, available_confirmations, edge_matches, edges, validate_snapshot


class PanelProjectionTest(unittest.TestCase):
    def test_admissible_actions_follow_runtime_and_collection_failure_is_not_fault(self):
        runtime = SeedRuntime()
        try:
            self.assertEqual(available_actions(validate_snapshot(runtime.snapshot())), ["power_on_self_test"])
            trace = demo(runtime)
            failed = trace[-2]["result"]["snapshot"]
            self.assertEqual(available_actions(failed), ["laser_parameter_collect", "shutdown"])
            self.assertIn("shutdown_confirmed", available_confirmations(failed))
            self.assertNotIn("recovery_confirmed", available_confirmations(failed))
            event = runtime.history()[-3]
            matches = [edge for edge in edges() if edge_matches(edge, event)]
            self.assertEqual(len(matches), 1)
            self.assertEqual(matches[0].target, "collectFailed")
        finally:
            runtime.close()

    def test_unknown_or_inconsistent_remote_models_disable_control(self):
        for snapshot in ({}, {"model": "other"}, {"model": "seed-hierarchical-v2", "state_id": "unchecked", "business_state": "出光"},
                         {"model": "seed-hierarchical-v2", "state_id": "collecting", "business_state": "出光"}):
            with self.assertRaises(ValueError):
                validate_snapshot(snapshot)


GUI_AVAILABLE = all(importlib.util.find_spec(module) for module in ("taurus", "PyQt6", "tango"))


@unittest.skipUnless(GUI_AVAILABLE, "install simulator[gui] for native Taurus tests")
class NativePanelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        os.environ.setdefault("QT_API", "pyqt6")
        os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
        from taurus.qt.qtgui.application import TaurusApplication
        from taurus.external.qt import Qt
        cls.Qt = Qt
        cls.app = Qt.QApplication.instance() or TaurusApplication(args=[], app_name="SeedPanelTests")
        cls.app.setQuitOnLastWindowClosed(False)

    def wait_for(self, predicate, timeout=12):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            self.app.processEvents()
            if predicate():
                return
            time.sleep(0.02)
        self.fail("Timed out: " + self.window.connection_label.text() + " / " + self.window.message.text())

    def state_is(self, leaf):
        return self.window.connected and not self.window.busy and self.window.snapshot and self.window.snapshot["state_id"] == leaf

    def test_real_taurus_panel_events_commands_failure_retry_and_reconnect(self):
        from tango.test_context import MultiDeviceTestContext
        from gxlf_sim_system.tango.device import SeedSourceDevice
        from gxlf_sim_system.gui.window import SeedWindow
        with tempfile.TemporaryDirectory() as tmp:
            config = [{"class": SeedSourceDevice, "devices": [{"name": "gxlf/gui/seed", "properties": {
                "database_path": str(Path(tmp) / "seed.sqlite3")}}]}]
            with MultiDeviceTestContext(config, process=True, host="127.0.0.1") as context:
                address = context.get_device_access("gxlf/gui/seed")
                self.window = SeedWindow(address, simulation_controls=True)
                self.window.show()
                try:
                    self.wait_for(lambda: self.state_is("unchecked"))
                    self.assertTrue(self.window.action_buttons["power_on_self_test"].isEnabled())
                    self.assertFalse(self.window.action_buttons["seed_source_emit"].isEnabled())
                    self.window.parameters.setPlainText("[]")
                    self.window.action_buttons["power_on_self_test"].click()
                    self.assertIn("参数", self.window.message.text())
                    self.assertFalse(self.window.busy)
                    self.window.parameters.setPlainText("{}")
                    for action, running, result in [
                        ("power_on_self_test", "unchecked", "selfTestOk"),
                        ("function_check", "selfTestOk", "configIdle"),
                        ("parameter_dispatch", "configIdle", "configOk"),
                        ("seed_source_emit", "configOk", "collectIdle"),
                    ]:
                        self.window.action_buttons[action].click()
                        self.wait_for(lambda: self.state_is(running))
                        self.assertTrue(all(not b.isEnabled() for b in self.window.action_buttons.values()))
                        self.window.success_button.click()
                        self.wait_for(lambda: self.state_is(result))
                    self.window.action_buttons["laser_parameter_collect"].click()
                    self.wait_for(lambda: self.state_is("collectIdle"))
                    self.window.failure_button.click()
                    self.wait_for(lambda: self.state_is("collectFailed"))
                    self.assertEqual(self.window.snapshot["business_state"], "出光")
                    self.assertTrue(self.window.diagram.nodes["collectFailed"].active)
                    self.assertFalse(self.window.diagram.nodes["faultDetected"].active)
                    self.assertTrue(self.window.action_buttons["laser_parameter_collect"].isEnabled())
                    self.window.grab().save("/tmp/cdi8-seed-panel-collection-failed.png")
                    self.window.action_buttons["laser_parameter_collect"].click()
                    self.wait_for(lambda: self.state_is("collectIdle"))
                    self.window.success_button.click()
                    self.wait_for(lambda: self.state_is("collectOk"))
                    self.wait_for(lambda: self.window.history_table.rowCount() >= 12)

                    # A command from another Tango client must update this panel through Taurus.
                    proxy = context.get_device("gxlf/gui/seed")
                    proxy.command_inout("ReportEvent", json.dumps({"event_id": "external-fault", "event": "fault_confirmed", "observed_business_state": "设备异常"}))
                    self.wait_for(lambda: self.state_is("faultDetected"))
                    self.assertTrue(self.window.diagram.nodes["faultDetected"].active)
                    self.assertEqual(self.window.event_choice.currentData(), "recovery_confirmed")
                    self.window.event_button.click()
                    self.wait_for(lambda: self.state_is("unchecked"))

                    # Disabled simulation mode gates both buttons and direct widget callbacks.
                    self.window.simulation_controls = False
                    self.window.action_buttons["power_on_self_test"].click()
                    self.wait_for(lambda: self.state_is("unchecked"))
                    self.assertFalse(self.window.success_button.isEnabled())
                    self.assertFalse(self.window.event_button.isEnabled())
                    self.window.simulate("success")
                    self.assertFalse(self.window.busy)
                    self.window.simulation_controls = True
                    self.window.update_controls()
                    self.window.success_button.click()
                    self.wait_for(lambda: self.state_is("selfTestOk"))

                    # Disconnecting keeps stale observations clearly labelled and blocks commands.
                    self.window.address.setText("invalid-device-address")
                    self.window.connect_device()
                    self.wait_for(lambda: "失败" in self.window.connection_label.text())
                    self.assertFalse(self.window.connected)
                    self.assertTrue(all(not b.isEnabled() for b in self.window.action_buttons.values()))
                    self.window.address.setText(address)
                    self.window.connect_device()
                    self.wait_for(lambda: self.state_is("selfTestOk"))
                    self.assertTrue(self.window.action_buttons["function_check"].isEnabled())
                finally:
                    self.window.close()
                    self.app.processEvents()
                    self.assertFalse(self.window.worker_thread.isRunning())


if __name__ == "__main__":
    unittest.main()
