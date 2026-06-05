from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Protocol

from .models import LifecycleEvent, ServiceInstance


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class LifecycleEventSink(Protocol):
    def publish(self, event: LifecycleEvent) -> None:
        ...

    def close(self) -> None:
        ...


@dataclass
class MemoryLifecycleSink:
    events: list[LifecycleEvent] = field(default_factory=list)

    def publish(self, event: LifecycleEvent) -> None:
        self.events.append(event)

    def close(self) -> None:
        return


class JsonlLifecycleSink:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._fh = self.path.open("w", encoding="utf-8")

    def publish(self, event: LifecycleEvent) -> None:
        self._fh.write(json.dumps(event.to_payload(), ensure_ascii=False) + "\n")

    def close(self) -> None:
        self._fh.close()


class TangoLifecycleEventSink:
    """Bridge point for publishing lifecycle events as Tango user events.

    The simulation package intentionally does not import PyTango here. A real
    deployment can implement this sink around a Tango device attribute/pipe and
    pass it into LifecycleLogger.
    """

    def publish(self, event: LifecycleEvent) -> None:
        raise NotImplementedError("configure a PyTango-backed lifecycle event sink")

    def close(self) -> None:
        return


class LifecycleLogger:
    def __init__(
        self,
        path: str | Path | None = None,
        sinks: list[LifecycleEventSink] | None = None,
    ):
        self.path = Path(path) if path else None
        self.memory_sink = MemoryLifecycleSink()
        self.sinks: list[LifecycleEventSink] = [self.memory_sink]
        self._seq = 0
        if self.path:
            self.sinks.append(JsonlLifecycleSink(self.path))
        self.sinks.extend(sinks or [])

    @property
    def events(self) -> list[LifecycleEvent]:
        return self.memory_sink.events

    def close(self) -> None:
        for sink in self.sinks:
            sink.close()

    def emit(
        self,
        event_type: str,
        instance: ServiceInstance,
        payload: dict[str, Any] | None = None,
        task_id: str = "",
        health_before: str | None = None,
        health_after: str | None = None,
        business_before: str | None = None,
        business_after: str | None = None,
        task_before: str | None = None,
        task_after: str | None = None,
        result_status: str | None = None,
        message: str = "",
    ) -> LifecycleEvent:
        payload = payload or {}
        self._seq += 1
        event = LifecycleEvent(
            seq=self._seq,
            timestamp=utc_now(),
            event_type=event_type,
            flow_instance_id=str(payload.get("flow_instance_id", "")),
            shot_id=str(payload.get("shot_id", "")),
            stage_id=str(payload.get("stage_id", "")),
            node_id=str(payload.get("node_id", "")),
            node_name=str(payload.get("node_name", "")),
            command=str(payload.get("command", "")),
            task_id=task_id,
            system_name=instance.system_name,
            service_type=instance.service_type,
            service_id=instance.service_id,
            tango_fqdn=instance.tango_fqdn,
            instance_code=instance.instance_code,
            beam_line_no=payload.get("beam_line_no"),
            beam_group_no=payload.get("beam_group_no"),
            health_state_before=health_before,
            health_state_after=health_after,
            business_state_before=business_before,
            business_state_after=business_after,
            task_state_before=task_before,
            task_state_after=task_after,
            result_status=result_status,
            sim_expected_duration_ms=payload.get("sim_expected_duration_ms"),
            sim_delay_seconds=payload.get("sim_delay_seconds"),
            sim_node_delay_seconds=payload.get("sim_node_delay_seconds"),
            sim_business_countdown_seconds=payload.get("sim_business_countdown_seconds"),
            message=message,
        )
        for sink in self.sinks:
            sink.publish(event)
        return event
