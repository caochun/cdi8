from pathlib import Path
import tempfile
import unittest

try:
    import tango
except ImportError:
    tango = None

from gxlf_component_simulator.tango.demo import (component_definition, component_specs,
                                                  reset_component_data, validate_counts)


class DemoConfigurationTest(unittest.TestCase):
    def test_counts_create_one_logical_device_per_instance(self):
        counts = {"seed": 2, "shg": 3, "preamp": 10, "multipass": 60}
        specs = component_specs(counts)
        self.assertEqual(len(specs), 75)
        self.assertEqual([key for key, _, _ in specs if key.startswith("preamp_")][-1], "preamp_10")
        self.assertEqual([key for key, _, _ in specs if key.startswith("multipass_")][-1], "multipass_60")
        self.assertEqual(len({name for _, _, name in specs}), 75)

    def test_each_count_must_be_positive(self):
        with self.assertRaisesRegex(ValueError, "至少为 1"):
            validate_counts({"seed": 1, "shg": 1, "preamp": 0, "multipass": 1})

    @unittest.skipIf(tango is None, "PyTango not installed")
    def test_auto_completion_properties_are_applied_to_every_component(self):
        with tempfile.TemporaryDirectory() as directory:
            specs = component_specs({"seed": 1, "shg": 1, "preamp": 1, "multipass": 1})
            definition = component_definition(specs, Path(directory),
                                              auto_complete=True, completion_delay_ms=125)
        properties = definition[0]["devices"][0]["properties"]
        self.assertTrue(properties["auto_complete"])
        self.assertEqual(properties["auto_complete_delay_ms"], 125)

    def test_reset_data_removes_only_selected_instance_databases(self):
        with tempfile.TemporaryDirectory() as directory:
            specs = component_specs({"seed": 1, "shg": 1, "preamp": 1, "multipass": 1})
            paths = [Path(directory) / (key + "-v" + str({
                "seed": 2, "shg": 2, "preamp": 3, "multipass": 3}[component]) + ".sqlite3")
                     for key, component, _ in specs]
            for path in paths:
                path.touch()
                Path(str(path) + "-wal").touch()
                Path(str(path) + "-shm").touch()
            reset_component_data(specs, directory)
            self.assertTrue(all(not path.exists() for path in paths))
            self.assertTrue(all(not Path(str(path) + suffix).exists()
                                for path in paths for suffix in ("-wal", "-shm")))


if __name__ == "__main__":
    unittest.main()
