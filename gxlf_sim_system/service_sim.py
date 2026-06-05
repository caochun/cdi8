from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any

from .contracts import ContractError, ContractValidator, make_error
from .models import ServiceInstance, ServiceTarget, TaskCallback

SYSTEM_ALIASES = {
    "多程放大系统": "多程放大组件",
}

COMMAND_ALIASES = {
    "ShotMotionReady": "MeasShotMotionReady",
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class ModelConsistencyError(ValueError):
    pass


class SimServiceRegistry:
    def __init__(self, state_machine_model: dict[str, Any]):
        self.state_machine_model = state_machine_model
        self.catalog = state_machine_model.get("system_instance_catalog") or {}
        self.templates = state_machine_model.get("service_type_templates") or {}
        self.service_type_by_system = self._build_service_type_index()
        self.instances_by_id: dict[str, ServiceInstance] = {}
        self.instances_by_system: dict[str, list[ServiceInstance]] = {}
        self._build_instances()

    def _build_service_type_index(self) -> dict[str, str]:
        mapping: dict[str, str] = {}
        for service_type, template in self.templates.items():
            for system_name in template.get("applies_to") or []:
                mapping[str(system_name)] = str(service_type)
        return mapping

    def _build_instances(self) -> None:
        for system_name, catalog_entry in self.catalog.items():
            service_type = self.service_type_by_system.get(system_name)
            if not service_type:
                continue
            selector = catalog_entry.get("selector", "broadcast")
            system_code = catalog_entry.get("system_code", "")
            protocol = catalog_entry.get("protocol", "tango")
            if selector == "by_beam_line":
                instances = [
                    self._make_instance(system_name, service_type, system_code, protocol, selector, f"bl{i:02d}")
                    for i in range(1, int(catalog_entry.get("instances", 60)) + 1)
                ]
            elif selector == "by_beam_group":
                instances = [
                    self._make_instance(system_name, service_type, system_code, protocol, selector, f"bg{i:02d}")
                    for i in range(1, int(catalog_entry.get("instances", 10)) + 1)
                ]
            else:
                service_ids = catalog_entry.get("service_ids") or [f"gxlf.{system_code}.svc01"]
                instances = [
                    self._make_instance(
                        system_name,
                        service_type,
                        system_code,
                        protocol,
                        selector,
                        service_id.split(".")[-1],
                        service_id=service_id,
                    )
                    for service_id in service_ids
                ]
            self.instances_by_system[system_name] = instances
            for alias, canonical in SYSTEM_ALIASES.items():
                if canonical == system_name:
                    self.instances_by_system[alias] = instances
            for instance in instances:
                self.instances_by_id[instance.service_id] = instance

    def _make_instance(
        self,
        system_name: str,
        service_type: str,
        system_code: str,
        protocol: str,
        selector: str,
        instance_code: str,
        service_id: str | None = None,
    ) -> ServiceInstance:
        service_id = service_id or f"gxlf.{system_code}.{instance_code}"
        initial_business_state = self.templates[service_type].get("initial_business_state", "idle")
        if service_type == "personnel_counter":
            initial_business_state = "zero"
        return ServiceInstance(
            system_name=system_name,
            service_type=service_type,
            system_code=system_code,
            service_id=service_id,
            tango_fqdn=f"gxlf/{system_code}/{instance_code}",
            instance_code=instance_code,
            protocol=protocol,
            selector=selector,
            health_state="running",
            business_state=initial_business_state,
        )

    def resolve_targets(
        self,
        system_name: str,
        fan_out: str,
        selected_beam_lines: list[int] | None = None,
        selected_beam_groups: list[int] | None = None,
    ) -> list[ServiceTarget]:
        instances = self.instances_by_system.get(system_name)
        if not instances:
            raise ModelConsistencyError(f"no simulated service instances for system {system_name!r}")
        if fan_out == "by_beam_line":
            wanted = selected_beam_lines or list(range(1, 61))
            by_code = {inst.instance_code: inst for inst in instances}
            return [
                self._target(by_code[f"bl{i:02d}"], beam_line_no=i)
                for i in wanted
                if f"bl{i:02d}" in by_code
            ]
        if fan_out == "by_beam_group":
            wanted = selected_beam_groups or list(range(1, 11))
            by_code = {inst.instance_code: inst for inst in instances}
            return [
                self._target(by_code[f"bg{i:02d}"], beam_group_no=i)
                for i in wanted
                if f"bg{i:02d}" in by_code
            ]
        return [self._target(inst) for inst in instances]

    def _target(
        self,
        instance: ServiceInstance,
        beam_line_no: int | None = None,
        beam_group_no: int | None = None,
    ) -> ServiceTarget:
        return ServiceTarget(
            system_name=instance.system_name,
            service_id=instance.service_id,
            instance_code=instance.instance_code,
            beam_line_no=beam_line_no,
            beam_group_no=beam_group_no,
        )


class TangoSimAdapter:
    def __init__(
        self,
        registry: SimServiceRegistry,
        state_machine_model: dict[str, Any],
        validator: ContractValidator,
    ):
        self.registry = registry
        self.state_machine_model = state_machine_model
        self.validator = validator
        self.templates = state_machine_model.get("service_type_templates") or {}

    def command_inout(self, command_name: str, dev_string_json: str) -> tuple[str, list[dict[str, Any]]]:
        payload = json.loads(dev_string_json)
        effective_command_name = COMMAND_ALIASES.get(command_name, command_name)
        payload["command"] = effective_command_name
        try:
            self.validator.validate_command_request(payload)
        except ContractError as exc:
            return json.dumps(self._accept_rejected(payload, effective_command_name, "INVALID_ARGUMENT", str(exc)), ensure_ascii=False), []

        service_id = payload["target_service_id"]
        instance = self.registry.instances_by_id.get(service_id)
        if not instance:
            return json.dumps(
                self._accept_rejected(payload, effective_command_name, "INVALID_SERVICE_NAME", f"unknown service_id {service_id}"),
                ensure_ascii=False,
            ), []

        accept, callbacks = self._execute(instance, effective_command_name, payload)
        self.validator.validate_accept_response(accept)
        for callback in callbacks:
            self.validator.validate_task_callback(callback, instance.service_type)
        return json.dumps(accept, ensure_ascii=False), callbacks

    def _execute(
        self,
        instance: ServiceInstance,
        command_name: str,
        payload: dict[str, Any],
    ) -> tuple[dict[str, Any], list[dict[str, Any]]]:
        if instance.health_state != "running":
            return self._accept_rejected(payload, command_name, "SERVICE_NOT_RUNNING", "service is not running"), []

        template = self.templates[instance.service_type]
        command_def = (template.get("commands") or {}).get(command_name)
        if not command_def:
            return self._accept_rejected(
                payload,
                command_name,
                "COMMAND_NOT_SUPPORTED",
                f"{instance.service_type}.{command_name} is not defined",
            ), []

        transition = command_def.get("transition", {})
        allowed_from = transition.get("from", [])
        if (
            allowed_from != "any"
            and "any" not in allowed_from
            and instance.business_state not in allowed_from
            and not self._is_compatible_transition(instance, command_name)
        ):
            return self._accept_rejected(
                payload,
                command_name,
                "INVALID_STATE",
                f"{instance.business_state} cannot accept {command_name}",
            ), []

        instance.task_seq += 1
        task_id = f"{payload['shot_id']}.{payload['node_id']}.{instance.instance_code}.{instance.task_seq}"
        instance.current_task_id = task_id
        instance.health_state = "busy"
        next_business_state = transition.get("to", instance.business_state)
        instance.business_state = next_business_state

        accept = {
            "accepted": True,
            "accept_code": 1,
            "accept_status": "received",
            "task_id": task_id,
            "service_id": instance.service_id,
            "instance_code": instance.instance_code,
            "command": command_name,
            "message": "command received",
        }

        task_mode = command_def.get("task_mode", payload.get("call_mode", "sync"))
        callbacks: list[dict[str, Any]] = []
        callbacks.append(self._callback(payload, instance, task_id, command_name, "accepted", "success", 1, 0))
        if task_mode == "async":
            callbacks.append(self._callback(payload, instance, task_id, command_name, "executing", "success", 1, 50))

        completion_transition = command_def.get("completion_transition")
        if completion_transition:
            instance.business_state = completion_transition.get("to", instance.business_state)
        instance.health_state = "running"
        callbacks.append(self._callback(payload, instance, task_id, command_name, "succeeded", "success", 0, 100))
        instance.current_task_id = None
        instance.history.append(
            {
                "task_id": task_id,
                "command": command_name,
                "node_id": payload["node_id"],
                "node_name": payload["node_name"],
                "final_business_state": instance.business_state,
                "finished_at": utc_now(),
            }
        )
        return accept, callbacks

    def _is_compatible_transition(self, instance: ServiceInstance, command_name: str) -> bool:
        if instance.service_type == "sync":
            recipe_states = {
                "parameterized",
                "preamp_reprate_loaded",
                "meas_reprate_loaded",
                "preamp_single_loaded",
                "meas_single_loaded",
            }
            recipe_commands = {
                "SyncPreampRepRateRecipe",
                "SyncMeasRepRateRecipe",
                "PreampSingleShotRecipe",
                "MeasSingleShotRecipe",
            }
            if command_name in recipe_commands and instance.business_state in recipe_states:
                return True
            if command_name == "SyncTrigger" and instance.business_state == "triggered":
                return True
        if instance.service_type == "measurement_sample":
            return command_name == "MeasShotMotionReady" and instance.business_state == "motion_ready"
        if instance.service_type == "target_alignment":
            return command_name == "TargetDataAnalysis" and instance.business_state == "retracted"
        if instance.service_type == "environment_control":
            return command_name in {"WarningMusicOn", "WarningLightAndMusic"} and instance.business_state in {
                "warning_active",
                "music_active",
            }
        return False

    def _accept_rejected(
        self,
        payload: dict[str, Any],
        command_name: str,
        code: str,
        message: str,
    ) -> dict[str, Any]:
        return {
            "accepted": False,
            "accept_code": 0 if code != "NO_SERVICE_PERMISSION" else 2,
            "accept_status": "failed" if code != "NO_SERVICE_PERMISSION" else "no_permission",
            "task_id": payload.get("flow_task_id", ""),
            "service_id": payload.get("target_service_id", ""),
            "instance_code": payload.get("target_instance_code", ""),
            "command": command_name,
            "message": message,
            "error": make_error(code, message, "system_service", command=command_name),
        }

    def _callback(
        self,
        payload: dict[str, Any],
        instance: ServiceInstance,
        task_id: str,
        command_name: str,
        task_state: str,
        result_status: str,
        result_code: int,
        progress: float,
    ) -> dict[str, Any]:
        callback = TaskCallback(
            flow_instance_id=payload["flow_instance_id"],
            shot_id=payload["shot_id"],
            stage_id=payload["stage_id"],
            flow_task_id=payload["flow_task_id"],
            node_id=payload["node_id"],
            node_name=payload["node_name"],
            task_id=task_id,
            command=command_name,
            system_name=instance.system_name,
            service_id=instance.service_id,
            instance_code=instance.instance_code,
            beam_line_no=payload.get("beam_line_no"),
            beam_group_no=payload.get("beam_group_no"),
            service_health_state=instance.health_state,
            business_state=instance.business_state,
            task_state=task_state,
            progress=progress,
            result_code=result_code,
            result_status=result_status,
            updated_at=utc_now(),
        )
        return callback.to_payload()
