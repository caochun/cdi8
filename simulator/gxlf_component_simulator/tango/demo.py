"""Definitions for the single-process Tango demo device server."""

from pathlib import Path

from ..component_model import model_by_name

COMPONENTS = ("seed", "shg", "preamp", "multipass")


def validate_counts(counts):
    normalized = {component: int(counts.get(component, 0)) for component in COMPONENTS}
    if any(count < 1 for count in normalized.values()):
        raise ValueError("每类组件的实例数必须至少为 1")
    return normalized


def component_specs(counts):
    """Return logical IDs and Tango names for all component instances."""
    counts = validate_counts(counts)
    return [
        (key, component, f"gxlf/{component}/{key}")
        for component in COMPONENTS
        for number in range(1, counts[component] + 1)
        for key in (f"{component}_{number:02}",)
    ]


def component_definition(specs, data_directory, database_overrides=None,
                         auto_complete=False, completion_delay_ms=500):
    """Build one Device Server definition containing every component instance."""
    from .device import ComponentDevice

    if int(completion_delay_ms) < 0:
        raise ValueError("completion_delay_ms 不能为负数")
    data_directory = Path(data_directory)
    database_overrides = database_overrides or {}
    return [{"class": ComponentDevice, "devices": [
        {"name": name, "properties": {
            "component_model": model_by_name(component).model_id,
            "database_path": str(Path(database_overrides.get(key, data_directory / (
                key + "-v" + str(model_by_name(component).model_version) + ".sqlite3"
            ))).resolve()),
            "auto_complete": bool(auto_complete),
            "auto_complete_delay_ms": int(completion_delay_ms),
        }}
        for key, component, name in specs
    ]}]


def reset_component_data(specs, data_directory):
    """Remove only the generated SQLite files for the selected demo instances."""
    data_directory = Path(data_directory)
    for key, component, _ in specs:
        path = data_directory / (key + "-v" + str(model_by_name(component).model_version) + ".sqlite3")
        for candidate in (path, Path(str(path) + "-wal"), Path(str(path) + "-shm")):
            candidate.unlink(missing_ok=True)


def component_bindings(context, specs):
    """Map logical IDs to the Tango addresses exported by a test context."""
    return {
        key: {"component": component, "address": context.get_device_access(name)}
        for key, component, name in specs
    }
