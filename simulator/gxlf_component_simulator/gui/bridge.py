"""Taurus device/attribute ownership in one worker thread, with queued Qt updates."""
import json
import time

import taurus
from taurus.external.qt import Qt

from .model import validate_snapshot
from ..component_model import ComponentModel


class Listener:
    def __init__(self, worker, generation):
        self.worker = worker
        self.generation = generation
        self.active = True

    def eventReceived(self, source, event_type, value):
        # Taurus callbacks may arrive on Tango threads. Do not touch widgets here.
        if self.active:
            try:
                self.worker.notice.emit(self.generation)
            except RuntimeError:
                pass  # A callback already in flight can overlap Qt object destruction.


class DeviceWorker(Qt.QObject):
    state = Qt.pyqtSignal(int, object)
    history = Qt.pyqtSignal(int, object)
    connection = Qt.pyqtSignal(int, bool, str)
    completed = Qt.pyqtSignal(int, bool, str)
    notice = Qt.pyqtSignal(int)

    def __init__(self, profile: ComponentModel):
        super().__init__()
        self.profile = profile
        self.generation = 0
        self.device = self.attribute = self.listener = self.timer = None
        self.dirty = True
        self.last_read = 0.0
        self.last_history = None
        self.notice.connect(self.mark_dirty, Qt.Qt.ConnectionType.QueuedConnection)

    @Qt.pyqtSlot(int)
    def mark_dirty(self, generation):
        if generation == self.generation:
            self.dirty = True

    def detach(self):
        if self.attribute is not None and self.listener is not None:
            self.listener.active = False
            self.attribute.removeListener(self.listener)
        self.device = self.attribute = self.listener = None

    @Qt.pyqtSlot(str, int, object)
    def connect_device(self, address, generation, profile):
        self.detach()
        self.profile = profile
        self.generation = generation
        self.last_history = None
        self.connection.emit(generation, False, "正在连接 Tango Device…")
        try:
            self.device = taurus.Device(address)
            self.device.getDeviceProxy().set_timeout_millis(2500)
            self.attribute = self.device.getAttribute("Snapshot")
            self.listener = Listener(self, generation)
            self.attribute.addListener(self.listener)
        except Exception as ex:
            self.connection.emit(generation, False, "连接失败：" + str(ex))
        if self.timer is None:
            self.timer = Qt.QTimer(self)
            self.timer.setInterval(350)
            self.timer.timeout.connect(self.pump)
            self.timer.start()
        self.dirty = True
        self.refresh()

    @Qt.pyqtSlot()
    def pump(self):
        if self.dirty or time.monotonic() - self.last_read >= 2:
            self.refresh()

    @Qt.pyqtSlot()
    def refresh(self):
        self.last_read = time.monotonic()
        self.dirty = False
        if self.attribute is None or self.device is None:
            return
        try:
            value = self.attribute.read(cache=False)
            snapshot = validate_snapshot(json.loads(str(value.rvalue)), self.profile)
            history = json.loads(self.device.getDeviceProxy().command_inout("History", 100))
            if not isinstance(history, list):
                raise ValueError("设备返回的历史格式不正确")
            self.connection.emit(self.generation, True, "已连接 · Taurus 事件订阅 + 定时核对")
            self.state.emit(self.generation, snapshot)
            if history != self.last_history:
                self.last_history = history
                self.history.emit(self.generation, history)
        except Exception as ex:
            self.connection.emit(self.generation, False, "连接或读取失败：" + str(ex))

    @Qt.pyqtSlot(int, str, object)
    def invoke(self, generation, command, payload):
        if generation != self.generation:
            return
        try:
            if self.device is None:
                raise ValueError("尚未连接设备")
            # Recheck the model before every mutation; the device rechecks transition guards.
            validate_snapshot(json.loads(str(self.attribute.read(cache=False).rvalue)), self.profile)
            self.device.getDeviceProxy().command_inout(command, json.dumps(payload, ensure_ascii=True))
            self.refresh()
            self.completed.emit(generation, True, "操作已提交，状态以设备反馈为准")
        except Exception as ex:
            self.refresh()
            self.completed.emit(generation, False, "操作失败：" + str(ex))

    @Qt.pyqtSlot()
    def stop(self):
        if self.timer is not None:
            self.timer.stop()
        self.detach()
