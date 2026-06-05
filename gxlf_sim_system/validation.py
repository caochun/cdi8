from __future__ import annotations

from dataclasses import dataclass, field

from .loader import ModelBundle


SYSTEM_ALIASES = {
    "多程放大系统": "多程放大组件",
    "多程放大组件": "多程放大系统",
}

COMMAND_ALIASES = {
    "ShotMotionReady": "MeasShotMotionReady",
}


@dataclass
class ValidationReport:
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.errors


def validate_model_bundle(bundle: ModelBundle) -> ValidationReport:
    report = ValidationReport()
    flow_systems = set(bundle.flow.get("systems") or {})
    catalog_systems = set(bundle.state_machine.get("system_instance_catalog") or {})
    template_systems = set(bundle.service_type_by_system)

    for system_name in sorted(flow_systems - catalog_systems):
        alias = SYSTEM_ALIASES.get(system_name)
        if alias in catalog_systems:
            report.warnings.append(f"system name differs: flow has {system_name}, state machine has {alias}")
        else:
            report.errors.append(f"system {system_name} is missing from system_instance_catalog")

    for system_name in sorted(flow_systems - template_systems):
        alias = SYSTEM_ALIASES.get(system_name)
        if alias in template_systems:
            report.warnings.append(f"service template alias needed: {system_name} -> {alias}")
        else:
            report.errors.append(f"system {system_name} has no service_type_template")

    for node in bundle.nodes.values():
        if node.node_type == "join" or not node.command:
            continue
        for system_name in node.target_systems:
            effective_system = system_name
            if effective_system not in bundle.service_type_by_system:
                effective_system = SYSTEM_ALIASES.get(system_name, system_name)
            service_type = bundle.service_type_by_system.get(effective_system)
            if not service_type:
                continue
            template = bundle.state_machine.get("service_type_templates", {}).get(service_type, {})
            commands = template.get("commands") or {}
            if node.command not in commands:
                alias = COMMAND_ALIASES.get(node.command)
                if alias in commands:
                    report.warnings.append(
                        f"{node.node_id} {node.name}: command alias needed {node.command} -> {alias}"
                    )
                else:
                    report.errors.append(
                        f"{node.node_id} {node.name}: {service_type} has no command {node.command}"
                    )

    n53 = bundle.nodes.get("靶瞄数据分析")
    n54 = bundle.nodes.get("靶瞄收靶")
    if n53 and n54 and "靶瞄数据分析" not in n54.depends and "靶瞄收靶" not in n53.depends:
        report.warnings.append(
            "N53 靶瞄数据分析 and N54 靶瞄收靶 both touch 靶瞄准定位系统 but lack an ordering dependency"
        )

    return report
