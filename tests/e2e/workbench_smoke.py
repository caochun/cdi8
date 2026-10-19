"""Verify model publishing and generic Tango execution against fresh services.

This creates models and replaces the demo environment. Run it with isolated
server/bridge data, not an environment containing work in progress.
"""
import argparse
import json
from pathlib import Path
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]


def request(url, payload=None):
    body = None if payload is None else json.dumps(payload).encode()
    try:
        with urlopen(Request(url, data=body, headers={"Content-Type": "application/json"}), timeout=60) as response:
            return json.load(response)
    except HTTPError as exc:
        raise RuntimeError(exc.read().decode()) from exc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    parser.add_argument("--bridge", default="http://127.0.0.1:8766")
    args = parser.parse_args()
    models = {}
    for kind, filename in [("scxml", "component.scxml"), ("bpmn", "process.bpmn20.xml")]:
        xml = (ROOT / "examples" / "minimal" / filename).read_text()
        saved = request(args.base + "/api/workbench/models", {"name": filename, "kind": kind, "xml": xml})
        version = request(args.base + "/api/workbench/models/" + saved["id"] + "/versions", {})
        models[kind] = {"model": saved["id"], "revision": version["id"]}
    config = {
        "entry": models["bpmn"]["model"],
        "processes": [models["bpmn"]],
        "devices": [{**models["scxml"], "component": "demo", "count": 2, "autoComplete": False, "delayMs": 50}],
        "autoComplete": False,
        "variables": {"commandTimeout": "PT30S", "workflowTimeout": "PT60S"},
        "conditions": {"alignment_ready": False},
    }
    config = request(args.base + "/api/workbench/environment", config)
    run = request(args.base + "/api/workbench/runs", config)
    deadline, completed = time.monotonic() + 30, set()
    while time.monotonic() < deadline:
        for device in request(args.bridge + "/devices"):
            command = device.get("snapshot", {}).get("active_command_id")
            if not command or command in completed:
                continue
            url = args.bridge + "/devices/" + device["id"] + "/simulate"
            for _ in range(2):
                result = request(url, {"command_id": command, "outcome": "success"})
                assert result["status"] == "succeeded", result
            completed.add(command)
        state = request(args.base + "/api/runs/" + run["id"])
        if state["ended"]:
            assert state["outcome"] == "SUCCEEDED", state
            assert len(state["commands"]) == len(completed) == 2, state
            assert len(state["initialDeviceSnapshots"]) == 3, state
            assert state["modelVersions"][models["bpmn"]["model"]] == models["bpmn"]["revision"]
            for device in request(args.bridge + "/devices"):
                if device["component"] == "demo":
                    assert device["snapshot"]["state_id"] == "active", device
                    assert device["snapshot"]["model_revision"] == models["scxml"]["revision"], device
            events = request(args.base + "/api/workbench/runs/" + run["id"] + "/trace")
            assert events, "Device events were not recorded"
            print("PASS workbench publish / environment / two Tango instances / duplicate feedback / trace", run["id"])
            return
        time.sleep(0.1)
    raise AssertionError("Workbench run did not finish: " + json.dumps(state, ensure_ascii=False))


if __name__ == "__main__":
    main()
