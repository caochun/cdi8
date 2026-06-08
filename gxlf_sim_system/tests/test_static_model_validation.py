from __future__ import annotations

import unittest

from gxlf_sim_system.guards import normalize_runtime_guards
from gxlf_sim_system.loader import load_models
from gxlf_sim_system.validation import validate_model_bundle

from .support import REPO_ROOT


SUPPORTED_NODE_FIELDS = {
    "call_mode",
    "command",
    "completion_criteria",
    "dependency_policy",
    "depends",
    "description",
    "guard_failure_policy",
    "hold_constraint",
    "id",
    "input",
    "linked_behavior",
    "on_failure",
    "operator_intervention",
    "output",
    "pipeline_overlap",
    "release_interlock_after_compensation",
    "runtime_guards",
    "safety_critical",
    "safety_scope",
    "stage",
    "target",
    "timeout",
    "timing",
    "type",
}

EXECUTED_NODE_FIELDS = {
    "id",
    "type",
    "stage",
    "depends",
    "target",
    "command",
    "call_mode",
    "timeout",
    "timing",
    "runtime_guards",
}

KNOWN_POLICY_GAPS = {
    "completion_criteria",
    "dependency_policy",
    "guard_failure_policy",
    "hold_constraint",
    "linked_behavior",
    "on_failure",
    "operator_intervention",
    "pipeline_overlap",
    "release_interlock_after_compensation",
    "safety_critical",
    "safety_scope",
}

EXPECTED_UNKNOWN_GUARDS = {
    ("N09", "门控服务运行状态正常"),
    ("N09", "人员计数归零"),
    ("N14", "片放吹扫停止"),
    ("N22", "各系统服务运行状态正常"),
    ("N22", "泵浦发射准备状态正常"),
    ("N34", "警示音乐停止"),
    ("N37", "泵浦触发结果正常"),
    ("N47", "警示音乐停止"),
    ("N58", "门控服务运行状态正常"),
}


class StaticModelValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.bundle = load_models(REPO_ROOT)

    def test_existing_cross_reference_validator_passes(self) -> None:
        report = validate_model_bundle(self.bundle)
        self.assertFalse(report.errors, "\n".join(report.errors))

    def test_node_field_support_matrix_is_explicit(self) -> None:
        fields = {field for node in self.bundle.flow.get("nodes", {}).values() for field in node}
        self.assertEqual(fields, SUPPORTED_NODE_FIELDS)
        self.assertTrue(EXECUTED_NODE_FIELDS < SUPPORTED_NODE_FIELDS)
        self.assertTrue(KNOWN_POLICY_GAPS < SUPPORTED_NODE_FIELDS)

    def test_dag_references_exist_and_ids_are_unique(self) -> None:
        names = set(self.bundle.nodes)
        ids = [node.node_id for node in self.bundle.nodes.values()]
        self.assertEqual(len(ids), len(set(ids)))
        for node in self.bundle.nodes.values():
            for dependency in node.depends:
                self.assertIn(dependency, names, f"{node.node_id} depends on unknown node {dependency}")

    def test_runtime_guard_parser_gaps_are_tracked(self) -> None:
        unknown_guards: set[tuple[str, str]] = set()
        for node in self.bundle.nodes.values():
            for guard in normalize_runtime_guards(node.raw.get("runtime_guards") or []):
                if guard.get("type") == "unknown":
                    unknown_guards.add((node.node_id, str(guard.get("check"))))
        self.assertEqual(unknown_guards, EXPECTED_UNKNOWN_GUARDS)

    def test_model_declares_policy_fields_that_engine_has_not_fully_implemented(self) -> None:
        fields = {field for node in self.bundle.flow.get("nodes", {}).values() for field in node}
        declared_gaps = fields & KNOWN_POLICY_GAPS
        self.assertEqual(declared_gaps, KNOWN_POLICY_GAPS)


if __name__ == "__main__":
    unittest.main()
