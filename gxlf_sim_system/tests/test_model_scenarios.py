from __future__ import annotations

import json
import threading
import unittest
from pathlib import Path
from typing import Any

from gxlf_sim_system.domain import FlowStatus, NodeStatus
from gxlf_sim_system.engine import FlowEngine, FlowRunControl
from gxlf_sim_system.guards import FlowRuntimeContext
from gxlf_sim_system.loader import load_models
from gxlf_sim_system.service_sim import SimFault, SimFaultRegistry

from .support import (
    REPO_ROOT,
    build_real_engine,
    load_scenario,
    normalize_status,
    scenario_paths,
    wait_until,
)


class CommandRecorder:
    def __init__(self, engine: FlowEngine):
        self.records: list[dict[str, Any]] = []
        self._original = engine.tango_adapter.command_inout

        def wrapped(command_name: str, dev_string_json: str) -> str:
            self.records.append(json.loads(dev_string_json))
            return self._original(command_name, dev_string_json)

        engine.tango_adapter.command_inout = wrapped  # type: ignore[method-assign]

    def count_for_node(self, node_id: str) -> int:
        return sum(1 for record in self.records if record.get("node_id") == node_id)


def apply_context(context: FlowRuntimeContext, data: dict[str, Any] | None) -> None:
    data = data or {}
    for key, value in (data.get("flags") or {}).items():
        context.set_flag(str(key), value)
    for key, value in (data.get("interlocks") or {}).items():
        context.set_interlock(str(key), value)


def status_nodes(engine: FlowEngine, status: NodeStatus) -> set[str]:
    return {
        node.node_id
        for name, node in engine.nodes.items()
        if engine.node_statuses.get(name) == status
    }


def event_types(events: list[Any]) -> list[str]:
    return [str(event.event_type) for event in events]


def make_fault_registry(scenario: dict[str, Any]) -> SimFaultRegistry:
    registry = SimFaultRegistry()
    for fault in scenario.get("faults") or []:
        registry.add(
            SimFault(
                behavior=str(fault.get("behavior") or ""),
                node_id=str(fault.get("node_id") or ""),
                system_name=str(fault.get("system_name") or fault.get("system") or ""),
                service_id=str(fault.get("service_id") or ""),
                instance_code=str(fault.get("instance_code") or fault.get("instance") or ""),
                command=str(fault.get("command") or ""),
            )
        )
    return registry


def set_service_health(engine: FlowEngine, payload: dict[str, Any] | None) -> None:
    payload = payload or {}
    system_name = str(payload.get("system_name") or "")
    health_state = str(payload.get("health_state") or "")
    for instance in engine.registry.instances_by_system.get(system_name, []):
        instance.health_state = health_state


def assert_expectations(
    test: unittest.TestCase,
    scenario: dict[str, Any],
    engine: FlowEngine,
    events: list[Any],
    commands: CommandRecorder,
    result: Any | None,
    expect_key: str = "expect",
) -> None:
    expect = scenario.get(expect_key) or {}

    if "flow_status" in expect:
        test.assertIsNotNone(result, f"{scenario['id']} has no result")
        test.assertEqual(normalize_status(result.flow_status), str(expect["flow_status"]))

    if "executed_nodes" in expect:
        test.assertIsNotNone(result, f"{scenario['id']} has no result")
        test.assertEqual(result.executed_nodes, int(expect["executed_nodes"]))

    if "completed_nodes" in expect:
        expected = expect["completed_nodes"]
        if isinstance(expected, int):
            test.assertIsNotNone(result, f"{scenario['id']} has no result")
            actual_count = sum(1 for status in result.node_statuses.values() if status == NodeStatus.COMPLETED)
            test.assertEqual(actual_count, expected)
        else:
            actual = status_nodes(engine, NodeStatus.COMPLETED)
            test.assertTrue(set(expected) <= actual, f"missing completed nodes: {set(expected) - actual}")

    if "failed_nodes" in expect:
        actual = status_nodes(engine, NodeStatus.FAILED)
        test.assertEqual(actual, set(expect["failed_nodes"]))

    if "waiting_nodes" in expect:
        actual = status_nodes(engine, NodeStatus.WAITING_GUARD)
        test.assertTrue(set(expect["waiting_nodes"]) <= actual, f"missing waiting nodes: {set(expect['waiting_nodes']) - actual}")

    if "commands_dispatched" in expect:
        test.assertEqual(len(commands.records), int(expect["commands_dispatched"]))

    for node_id, expected_count in (expect.get("commands_dispatched_for") or {}).items():
        test.assertEqual(commands.count_for_node(str(node_id)), int(expected_count), f"{node_id} command count")

    includes = ((expect.get("engine_events") or {}).get("includes") or [])
    actual_events = event_types(events)
    for event_type in includes:
        test.assertIn(event_type, actual_events)


class ModelScenarioTest(unittest.TestCase):
    def test_all_declared_scenarios(self) -> None:
        paths = scenario_paths()
        self.assertTrue(paths, "no scenario yaml files found")
        for path in paths:
            with self.subTest(path=str(path.relative_to(Path(__file__).parent))):
                scenario = load_scenario(path)
                if scenario.get("suite") == "model_gap":
                    self._run_model_gap_scenario(scenario)
                else:
                    self._run_model_scenario(scenario)

    def _run_model_gap_scenario(self, scenario: dict[str, Any]) -> None:
        bundle = load_models(REPO_ROOT)
        field = str((scenario.get("check") or {}).get("field"))
        declared = sum(1 for node in bundle.flow.get("nodes", {}).values() if field in node)
        expect = scenario.get("expect") or {}
        self.assertEqual(declared, int(expect.get("declared_on_nodes", declared)))
        self.assertEqual(expect.get("engine_support"), "unsupported")

    def _run_model_scenario(self, scenario: dict[str, Any]) -> None:
        context = FlowRuntimeContext()
        apply_context(context, scenario.get("initial_context"))
        events: list[Any] = []
        fault_registry = make_fault_registry(scenario)
        engine = build_real_engine(runtime_context=context, events=events, fault_registry=fault_registry)
        commands = CommandRecorder(engine)
        control = FlowRunControl()
        run_config = scenario.get("run") or {}
        result_box: dict[str, Any] = {}

        def run_engine() -> None:
            result_box["result"] = engine.run(max_nodes=run_config.get("max_nodes"), control=control)

        if run_config.get("async") or scenario.get("then"):
            thread = threading.Thread(target=run_engine, daemon=True)
            thread.start()
            self._wait_for_expectation(scenario, engine, events, "expect_before_action")
            if scenario.get("expect_before_action"):
                assert_expectations(self, scenario, engine, events, commands, None, "expect_before_action")
                then = scenario.get("then") or {}
                set_service_health(engine, then.get("set_service_health"))
                apply_context(context, then.get("update_context"))
                if then.get("set_service_health"):
                    context.notify_update()
            self._wait_for_expectation(scenario, engine, events, "expect_before_update")
            self._wait_for_expectation(scenario, engine, events, "expect_before_stop")
            if scenario.get("expect_before_update"):
                assert_expectations(self, scenario, engine, events, commands, None, "expect_before_update")
            if scenario.get("expect_before_stop"):
                assert_expectations(self, scenario, engine, events, commands, None, "expect_before_stop")
            then = scenario.get("then") or {}
            apply_context(context, then.get("update_context"))
            if then.get("stop"):
                control.stop()
            then_after = scenario.get("then_after_expectation") or {}
            if then_after.get("stop"):
                control.stop()
            thread.join(5)
            self.assertFalse(thread.is_alive(), f"{scenario['id']} did not finish")
            result = result_box.get("result")
        else:
            result = engine.run(max_nodes=run_config.get("max_nodes"), control=control)

        assert_expectations(self, scenario, engine, events, commands, result, "expect")

    def _wait_for_expectation(
        self,
        scenario: dict[str, Any],
        engine: FlowEngine,
        events: list[Any],
        expect_key: str,
    ) -> None:
        expect = scenario.get(expect_key)
        if not expect:
            return
        waiting_nodes = set(expect.get("waiting_nodes") or [])
        completed_nodes = set(expect.get("completed_nodes") or [])
        event_includes = set(((expect.get("engine_events") or {}).get("includes") or []))

        def predicate() -> bool:
            waiting_ok = waiting_nodes <= status_nodes(engine, NodeStatus.WAITING_GUARD)
            completed_ok = completed_nodes <= status_nodes(engine, NodeStatus.COMPLETED)
            events_ok = event_includes <= set(event_types(events))
            return waiting_ok and completed_ok and events_ok

        self.assertTrue(wait_until(predicate, timeout_seconds=5), f"{scenario['id']} did not reach {expect_key}")


if __name__ == "__main__":
    unittest.main()
