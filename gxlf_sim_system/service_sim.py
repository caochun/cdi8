from __future__ import annotations

import json
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from .contracts import ContractError, ContractValidator, make_error
from .lifecycle import LifecycleLogger
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


@dataclass(frozen=True)
class SimTimingConfig:
    time_scale: float = 0.0
    min_delay_seconds: float = 0.0
    max_delay_seconds: float = 5.0


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
        lifecycle_logger: LifecycleLogger | None = None,
        timing: SimTimingConfig | None = None,
    ):
        self.registry = registry
        self.state_machine_model = state_machine_model
        self.validator = validator
        self.templates = state_machine_model.get("service_type_templates") or {}
        self.lifecycle_logger = lifecycle_logger
        self.timing = timing or SimTimingConfig()

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
            self._log_rejected(instance, payload, command_name, "service is not running")
            return self._accept_rejected(payload, command_name, "SERVICE_NOT_RUNNING", "service is not running"), []

        template = self.templates[instance.service_type]
        command_def = (template.get("commands") or {}).get(command_name)
        if not command_def:
            self._log_rejected(instance, payload, command_name, f"{instance.service_type}.{command_name} is not defined")
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
            self._log_rejected(instance, payload, command_name, f"{instance.business_state} cannot accept {command_name}")
            return self._accept_rejected(
                payload,
                command_name,
                "INVALID_STATE",
                f"{instance.business_state} cannot accept {command_name}",
            ), []

        payload["sim_node_delay_seconds"] = self._sim_node_delay_seconds(payload, command_def)
        payload["sim_delay_seconds"] = self._sim_delay_seconds(payload, command_def)
        payload["sim_business_countdown_seconds"] = self._sim_business_countdown_seconds(payload, command_name)
        instance.task_seq += 1
        task_id = f"{payload['shot_id']}.{payload['node_id']}.{instance.instance_code}.{instance.task_seq}"
        instance.current_task_id = task_id
        health_before = instance.health_state
        business_before = instance.business_state
        self._log(
            "command_received",
            instance,
            payload,
            task_id,
            health_before=health_before,
            health_after=instance.health_state,
            business_before=business_before,
            business_after=instance.business_state,
            task_before=None,
            task_after="created",
        )
        instance.health_state = "busy"
        next_business_state = transition.get("to", instance.business_state)
        instance.business_state = next_business_state
        self._log(
            "state_transition",
            instance,
            payload,
            task_id,
            health_before=health_before,
            health_after=instance.health_state,
            business_before=business_before,
            business_after=instance.business_state,
            task_before="created",
            task_after="accepted",
        )

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
        self._log("task_state_changed", instance, payload, task_id, task_before="created", task_after="accepted", result_status="success")
        if task_mode == "async":
            callbacks.append(self._callback(payload, instance, task_id, command_name, "executing", "success", 1, 50))
            self._log("task_state_changed", instance, payload, task_id, task_before="accepted", task_after="executing", result_status="success")
            self._sleep_for_command(payload, command_def)

        completion_business_before = instance.business_state
        completion_transition = command_def.get("completion_transition")
        if completion_transition:
            instance.business_state = completion_transition.get("to", instance.business_state)
            self._log(
                "state_transition",
                instance,
                payload,
                task_id,
                health_before=instance.health_state,
                health_after=instance.health_state,
                business_before=completion_business_before,
                business_after=instance.business_state,
                task_before="executing" if task_mode == "async" else "accepted",
                task_after="executing" if task_mode == "async" else "accepted",
                message="completion_transition",
            )
        final_health_before = instance.health_state
        instance.health_state = "running"
        callbacks.append(self._callback(payload, instance, task_id, command_name, "succeeded", "success", 0, 100))
        self._log(
            "task_state_changed",
            instance,
            payload,
            task_id,
            health_before=final_health_before,
            health_after=instance.health_state,
            business_before=instance.business_state,
            business_after=instance.business_state,
            task_before="executing" if task_mode == "async" else "accepted",
            task_after="succeeded",
            result_status="success",
        )
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

    def _sleep_for_command(self, payload: dict[str, Any], command_def: dict[str, Any]) -> None:
        delay = float(payload.get("sim_delay_seconds") or 0)
        if delay > 0:
            time.sleep(delay)

    def _sim_delay_seconds(self, payload: dict[str, Any], command_def: dict[str, Any]) -> float:
        raw_duration = self._command_sim_duration(command_def)
        if raw_duration <= 0:
            raw_duration = float(payload.get("sim_expected_duration_ms") or 0) / 1000
        if raw_duration <= 0 or self.timing.time_scale <= 0:
            return 0.0
        fanout_count = self._sim_fanout_count(payload)
        delay = self._sim_node_delay_seconds(payload, command_def) / fanout_count
        delay = max(delay, self.timing.min_delay_seconds)
        return min(delay, self.timing.max_delay_seconds)

    def _sim_node_delay_seconds(self, payload: dict[str, Any], command_def: dict[str, Any]) -> float:
        raw_duration = self._command_sim_duration(command_def)
        if raw_duration <= 0:
            raw_duration = float(payload.get("sim_expected_duration_ms") or 0) / 1000
        if raw_duration <= 0 or self.timing.time_scale <= 0:
            return 0.0
        delay = raw_duration * self.timing.time_scale
        return min(delay, self.timing.max_delay_seconds)

    def _sim_fanout_count(self, payload: dict[str, Any]) -> int:
        params = payload.get("params") if isinstance(payload.get("params"), dict) else {}
        try:
            return max(1, int(params.get("_sim_fanout_count") or 1))
        except (TypeError, ValueError):
            return 1

    def _sim_business_countdown_seconds(self, payload: dict[str, Any], command_name: str) -> float | None:
        if command_name != "SyncTrigger":
            return None
        params = payload.get("params") if isinstance(payload.get("params"), dict) else {}
        try:
            return float(params.get("_sim_business_countdown_seconds") or 5.0)
        except (TypeError, ValueError):
            return 5.0

    def _command_sim_duration(self, command_def: dict[str, Any]) -> float:
        sim_timing = command_def.get("sim_timing")
        if isinstance(sim_timing, dict):
            for key in ("compressed_duration", "duration", "expected_duration"):
                value = sim_timing.get(key)
                if value is not None:
                    from .models import parse_duration

                    return parse_duration(value)
        timeout = command_def.get("timeout")
        if timeout:
            from .models import parse_duration

            return parse_duration(timeout)
        return 0.0

    def _log(
        self,
        event_type: str,
        instance: ServiceInstance,
        payload: dict[str, Any],
        task_id: str = "",
        health_before: str | None = None,
        health_after: str | None = None,
        business_before: str | None = None,
        business_after: str | None = None,
        task_before: str | None = None,
        task_after: str | None = None,
        result_status: str | None = None,
        message: str = "",
    ) -> None:
        if not self.lifecycle_logger:
            return
        self.lifecycle_logger.emit(
            event_type,
            instance,
            payload,
            task_id=task_id,
            health_before=health_before,
            health_after=health_after,
            business_before=business_before,
            business_after=business_after,
            task_before=task_before,
            task_after=task_after,
            result_status=result_status,
            message=message,
        )

    def _log_rejected(self, instance: ServiceInstance, payload: dict[str, Any], command_name: str, message: str) -> None:
        payload = dict(payload)
        payload["command"] = command_name
        self._log(
            "command_rejected",
            instance,
            payload,
            task_id=payload.get("flow_task_id", ""),
            health_before=instance.health_state,
            health_after=instance.health_state,
            business_before=instance.business_state,
            business_after=instance.business_state,
            task_before="created",
            task_after="rejected",
            result_status="rejected",
            message=message,
        )

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
