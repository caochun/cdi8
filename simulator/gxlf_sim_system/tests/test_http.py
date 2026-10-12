import json
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from gxlf_sim_system import SeedRuntime
from gxlf_sim_system.http import create_server


class HttpTest(unittest.TestCase):
    def setUp(self):
        self.a, self.b = SeedRuntime(), SeedRuntime()
        self.server = create_server({"seed_01": self.a, "seed_02": self.b}, port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.thread.join(2)
        self.server.server_close()
        self.a.close()
        self.b.close()

    def request(self, path, value=None):
        body = None if value is None else json.dumps(value).encode()
        req = Request(self.base + path, data=body, headers={"Content-Type": "application/json"})
        try:
            response = urlopen(req, timeout=3)
        except HTTPError as ex:
            response = ex
        with response:
            return response.status, json.load(response)

    def test_request_result_state_and_instance_isolation(self):
        code, result = self.request("/devices/seed_01/commands", {"command_id": "one", "action": "power_on_self_test"})
        self.assertEqual(code, 200)
        self.assertEqual(result["snapshot"]["current_state"], "自检中")
        self.assertEqual(self.request("/devices/seed_02")[1]["state_id"], "Idle")
        self.assertEqual(self.request("/devices/seed_02/commands/one")[0], 409)
        code, result = self.request("/devices/seed_01/simulate", {"command_id": "one", "outcome": "success"})
        self.assertEqual(code, 200)
        self.assertEqual(result["snapshot"]["state_id"], "TestOK")
        self.assertEqual(self.request("/devices/seed_01/commands/one")[1], result)
        self.assertEqual(len(self.request("/devices/seed_01/history")[1]), 2)

    def test_invalid_routes_payload_and_illegal_state_are_distinct(self):
        self.assertEqual(self.request("/devices/shg_01")[0], 404)
        self.assertEqual(self.request("/devices/seed_01/cancel", {"command_id": "one"})[0], 404)
        self.assertEqual(self.request("/devices/seed_01/commands", ["not-an-object"])[0], 400)
        self.assertEqual(self.request("/devices/seed_01/history?limit=invalid")[0], 400)
        self.assertEqual(self.request("/devices/seed_01/commands", {"command_id": "bad", "action": "seed_source_emit"})[0], 409)
        self.assertEqual(self.request("/devices/seed_01/history")[1], [])

    def test_fault_confirmation_is_not_a_normal_task_failure(self):
        self.request("/devices/seed_01/commands", {"command_id": "one", "action": "power_on_self_test"})
        payload = {"event_id": "fault", "event": "fault_confirmed", "observed_business_state": "异常"}
        code, result = self.request("/devices/seed_01/events", payload)
        self.assertEqual(code, 200)
        self.assertEqual(result["snapshot"]["state_id"], "Fault")
        self.assertEqual(self.request("/devices/seed_01/commands/one")[1]["status"], "interrupted")
        self.assertEqual(self.request("/devices/seed_01/events", payload)[1], result)


if __name__ == "__main__":
    unittest.main()
