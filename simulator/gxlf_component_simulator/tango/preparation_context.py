"""Explicit demo stand-in for synchronization, safety and environment evidence.

All conditions start false. The operator/test controls them through Tango.
This is not a physical safety service or a component SCXML model.
"""
import copy
import json
from threading import RLock, Timer

from tango.server import Device, attribute, command, device_property

from ..component_runtime import identifier


class PreparationContextDevice(Device):
    auto_complete = device_property(dtype=bool, default_value=False)
    auto_complete_delay_ms = device_property(dtype=int, default_value=500)

    def init_device(self):
        super().init_device()
        old_lock = getattr(self, "lock", None)
        if old_lock is not None:
            with old_lock:
                self._deleted = True
                for timer in getattr(self, "_auto_timers", {}).values():
                    timer.cancel()
                getattr(self, "_auto_timers", {}).clear()
        self.lock = RLock()
        self._auto_timers = {}
        self._deleted = False
        self.conditions = dict(
            safety_permit=False,
            preamp_repeat_ready=False,
            collimation_environment_ready=False,
            diagnosis_acquisition_ready=False,
            crystal_pose_ready=False,
            charge_preparation_ready=False,
            preamp_single_recipe_ready=False,
            measurement_single_recipe_ready=False,
            target_measurement_ready=False,
            switch_charge_ready=False,
            charge_complete=False,
            warning_music_stopped=False,
            trigger_countdown_started=False,
            pump_trigger_ready=False,
            switch_trigger_ready=False,
            synchronized_triggered=False,
            diagnostic_postprocess_ready=False,
            pump_data_collected=False,
            pump_standby_ready=False,
            preionization_prepared=False,
            preionization_music_started=False,
            preionization_charge_complete=False,
            preionization_music_stopped=False,
            preionization_countdown_started=False,
            preionization_pump_trigger_ready=False,
            preionization_sync_triggered=False,
            preionization_data_collected=False,
            preionization_standby_ready=False,
            warning_light_off=False,
            laser_parameter_collected=False,
            target_analysis_ready=False,
            target_retracted=False,
            switch_waveform_collected=False,
            switch_standby_ready=False,
            slab_purge_complete=False,
            shielding_door_open=False,
        )
        self.results = {}
        self.active = None
        self.last = None
        self.set_change_event("Snapshot", True, False)

    def delete_device(self):
        with getattr(self, "lock", RLock()):
            self._deleted = True
            for timer in getattr(self, "_auto_timers", {}).values():
                timer.cancel()
            getattr(self, "_auto_timers", {}).clear()

    def _finish_automatically(self, command_id):
        try:
            with self.lock:
                if self._deleted:
                    return
                result = self.results.get(command_id)
                if not result or result["status"] != "accepted":
                    return
            self.finish(json.dumps({"command_id": command_id, "outcome": "success"}))
        except Exception as ex:
            try:
                self.error_stream("automatic completion failed for %s: %s" % (command_id, ex))
            except Exception:
                pass
        finally:
            with self.lock:
                self._auto_timers.pop(command_id, None)

    def _schedule_auto_completion(self, result):
        if not getattr(self, "auto_complete", False) or result.get("status") != "accepted":
            return
        command_id = result["command_id"]
        with self.lock:
            if self._deleted or command_id in self._auto_timers:
                return
            delay = max(0, int(getattr(self, "auto_complete_delay_ms", 500))) / 1000
            timer = Timer(delay, self._finish_automatically, args=(command_id,))
            timer.daemon = True
            self._auto_timers[command_id] = timer
            timer.start()

    def snapshot(self):
        return {"model": "preparation-context-demo-v1", "component": "演示同步及环境条件",
                "conditions": dict(self.conditions), "active_command_id": self.active,
                "active_action": "preamp_repeat" if self.active else None,
                "last_result": copy.deepcopy(self.results.get(self.last)) if self.last else None}

    def publish(self, value):
        self.push_change_event("Snapshot", json.dumps(self.snapshot(), ensure_ascii=True))
        return json.dumps(value, ensure_ascii=True)

    @attribute(dtype=str)
    def Snapshot(self):
        with self.lock:
            return json.dumps(self.snapshot(), ensure_ascii=True)

    @command(dtype_in=str, dtype_out=str)
    def SetConditions(self, payload):
        values = json.loads(payload)
        with self.lock:
            if not isinstance(values, dict) or not values or any(
                    key not in self.conditions or not isinstance(value, bool)
                    for key, value in values.items()):
                raise ValueError("expected named boolean conditions")
            self.conditions.update(values)
            return self.publish(self.snapshot())

    @command(dtype_in=str, dtype_out=str)
    def Execute(self, payload):
        request = json.loads(payload)
        key = identifier(request.get("command_id"))
        with self.lock:
            if key in self.results:
                if request != self.results[key]["request"]:
                    raise ValueError("command ID reused")
                return json.dumps(self.results[key], ensure_ascii=True)
            if request.get("action") != "preamp_repeat" or self.active:
                raise ValueError("unsupported action or command already active")
            self.conditions["preamp_repeat_ready"] = False
            self.active = key
            self.results[key] = {"command_id": key, "action": "preamp_repeat", "status": "accepted", "request": request}
            result = self.results[key]
            response = self.publish(result)
            self._schedule_auto_completion(result)
            return response

    def finish(self, payload):
        request = json.loads(payload)
        key = identifier(request.get("command_id"))
        outcome = request.get("outcome")
        if outcome not in ("success", "failure"):
            raise ValueError("outcome must be success or failure")
        with self.lock:
            result = self.results[key]
            if result["status"] != "accepted":
                if result["status"] != ("succeeded" if outcome == "success" else "failed"):
                    raise ValueError("conflicting completion")
                return json.dumps(result, ensure_ascii=True)
            self.conditions["preamp_repeat_ready"] = outcome == "success"
            self.active = None
            self.last = key
            result["status"] = "succeeded" if outcome == "success" else "failed"
            result["snapshot"] = {"conditions": dict(self.conditions), "active_command_id": None}
            return self.publish(result)

    @command(dtype_in=str, dtype_out=str)
    def Simulate(self, payload):
        return self.finish(payload)

    @command(dtype_in=str, dtype_out=str)
    def Complete(self, payload):
        return self.finish(payload)

    @command(dtype_in=str, dtype_out=str)
    def GetResult(self, key):
        with self.lock:
            return json.dumps(self.results[key], ensure_ascii=True)
