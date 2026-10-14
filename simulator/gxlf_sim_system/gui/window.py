"""Taurus-backed native operator window; all mutations go through Tango commands."""
import json
import uuid

from taurus.external.qt import Qt

from ..component_model import ComponentModel
from .bridge import DeviceWorker
from .diagram import DiagramView
from .model import available_actions, available_confirmations


class ComponentWindow(Qt.QMainWindow):
    connect_requested = Qt.pyqtSignal(str, int)
    command_requested = Qt.pyqtSignal(int, str, object)
    refresh_requested = Qt.pyqtSignal()

    def __init__(self, profile: ComponentModel, address="", simulation_controls=False):
        super().__init__()
        self.profile = profile
        self.setWindowTitle(profile.display_name + "仿真操作台 · Taurus / Tango")
        self.resize(1500, 1000)
        self.setMinimumSize(1000, 720)
        self.snapshot = None
        self.history_rows = []
        self.generation = 0
        self.connected = False
        self.busy = False
        self.simulation_controls = simulation_controls
        self._closing = False
        self.setStyleSheet("""
            QMainWindow { background: #f1f5f7; }
            QLabel { color: #243d4a; }
            QGroupBox { background: white; border: 1px solid #cfdae0; border-radius: 8px;
                        margin-top: 14px; padding: 14px 10px 10px; font-weight: bold; }
            QGroupBox::title { subcontrol-origin: margin; left: 12px; }
            QPushButton { padding: 7px 12px; border: 1px solid #b4c8d1; border-radius: 5px; background: white; }
            QPushButton:hover { background: #e7f2f3; }
            QPushButton:disabled { color: #a0aeb5; background: #f1f4f5; }
            QPushButton[primary="true"] { background: #246d78; color: white; border-color: #246d78; }
            QPushButton[primary="true"]:disabled { background: #bdd0d4; border-color: #bdd0d4; }
            QLineEdit, QComboBox, QPlainTextEdit { background: white; border: 1px solid #bbcdd6; padding: 5px; border-radius: 4px; }
            QTableWidget { background: white; border: 1px solid #cfdae0; gridline-color: #e8eff2; }
            QHeaderView::section { background: #e9f0f3; padding: 6px; border: none; }
        """)
        content = Qt.QWidget()
        self.setCentralWidget(content)
        outer = Qt.QVBoxLayout(content)
        outer.setContentsMargins(18, 14, 18, 14)
        header = Qt.QHBoxLayout()
        title = Qt.QLabel(profile.display_name)
        title.setStyleSheet("font-size: 23px; font-weight: bold;")
        header.addWidget(title)
        header.addStretch()
        mode = Qt.QLabel("仿真操作模式" if simulation_controls else "设备连接模式 · 仿真注入已关闭")
        mode.setStyleSheet("color: #647e8b;")
        header.addWidget(mode)
        outer.addLayout(header)
        connection_bar = Qt.QHBoxLayout()
        connection_bar.addWidget(Qt.QLabel("Tango Device"))
        self.address = Qt.QLineEdit(address)
        self.address.setPlaceholderText("tango://主机:端口/域/族/成员，或无数据库 Device URL")
        self.address.setObjectName("deviceAddress")
        connection_bar.addWidget(self.address, 1)
        self.connect_button = Qt.QPushButton("连接")
        self.connect_button.setObjectName("connectButton")
        self.connect_button.clicked.connect(self.connect_device)
        connection_bar.addWidget(self.connect_button)
        self.refresh_button = Qt.QPushButton("刷新")
        self.refresh_button.clicked.connect(self.refresh_requested.emit)
        connection_bar.addWidget(self.refresh_button)
        outer.addLayout(connection_bar)
        self.connection_label = Qt.QLabel("尚未连接")
        self.connection_label.setWordWrap(True)
        self.connection_label.setMaximumHeight(55)
        outer.addWidget(self.connection_label)
        self.state_label = Qt.QLabel("外层主状态：—    内层子状态：—")
        self.state_label.setStyleSheet("background: #e6eff2; padding: 12px; border-radius: 6px; font-size: 18px; font-weight: bold;")
        outer.addWidget(self.state_label)
        splitter = Qt.QSplitter(Qt.Qt.Orientation.Horizontal)
        graph_panel = Qt.QWidget()
        graph_layout = Qt.QVBoxLayout(graph_panel)
        graph_layout.setContentsMargins(0, 0, 0, 0)
        toolbar = Qt.QHBoxLayout()
        toolbar.addWidget(Qt.QLabel("嵌套状态图 · 滚轮缩放 / 拖动平移 / 点击查看状态"))
        toolbar.addStretch()
        self.labels_toggle = Qt.QCheckBox("显示全部事件名称")
        toolbar.addWidget(self.labels_toggle)
        fit = Qt.QPushButton("适应窗口")
        toolbar.addWidget(fit)
        graph_layout.addLayout(toolbar)
        self.diagram = DiagramView(profile)
        fit.clicked.connect(self.diagram.fit)
        self.labels_toggle.toggled.connect(self.toggle_labels)
        graph_layout.addWidget(self.diagram, 1)
        self.node_label = Qt.QLabel("高亮框表示当前外层主状态，实色节点表示当前内层子状态；橙色连线表示最近一次转换。")
        self.node_label.setWordWrap(True)
        self.node_label.setMinimumHeight(40)
        self.diagram.selected.connect(self.show_node)
        graph_layout.addWidget(self.node_label)
        splitter.addWidget(graph_panel)

        side_scroll = Qt.QScrollArea()
        side_scroll.setWidgetResizable(True)
        side_scroll.setMinimumWidth(290)
        side = Qt.QWidget()
        side_layout = Qt.QVBoxLayout(side)
        side_layout.setContentsMargins(8, 0, 4, 0)
        action_group = Qt.QGroupBox("发送动作请求")
        actions_layout = Qt.QVBoxLayout(action_group)
        actions_layout.addWidget(Qt.QLabel("仅当前状态允许的动作可以点击"))
        self.action_buttons = {}
        for key, spec in profile.actions.items():
            button = Qt.QPushButton(spec.label)
            button.setObjectName("action_" + key)
            button.setProperty("primary", True)
            button.clicked.connect(lambda checked=False, action=key: self.execute_action(action))
            actions_layout.addWidget(button)
            self.action_buttons[key] = button
        parameter_toggle = Qt.QCheckBox("附带参数（JSON 对象）")
        self.parameters = Qt.QPlainTextEdit("{}")
        self.parameters.setMaximumHeight(75)
        self.parameters.hide()
        parameter_toggle.toggled.connect(self.parameters.setVisible)
        actions_layout.addWidget(parameter_toggle)
        actions_layout.addWidget(self.parameters)
        side_layout.addWidget(action_group)

        feedback_group = Qt.QGroupBox("当前任务")
        feedback_layout = Qt.QVBoxLayout(feedback_group)
        self.task_label = Qt.QLabel("暂无任务")
        self.task_label.setWordWrap(True)
        self.task_label.setTextInteractionFlags(Qt.Qt.TextInteractionFlag.TextSelectableByMouse)
        feedback_layout.addWidget(self.task_label)
        self.success_button = Qt.QPushButton("模拟本次任务成功")
        self.failure_button = Qt.QPushButton("模拟本次任务失败")
        self.success_button.setObjectName("simulateSuccess")
        self.failure_button.setObjectName("simulateFailure")
        self.success_button.clicked.connect(lambda: self.simulate("success"))
        self.failure_button.clicked.connect(lambda: self.simulate("failure"))
        feedback_layout.addWidget(self.success_button)
        feedback_layout.addWidget(self.failure_button)
        self.last_result_label = Qt.QLabel("尚无最终结果")
        self.last_result_label.setWordWrap(True)
        feedback_layout.addWidget(self.last_result_label)
        side_layout.addWidget(feedback_group)

        event_group = Qt.QGroupBox("设备状态确认（仿真注入）")
        event_layout = Qt.QVBoxLayout(event_group)
        hint = Qt.QLabel("注入“已完成并已确认”的事实，不会执行真实关机或复位。")
        hint.setWordWrap(True)
        event_layout.addWidget(hint)
        self.event_choice = Qt.QComboBox()
        self.event_choice.setMinimumWidth(0)
        self.event_choice.setSizeAdjustPolicy(Qt.QComboBox.SizeAdjustPolicy.AdjustToMinimumContentsLengthWithIcon)
        self.event_choice.setMinimumContentsLength(12)
        event_layout.addWidget(self.event_choice)
        self.event_button = Qt.QPushButton("注入所选确认事件")
        self.event_button.setObjectName("injectEvent")
        self.event_button.clicked.connect(self.confirm_event)
        event_layout.addWidget(self.event_button)
        side_layout.addWidget(event_group)
        reminder = Qt.QLabel("采集失败仍可保持出光。\n任务失败与设备故障分别显示。")
        reminder.setStyleSheet("color: #647e8b; padding: 8px;")
        side_layout.addWidget(reminder)
        side_layout.addStretch()
        side_scroll.setWidget(side)
        splitter.addWidget(side_scroll)
        splitter.setSizes([1120, 340])
        outer.addWidget(splitter, 1)
        self.latest_label = Qt.QLabel("最近转换：—")
        self.latest_label.setWordWrap(True)
        outer.addWidget(self.latest_label)
        history_title = Qt.QLabel("设备事件历史（最近 100 条，UTC）")
        outer.addWidget(history_title)
        self.history_table = Qt.QTableWidget(0, 5)
        self.history_table.setHorizontalHeaderLabels(["序号 / 时间", "事件", "来源状态", "目标状态", "任务 / 事件编号"])
        self.history_table.setEditTriggers(Qt.QAbstractItemView.EditTrigger.NoEditTriggers)
        self.history_table.setSelectionBehavior(Qt.QAbstractItemView.SelectionBehavior.SelectRows)
        self.history_table.verticalHeader().hide()
        self.history_table.horizontalHeader().setSectionResizeMode(Qt.QHeaderView.ResizeMode.Stretch)
        self.history_table.setMinimumHeight(130)
        self.history_table.setMaximumHeight(220)
        outer.addWidget(self.history_table)
        self.message = Qt.QLabel("")
        self.message.setWordWrap(True)
        self.message.setMaximumHeight(60)
        outer.addWidget(self.message)

        self.worker_thread = Qt.QThread(self)
        self.worker = DeviceWorker(profile)
        self.worker.moveToThread(self.worker_thread)
        self.connect_requested.connect(self.worker.connect_device)
        self.command_requested.connect(self.worker.invoke)
        self.refresh_requested.connect(self.worker.refresh)
        self.worker.connection.connect(self.receive_connection)
        self.worker.state.connect(self.receive_state)
        self.worker.history.connect(self.receive_history)
        self.worker.completed.connect(self.receive_completion)
        self.worker_thread.finished.connect(self.worker.deleteLater)
        self.worker_thread.start()
        self.update_controls()
        if address:
            Qt.QTimer.singleShot(0, self.connect_device)
        Qt.QTimer.singleShot(100, self.diagram.fit)

    def toggle_labels(self, checked):
        self.diagram.show_labels = checked
        self.diagram.page().runJavaScript("window.setShowLabels(%s);" % ("true" if checked else "false"))
        self.diagram.update_edges()

    def show_node(self, state_id):
        if state_id in self.profile.states:
            outer, inner = self.profile.states[state_id]
            actions = [a.label for a in self.profile.actions.values() if state_id in a.requests]
            description = f"{outer} / {inner}"
        elif state_id in self.profile.outer_labels:
            description = self.profile.outer_labels[state_id] + " / 外层主状态"
            actions = []
        else:
            return
        self.node_label.setText(f"选中：{description}；该状态可接受：{'、'.join(actions) or '等待反馈或业务状态确认事件'}。操作按钮始终以设备当前状态为准。")

    def connect_device(self):
        address = self.address.text().strip()
        if not address:
            self.message.setText("请填写 Tango Device 地址")
            return
        self.generation += 1
        self.connected, self.busy = False, False
        self.snapshot, self.history_rows = None, []
        self.history_table.setRowCount(0)
        self.diagram.latest_event = None
        self.diagram.update_state(None, False)
        self.state_label.setText("外层主状态：—    内层子状态：—")
        self.task_label.setText("正在读取设备")
        self.last_result_label.setText("尚未读取结果")
        self.latest_label.setText("最近转换：—")
        self.message.clear()
        self.connection_label.setText("正在连接…")
        self.update_controls()
        self.connect_requested.emit(address, self.generation)

    @Qt.pyqtSlot(int, bool, str)
    def receive_connection(self, generation, connected, message):
        if generation != self.generation:
            return
        self.connected = connected
        self.connection_label.setText(message)
        self.connection_label.setToolTip(message)
        self.diagram.update_state(self.snapshot["state_id"] if self.snapshot else None, connected)
        if not connected and self.snapshot:
            self.state_label.setText("连接不可用 · 上次状态：" + self.snapshot["outer_state_label"] + " / " + self.snapshot["inner_state_label"])
        self.update_controls()

    @Qt.pyqtSlot(int, object)
    def receive_state(self, generation, snapshot):
        if generation != self.generation:
            return
        self.snapshot = snapshot
        self.state_label.setText(f"外层主状态：{snapshot['outer_state_label']}    内层子状态：{snapshot['inner_state_label']}")
        self.diagram.update_state(snapshot["state_id"], self.connected)
        command_id = snapshot.get("active_command_id")
        action = snapshot.get("active_action")
        self.task_label.setText(f"{self.profile.actions[action].label}\n任务编号：{command_id}" if command_id else "当前无执行中的任务")
        last = snapshot.get("last_result")
        self.last_result_label.setText(f"上次结果：{last.get('key_state') or last.get('status')}\n任务编号：{last.get('command_id')}" if last else "尚无最终结果")
        self.update_controls()

    @Qt.pyqtSlot(int, object)
    def receive_history(self, generation, rows):
        if generation != self.generation:
            return
        self.history_rows = rows
        self.history_table.setRowCount(len(rows))
        for row_index, event in enumerate(rows):
            values = [str(event["sequence"]) + " / " + event["occurred_at"], event["event"],
                      self.profile.states.get(event["source"], ("", event["source"]))[1], self.profile.states.get(event["target"], ("", event["target"]))[1],
                      event.get("command_id") or event.get("event_id") or ""]
            for column, value in enumerate(values):
                item = Qt.QTableWidgetItem(value)
                item.setToolTip(value + "\n" + json.dumps(event.get("detail"), ensure_ascii=False))
                self.history_table.setItem(row_index, column, item)
        if rows:
            event = rows[-1]
            self.latest_label.setText(f"最近转换：{event['event']} · {self.profile.states[event['source']][1]} → {self.profile.states[event['target']][1]}")
            self.diagram.update_edges(event)
            self.history_table.scrollToBottom()

    @Qt.pyqtSlot(int, bool, str)
    def receive_completion(self, generation, success, message):
        if generation != self.generation:
            return
        self.busy = False
        self.message.setStyleSheet("color: #267568;" if success else "color: #b03d49;")
        self.message.setText(message)
        self.message.setToolTip(message)
        self.update_controls()

    def update_controls(self):
        enabled = self.connected and self.snapshot is not None and not self.busy
        allowed = available_actions(self.snapshot, self.profile) if enabled else []
        for key, button in self.action_buttons.items():
            button.setEnabled(key in allowed)
        feedback = bool(enabled and self.simulation_controls and self.snapshot.get("active_command_id"))
        self.success_button.setEnabled(feedback)
        self.failure_button.setEnabled(feedback)
        event_keys = available_confirmations(self.snapshot, self.profile) if self.snapshot else []
        old = self.event_choice.currentData()
        current_keys = [self.event_choice.itemData(i) for i in range(self.event_choice.count())]
        if event_keys != current_keys:
            self.event_choice.clear()
            for key in event_keys:
                self.event_choice.addItem(self.profile.confirmations[key].label, key)
            found = self.event_choice.findData(old)
            if found >= 0:
                self.event_choice.setCurrentIndex(found)
        injection = bool(enabled and self.simulation_controls and event_keys)
        self.event_choice.setEnabled(injection)
        self.event_button.setEnabled(injection)
        self.refresh_button.setEnabled(not self.busy)
        self.connect_button.setEnabled(not self.busy)

    def submit(self, command, payload):
        if not self.connected or self.busy or self.snapshot is None:
            return
        self.busy = True
        self.message.setText("正在等待 Tango 命令返回…")
        self.update_controls()
        self.command_requested.emit(self.generation, command, payload)

    def execute_action(self, action):
        if action not in available_actions(self.snapshot, self.profile):
            return
        try:
            parameters = json.loads(self.parameters.toPlainText())
            if not isinstance(parameters, dict):
                raise ValueError("参数必须为 JSON 对象")
        except ValueError as ex:
            self.message.setText("参数格式错误：" + str(ex))
            return
        self.submit("Execute", {"command_id": "gui-" + uuid.uuid4().hex, "action": action, "parameters": parameters})

    def simulate(self, outcome):
        if self.simulation_controls and self.snapshot and self.snapshot.get("active_command_id"):
            self.submit("Simulate", {"command_id": self.snapshot["active_command_id"], "outcome": outcome})

    def confirm_event(self):
        event = self.event_choice.currentData()
        if self.simulation_controls and event in available_confirmations(self.snapshot, self.profile):
            self.submit("ReportEvent", {"event_id": "gui-event-" + uuid.uuid4().hex, "event": event,
                                        "observed_outer_state": self.profile.confirmations[event].observation})

    def closeEvent(self, event):
        if not self._closing:
            self._closing = True
            Qt.QMetaObject.invokeMethod(self.worker, "stop", Qt.Qt.ConnectionType.BlockingQueuedConnection)
            self.worker_thread.quit()
            self.worker_thread.wait()
        event.accept()
