"""Optional actual Tango command, attribute and change-event validation."""
import importlib.util
import json
from pathlib import Path
import tempfile
from threading import Event
import unittest


@unittest.skipUnless(importlib.util.find_spec("tango"), "PyTango extra is not installed")
class TangoTest(unittest.TestCase):
    def test_commands_attributes_change_events_and_collection_failure(self):
        import tango
        from tango.test_context import MultiDeviceTestContext
        from gxlf_sim_system.tango.device import SeedSourceDevice
        from gxlf_sim_system.tango.gateway import TangoAdapter
        with tempfile.TemporaryDirectory() as tmp:
            devices = [{"class": SeedSourceDevice, "devices": [{"name": "gxlf/test/seed", "properties": {
                "database_path": str(Path(tmp) / "seed.sqlite3")}}]}]
            with MultiDeviceTestContext(devices, process=True, host="127.0.0.1") as context:
                proxy = context.get_device("gxlf/test/seed")
                adapter = TangoAdapter(proxy)
                changed = Event()
                def observe(event):
                    if not event.err and json.loads(event.attr_value.value)["state_id"] == "CollectFailed":
                        changed.set()
                subscription = proxy.subscribe_event("Snapshot", tango.EventType.CHANGE_EVENT, observe)
                try:
                    self.assertEqual(adapter.snapshot()["state_id"], "Idle")
                    for index, action in enumerate(("power_on_self_test", "function_check", "parameter_dispatch", "seed_source_emit", "laser_parameter_collect")):
                        payload = {"command_id": f"tango-{index}", "action": action}
                        accepted = adapter.execute(payload)
                        self.assertEqual(adapter.execute(payload), accepted)
                        result = adapter.simulate({"command_id": payload["command_id"], "outcome": "failure" if index == 4 else "success"})
                        self.assertEqual(adapter.result(payload["command_id"]), result)
                    self.assertEqual(adapter.snapshot()["business_state"], "出光")
                    self.assertEqual(adapter.snapshot()["state_id"], "CollectFailed")
                    self.assertTrue(changed.wait(3), "change event not received")
                    self.assertEqual(len(adapter.history()), 10)
                    adapter.report_event({"event_id": "shutdown", "event": "shutdown_confirmed", "observed_business_state": "未就绪"})
                    self.assertEqual(adapter.snapshot()["state_id"], "Idle")
                finally:
                    proxy.unsubscribe_event(subscription)


if __name__ == "__main__":
    unittest.main()
