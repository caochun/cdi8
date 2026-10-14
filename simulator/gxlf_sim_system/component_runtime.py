"""Persistent runtime shared by all SCXML component models."""

from contextlib import contextmanager
from pathlib import Path
from threading import RLock
import copy
import json
import re
import sqlite3

from .component_model import ComponentModel
from .scxml_machine import TransitionError


SCHEMA_VERSION = 1
DATA_QUALIFICATIONS = frozenset(("unknown", "valid", "invalid"))


def encode(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, allow_nan=False)


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value):
        raise TransitionError("编号必须为 1–128 个字母、数字、下划线或连字符")
    return value


class ComponentRuntime:
    """SQLite-backed task runtime parameterized by one component model."""

    def __init__(self, model: ComponentModel, database=":memory:"):
        self.model = model
        if str(database) != ":memory:":
            Path(database).parent.mkdir(parents=True, exist_ok=True)
        self.lock = RLock()
        self.db = sqlite3.connect(str(database), timeout=10, isolation_level=None, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        try:
            with self._transaction():
                tables = {r[0] for r in self.db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
                if tables and "component_schema" not in tables:
                    raise TransitionError("数据库不是当前 SCXML 组件数据库；请使用新路径")
                if "component_schema" in tables:
                    row = self.db.execute("SELECT version,model FROM component_schema").fetchone()
                    if row is None or row[0] != SCHEMA_VERSION or row[1] != model.model_id:
                        raise TransitionError("数据库版本或 SCXML 组件模型不匹配")
                else:
                    for sql in (
                        "CREATE TABLE component_schema(version INTEGER NOT NULL, model TEXT NOT NULL)",
                        "INSERT INTO component_schema VALUES(?,?)",
                        "CREATE TABLE component_state(id INTEGER PRIMARY KEY CHECK(id=1), leaf TEXT NOT NULL, active_id TEXT, last_id TEXT, last_result_id TEXT, context TEXT NOT NULL)",
                        "CREATE TABLE component_commands(id TEXT PRIMARY KEY, action TEXT NOT NULL, parameters TEXT NOT NULL, status TEXT NOT NULL, key_state TEXT, completion TEXT, result TEXT NOT NULL)",
                        "CREATE TABLE component_events(sequence INTEGER PRIMARY KEY AUTOINCREMENT, event TEXT NOT NULL, command_id TEXT, event_id TEXT, source TEXT NOT NULL, target TEXT NOT NULL, detail TEXT NOT NULL, occurred_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')))",
                        "CREATE TABLE component_receipts(id TEXT PRIMARY KEY, payload TEXT NOT NULL, result TEXT NOT NULL)",
                    ):
                        if sql == "INSERT INTO component_schema VALUES(?,?)":
                            self.db.execute(sql, (SCHEMA_VERSION, model.model_id))
                        else:
                            self.db.execute(sql)
                    self.db.execute("INSERT INTO component_state VALUES(1,?,?,?,?,?)", (model.machine.initial_leaf, None, None, None, encode(model.initial_context())))
                if self._state()["leaf"] not in model.states:
                    raise TransitionError("数据库包含当前 SCXML 模型不存在的状态")
        except Exception:
            self.db.close()
            raise

    @contextmanager
    def _transaction(self):
        with self.lock:
            self.db.execute("BEGIN IMMEDIATE")
            try:
                yield
                self.db.commit()
            except Exception:
                self.db.rollback()
                raise

    def close(self):
        with self.lock:
            self.db.close()

    def _state(self):
        return self.db.execute("SELECT * FROM component_state WHERE id=1").fetchone()

    def _command(self, command_id):
        row = self.db.execute("SELECT * FROM component_commands WHERE id=?", (command_id,)).fetchone()
        if row is None:
            raise TransitionError("未知任务编号：" + command_id)
        return row

    def _context(self):
        return json.loads(self._state()["context"])

    def _save_context(self, context):
        self.db.execute("UPDATE component_state SET context=? WHERE id=1", (encode(context),))

    def _snapshot(self):
        state = self._state()
        leaf = state["leaf"]
        outer_id = self.model.machine.outer(leaf)
        active = self._command(state["active_id"]) if state["active_id"] else None
        last = self._command(state["last_id"]) if state["last_id"] else None
        result = self._command(state["last_result_id"]) if state["last_result_id"] else None
        context = self._context()
        value = {
            "model": self.model.model_id,
            "model_version": self.model.model_version,
            "component": self.model.display_name,
            "state_id": leaf,
            "outer_state": outer_id,
            "outer_state_label": self.model.outer_label(leaf),
            "inner_state": leaf,
            "inner_state_label": self.model.inner_label(leaf),
            "active_command_id": state["active_id"],
            "active_action": active["action"] if active else None,
            "task_state": "executing" if active else last["status"] if last else "idle",
            "context": copy.deepcopy(context),
            "last_result": {"command_id": result["id"], "action": result["action"], "status": result["status"], "key_state": result["key_state"]} if result else None,
        }
        # These are explicit context fields for clients that need qualification
        # information; they are absent for models that do not define them.
        if "configuration" in context:
            value["configuration"] = copy.deepcopy(context["configuration"])
        if "data_qualification" in context:
            value["data_qualification"] = context["data_qualification"]
        if "faults" in context:
            value["faults"] = copy.deepcopy(context["faults"])
        return value

    def snapshot(self):
        with self._transaction():
            return self._snapshot()

    def _result(self, command_id, reason=None):
        row = self._command(command_id)
        result = {"command_id": row["id"], "action": row["action"], "parameters": json.loads(row["parameters"]), "status": row["status"], "key_state": row["key_state"], "snapshot": self._snapshot()}
        if reason is not None:
            result["reason"] = reason
        self.db.execute("UPDATE component_commands SET result=? WHERE id=?", (encode(result), command_id))
        return result

    def _log(self, event, source, target, detail, command_id=None, event_id=None):
        self.db.execute("INSERT INTO component_events(event,command_id,event_id,source,target,detail) VALUES(?,?,?,?,?,?)", (event, command_id, event_id, source, target, encode(detail)))

    def _set_leaf(self, leaf):
        if leaf not in self.model.states:
            raise TransitionError("SCXML 转换产生未知叶子状态：" + leaf)
        self.db.execute("UPDATE component_state SET leaf=? WHERE id=1", (leaf,))

    def _request_context(self, context, action):
        if action == "parameter_dispatch" and "configuration" in context:
            context["configuration"] = {"status": "pending", "version": context["configuration"].get("version")}
        if action in {"laser_parameter_collect", "energy_collect"} and "data_qualification" in context:
            context["data_qualification"] = "unknown"

    def _completion_context(self, context, action, outcome, command_id, qualification):
        if action == "parameter_dispatch" and "configuration" in context:
            context["configuration"] = {"status": "valid" if outcome == "success" else "invalid", "version": command_id if outcome == "success" else None}
        if action in {"laser_parameter_collect", "energy_collect"} and "data_qualification" in context:
            context["data_qualification"] = qualification
        if self.model.machine.outer(self._state()["leaf"]) != "ready" and "configuration" in context:
            context["configuration"] = {"status": "unknown", "version": None}

    def execute(self, request):
        if not isinstance(request, dict):
            raise TransitionError("命令必须为 JSON 对象")
        command_id = identifier(request.get("command_id"))
        action = request.get("action")
        if not isinstance(action, str):
            raise TransitionError("action 必须是动作名称")
        parameters = request.get("parameters", {})
        if not isinstance(parameters, dict):
            raise TransitionError("parameters 必须为 JSON 对象")
        parameters_json = encode(parameters)
        with self._transaction():
            previous = self.db.execute("SELECT * FROM component_commands WHERE id=?", (command_id,)).fetchone()
            if previous:
                if previous["action"] != action or previous["parameters"] != parameters_json:
                    raise TransitionError("同一任务编号不能用于不同动作或参数")
                return json.loads(previous["result"])
            state = self._state()
            if state["active_id"]:
                raise TransitionError("已有执行中的任务：" + state["active_id"])
            source = state["leaf"]
            target, request_event = self.model.request_transition(source, action)
            self.db.execute("INSERT INTO component_commands(id,action,parameters,status,result) VALUES(?,?,?,'accepted','{}')", (command_id, action, parameters_json))
            self._set_leaf(target)
            context = self._context()
            self._request_context(context, action)
            self._save_context(context)
            self.db.execute("UPDATE component_state SET active_id=?,last_id=? WHERE id=1", (command_id, command_id))
            self._log(request_event, source, target, {"parameters": parameters, "task_accepted": True}, command_id)
            return self._result(command_id)

    def complete(self, request):
        if not isinstance(request, dict):
            raise TransitionError("反馈必须为 JSON 对象")
        command_id = identifier(request.get("command_id"))
        outcome = request.get("outcome")
        if outcome not in ("success", "failure"):
            raise TransitionError("outcome 必须是 success 或 failure")
        observation = request.get("observed_outer_state")
        qualification = request.get("data_qualification", "unknown")
        if qualification not in DATA_QUALIFICATIONS:
            raise TransitionError("data_qualification 必须是 unknown、valid 或 invalid")
        payload = encode({"outcome": outcome, "observed_outer_state": observation, "data_qualification": qualification})
        with self._transaction():
            command = self._command(command_id)
            if command["status"] == "interrupted":
                return json.loads(command["result"])
            if command["status"] != "accepted":
                if command["completion"] != payload:
                    raise TransitionError("已完成任务不能改写为另一份反馈")
                return json.loads(command["result"])
            state = self._state()
            if state["active_id"] != command_id:
                raise TransitionError("反馈不是当前任务")
            source = state["leaf"]
            target, event, key = self.model.completion_transition(source, command["action"], outcome, observation)
            self.db.execute("UPDATE component_commands SET status=?,key_state=?,completion=? WHERE id=?", ("succeeded" if outcome == "success" else "failed", key, payload, command_id))
            self._set_leaf(target)
            context = self._context()
            self._completion_context(context, command["action"], outcome, command_id, qualification)
            self._save_context(context)
            self.db.execute("UPDATE component_state SET active_id=NULL,last_result_id=? WHERE id=1", (command_id,))
            self._log(event, source, target, json.loads(payload), command_id)
            return self._result(command_id)

    def simulate(self, request):
        if not isinstance(request, dict):
            raise TransitionError("仿真反馈必须为 JSON 对象")
        command_id = identifier(request.get("command_id"))
        command = self._command(command_id)
        outcome = request.get("outcome")
        if outcome not in ("success", "failure"):
            raise TransitionError("仿真结果仅支持 success/failure；设备故障使用 events")
        expected = self.model.actions[command["action"]].success_observation if outcome == "success" else self.model.actions[command["action"]].failure_observation
        payload = {"command_id": command_id, "outcome": outcome, "observed_outer_state": request.get("observed_outer_state", expected)}
        if "data_qualification" in request:
            payload["data_qualification"] = request["data_qualification"]
        return self.complete(payload)

    def result(self, command_id):
        identifier(command_id)
        with self.lock:
            return json.loads(self._command(command_id)["result"])

    def report_event(self, request):
        if not isinstance(request, dict):
            raise TransitionError("事件必须为 JSON 对象")
        event_id = identifier(request.get("event_id"))
        event = request.get("event")
        if not isinstance(event, str):
            raise TransitionError("event 必须是事件名称")
        observation = request.get("observed_outer_state")
        payload = encode({"event": event, "observed_outer_state": observation})
        with self._transaction():
            previous = self.db.execute("SELECT * FROM component_receipts WHERE id=?", (event_id,)).fetchone()
            if previous:
                if previous["payload"] != payload:
                    raise TransitionError("同一事件编号不能用于不同确认事件")
                return json.loads(previous["result"])
            state = self._state()
            source = state["leaf"]
            target, machine_event, label = self.model.confirmation_transition(source, event, observation)
            active_id = state["active_id"]
            self._set_leaf(target)
            context = self._context()
            if event in {"fault_confirmed", "communication_fault_confirmed", "timeout_confirmed"} and "faults" in context:
                context["faults"] = {"fault_confirmed": ["unclassified"], "communication_fault_confirmed": ["communication"], "timeout_confirmed": ["timeout"]}[event]
            elif event == "recovery_confirmed" and "faults" in context:
                context["faults"] = []
            self._save_context(context)
            if active_id:
                self.db.execute("UPDATE component_commands SET status='interrupted',key_state='任务中断' WHERE id=?", (active_id,))
                self.db.execute("UPDATE component_state SET active_id=NULL,last_result_id=? WHERE id=1", (active_id,))
                self._result(active_id, reason=label)
            self._log(machine_event, source, target, {**json.loads(payload), "confirmation": label}, active_id, event_id)
            result = {"event_id": event_id, "event": event, "interrupted_command_id": active_id, "snapshot": self._snapshot()}
            self.db.execute("INSERT INTO component_receipts VALUES(?,?,?)", (event_id, payload, encode(result)))
            return result

    def history(self, limit=100):
        if not isinstance(limit, int) or not 1 <= limit <= 1000:
            raise TransitionError("limit 必须为 1–1000")
        with self.lock:
            rows = self.db.execute("SELECT * FROM component_events ORDER BY sequence DESC LIMIT ?", (limit,)).fetchall()
            return [{**dict(row), "detail": json.loads(row["detail"])} for row in reversed(rows)]


__all__ = ["ComponentRuntime", "DATA_QUALIFICATIONS", "TransitionError", "identifier"]
