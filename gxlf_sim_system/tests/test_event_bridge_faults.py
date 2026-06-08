from __future__ import annotations

import unittest
from pathlib import Path

from gxlf_sim_system.event_bridge import BridgeConfig, BridgeEngineController, EventBroadcaster

from .support import build_real_engine


class EventBridgeFaultControlTest(unittest.TestCase):
    def test_clear_faults_resets_runtime_injections(self) -> None:
        controller = BridgeEngineController(BridgeConfig(root=Path(".")), EventBroadcaster())
        controller.command("set_interlock", {"key": "safety", "value": "abnormal"})
        controller.command("set_flag", {"key": "recipe_loaded", "value": False})
        controller.command("set_service_health", {"system_name": "光纤种子源组件", "health_state": "offline"})
        controller.command(
            "inject_fault",
            {"fault": {"behavior": "callback_timeout", "node_id": "N04", "system_name": "光纤种子源组件"}},
        )

        engine = build_real_engine(runtime_context=controller.runtime_context, fault_registry=controller.fault_registry)
        controller.apply_service_health_overrides(engine)
        self.assertEqual(controller.runtime_context.interlocks["safety"], "abnormal")
        self.assertEqual(controller.runtime_context.flags["recipe_loaded"], False)
        self.assertTrue(controller.fault_registry.snapshot())
        self.assertTrue(controller.service_health_overrides)
        self.assertEqual(engine.registry.instances_by_system["光纤种子源组件"][0].health_state, "offline")

        result = controller.command("clear_faults")

        self.assertTrue(result["accepted"])
        self.assertEqual(controller.runtime_context.interlocks["safety"], "normal")
        self.assertEqual(controller.runtime_context.flags["recipe_loaded"], True)
        self.assertEqual(controller.runtime_context.flags["central_control_healthy"], True)
        self.assertEqual(controller.fault_registry.snapshot(), [])
        self.assertEqual(controller.service_health_overrides, {})


if __name__ == "__main__":
    unittest.main()
