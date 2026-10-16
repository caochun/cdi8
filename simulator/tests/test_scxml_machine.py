"""SCXML 3.13 transition domains and entry/exit ordering for sequential states."""
from pathlib import Path
import tempfile
import unittest

from gxlf_component_simulator.scxml_machine import SCXMLMachine, TransitionError


MODEL = '''<scxml xmlns="http://www.w3.org/2005/07/scxml" version="1.0" initial="parent">
  <state id="parent" initial="first">
    <state id="first">
      <transition event="self" target="first" type="internal" />
      <transition event="priority" target="second" />
      <transition event="ancestor" target="parent" />
    </state>
    <state id="second" />
    <transition event="inside" target="second" type="internal" />
    <transition event="outside" target="second" />
    <transition event="cross" target="other" type="internal" />
    <transition event="priority" target="other" />
    <transition event="parent-self" target="parent" type="internal" />
  </state>
  <state id="other" initial="nested">
    <state id="nested" initial="last">
      <state id="last" />
    </state>
  </state>
</scxml>'''


class SCXMLTransitionTest(unittest.TestCase):
    def model(self, text=MODEL):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "model.scxml"
        path.write_text(text)
        return SCXMLMachine(path)

    def test_internal_transition_preserves_parent_while_default_external_reenters_it(self):
        machine = self.model()
        internal = machine.step("first", "inside")
        external = machine.step("first", "outside")
        self.assertEqual(internal.target, external.target)
        self.assertEqual(internal.exit_states, ("first",))
        self.assertEqual(internal.entry_states, ("second",))
        self.assertEqual(external.exit_states, ("first", "parent"))
        self.assertEqual(external.entry_states, ("parent", "second"))
        self.assertEqual(external.transition.type, "external")

    def test_internal_transition_outside_source_uses_external_semantics(self):
        step = self.model().step("first", "cross")
        self.assertEqual(step.target, "last")
        self.assertEqual(step.exit_states, ("first", "parent"))
        self.assertEqual(step.entry_states, ("other", "nested", "last"))

    def test_internal_atomic_self_transition_still_exits_and_reenters(self):
        step = self.model().step("first", "self")
        self.assertEqual(step.exit_states, ("first",))
        self.assertEqual(step.entry_states, ("first",))

    def test_compound_self_target_is_not_a_proper_descendant(self):
        step = self.model().step("second", "parent-self")
        self.assertEqual(step.target, "first")
        self.assertEqual(step.exit_states, ("second", "parent"))
        self.assertEqual(step.entry_states, ("parent", "first"))

    def test_atomic_targeting_ancestor_enters_its_default_child(self):
        step = self.model().step("first", "ancestor")
        self.assertEqual(step.target, "first")
        self.assertEqual(step.exit_states, ("first", "parent"))
        self.assertEqual(step.entry_states, ("parent", "first"))

    def test_child_transition_has_priority_and_siblings_preserve_parent(self):
        step = self.model().step("first", "priority")
        self.assertEqual(step.transition.source, "first")
        self.assertEqual(step.target, "second")
        self.assertEqual(step.exit_states, ("first",))
        self.assertEqual(step.entry_states, ("second",))

    def test_invalid_type_and_missing_target_are_rejected_on_load(self):
        for invalid in (MODEL.replace('type="internal"', 'type="invalid"'),
                        MODEL.replace('target="second"', 'target="missing"')):
            with self.subTest(invalid=invalid), self.assertRaises(TransitionError):
                self.model(invalid)


if __name__ == "__main__":
    unittest.main()
