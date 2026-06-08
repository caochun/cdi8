from __future__ import annotations

import unittest

from gxlf_sim_system.domain import FlowStatus, NodeStatus
from gxlf_sim_system.engine import FlowRunControl
from gxlf_sim_system.guards import FlowRuntimeContext

from .support import (
    AdapterAction,
    RecordingAdapter,
    make_engine,
    make_node,
    make_registry,
    run_engine_in_thread,
    wait_until,
)


class EngineSemanticsTest(unittest.TestCase):
    def test_dependency_orders_downstream_after_upstream(self) -> None:
        node_a = make_node("A", "N01")
        node_b = make_node("B", "N02", depends=["A"])
        engine, adapter, _ = make_engine([node_b, node_a])

        result = engine.run()

        self.assertEqual(result.flow_status, FlowStatus.COMPLETED)
        self.assertEqual([record["node_name"] for record in adapter.command_records], ["A", "B"])

    def test_fanout_all_success_completes_node(self) -> None:
        registry = make_registry(count=2)
        node = make_node("A", "N01")
        engine, adapter, _ = make_engine([node], registry=registry)

        result = engine.run()

        self.assertEqual(result.node_statuses["A"], NodeStatus.COMPLETED)
        self.assertEqual(len(adapter.command_records), 2)

    def test_rejected_target_fails_node_and_flow(self) -> None:
        registry = make_registry(count=2)
        adapter = RecordingAdapter({"fake.system_a.svc02": AdapterAction(behavior="reject")})
        engine, _, events = make_engine([make_node("A", "N01")], registry=registry, adapter=adapter)

        result = engine.run()

        self.assertEqual(result.flow_status, FlowStatus.FAILED)
        self.assertEqual(result.node_statuses["A"], NodeStatus.FAILED)
        self.assertIn("target_failed", [event.event_type for event in events])

    def test_callback_failed_fails_node_and_flow(self) -> None:
        adapter = RecordingAdapter({"*": AdapterAction(behavior="success", task_state="failed", result_status="failed")})
        engine, _, _ = make_engine([make_node("A", "N01")], adapter=adapter)

        result = engine.run()

        self.assertEqual(result.flow_status, FlowStatus.FAILED)
        self.assertEqual(result.node_statuses["A"], NodeStatus.FAILED)

    def test_callback_timeout_fails_node_and_flow(self) -> None:
        adapter = RecordingAdapter({"*": AdapterAction(behavior="timeout")})
        engine, _, events = make_engine([make_node("A", "N01")], adapter=adapter)

        result = engine.run()

        self.assertEqual(result.flow_status, FlowStatus.FAILED)
        self.assertEqual(result.node_statuses["A"], NodeStatus.FAILED)
        self.assertTrue(any("timeout" in event.detail for event in events if event.event_type == "target_failed"))

    def test_guard_false_waits_without_dispatch_then_resumes(self) -> None:
        context = FlowRuntimeContext()
        context.set_flag("ready", False)
        node = make_node(
            "A",
            "N01",
            raw={"runtime_guards": [{"type": "context_flag", "key": "ready", "expected": True}]},
        )
        engine, adapter, events = make_engine([node], context=context)
        control = FlowRunControl()
        thread, box = run_engine_in_thread(engine, control=control)

        self.assertTrue(wait_until(lambda: engine.node_statuses["A"] == NodeStatus.WAITING_GUARD))
        self.assertEqual(adapter.command_records, [])
        self.assertIn("node_guard_blocked", [event.event_type for event in events])

        context.set_flag("ready", True)
        thread.join(2)

        self.assertFalse(thread.is_alive())
        result = box["result"]
        self.assertEqual(result.node_statuses["A"], NodeStatus.COMPLETED)
        self.assertIn("node_guard_passed", [event.event_type for event in events])

    def test_stop_during_guard_wait_aborts_flow(self) -> None:
        context = FlowRuntimeContext()
        context.set_flag("ready", False)
        node = make_node(
            "A",
            "N01",
            raw={"runtime_guards": [{"type": "context_flag", "key": "ready", "expected": True}]},
        )
        engine, _, _ = make_engine([node], context=context)
        control = FlowRunControl()
        thread, box = run_engine_in_thread(engine, control=control)

        self.assertTrue(wait_until(lambda: engine.node_statuses["A"] == NodeStatus.WAITING_GUARD))
        control.stop()
        thread.join(2)

        self.assertFalse(thread.is_alive())
        self.assertEqual(box["result"].flow_status, FlowStatus.ABORTED)

    def test_node_state_started_means_started_or_already_completed(self) -> None:
        node_a = make_node("A", "N01")
        node_b = make_node(
            "B",
            "N02",
            depends=["A"],
            raw={"runtime_guards": [{"type": "node_state", "node": "A", "expected": "started"}]},
        )
        engine, _, _ = make_engine([node_a, node_b])

        result = engine.run()

        self.assertEqual(result.flow_status, FlowStatus.COMPLETED)
        self.assertEqual(result.node_statuses["B"], NodeStatus.COMPLETED)


if __name__ == "__main__":
    unittest.main()
