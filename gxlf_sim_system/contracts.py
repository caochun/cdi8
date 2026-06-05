from __future__ import annotations

from typing import Any


class ContractError(ValueError):
    pass


class ContractValidator:
    def __init__(self, interface_model: dict[str, Any], state_machine_model: dict[str, Any]):
        self.interface_model = interface_model
        self.state_machine_model = state_machine_model
        self.request_fields = interface_model.get("standard_command_request", {}).get("ordered_fields", [])
        self.accept_fields = interface_model.get("standard_command_accept_response", {}).get("fields", [])
        self.callback_fields = interface_model.get("status_callback_interface", {}).get("required_fields", [])
        self.service_templates = state_machine_model.get("service_type_templates") or {}

    def validate_command_request(self, payload: dict[str, Any]) -> None:
        self._validate_required_fields(payload, self.request_fields, "command_request")

    def validate_accept_response(self, payload: dict[str, Any]) -> None:
        self._validate_required_fields(payload, self.accept_fields, "accept_response")

    def validate_task_callback(self, payload: dict[str, Any], service_type: str | None = None) -> None:
        self._validate_required_fields(payload, self.callback_fields, "task_callback")
        if service_type:
            business_state = payload.get("business_state")
            states = set(self.service_templates.get(service_type, {}).get("business_states") or [])
            if states and business_state not in states:
                raise ContractError(
                    f"task_callback.business_state={business_state!r} is not valid for {service_type}"
                )

    def _validate_required_fields(self, payload: dict[str, Any], fields: list[dict[str, Any]], label: str) -> None:
        missing = [
            field["name"]
            for field in fields
            if field.get("required") and field.get("name") not in payload
        ]
        if missing:
            raise ContractError(f"{label} missing required fields: {', '.join(missing)}")


def make_error(code: str, message: str, source: str, **detail: Any) -> dict[str, Any]:
    return {
        "code": code,
        "message": message,
        "severity": detail.pop("severity", "warning"),
        "retriable": detail.pop("retriable", True),
        "source": source,
        "detail": detail,
    }

