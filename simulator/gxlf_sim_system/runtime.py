"""Atomic, persistent seed events and independent task results for SCXML v2."""
from contextlib import contextmanager
from pathlib import Path
from threading import RLock
import json
import re
import sqlite3

from .machine import (
    ACTIONS, CONFIRMATIONS, MACHINE, MODEL_ID, MODEL_VERSION, STATES,
    TransitionError, confirmation_transition, completion_transition,
    expected_observation, request_transition,
)


SCHEMA_VERSION = 2
DATA_QUALIFICATIONS = frozenset(("unknown", "valid", "invalid"))
CONFIG_STATUSES = frozenset(("unknown", "pending", "valid", "invalid"))


def encode(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, allow_nan=False)


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value):
        raise TransitionError("编号必须为 1–128 个字母、数字、下划线或连字符")
    return value


class SeedRuntime:
    def __init__(self, database=":memory:"):
        if str(database) != ":memory:":
            Path(database).parent.mkdir(parents=True, exist_ok=True)
        self.lock = RLock()
        self.db = sqlite3.connect(str(database), timeout=10, isolation_level=None, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        try:
            with self._transaction():
                tables = {r[0] for r in self.db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
                if tables and "seed_schema" not in tables:
                    raise TransitionError("数据库不是种子源 v2 数据库，请使用新路径；不会覆盖旧数据")
                if "seed_schema" in tables:
                    version = self.db.execute("SELECT version FROM seed_schema").fetchone()
                    if version is None or version[0] != SCHEMA_VERSION:
                        raise TransitionError("不支持的种子源数据库版本；v1 数据不会被静默解释为 v2")
                else:
                    for sql in (
                        "CREATE TABLE seed_schema(version INTEGER NOT NULL)",
                        f"INSERT INTO seed_schema VALUES({SCHEMA_VERSION})",
                        "CREATE TABLE seed_state(id INTEGER PRIMARY KEY CHECK(id=1), leaf TEXT NOT NULL, active_id TEXT, last_id TEXT, last_result_id TEXT, config_status TEXT NOT NULL, config_version TEXT, data_status TEXT NOT NULL, faults TEXT NOT NULL)",
                        "INSERT INTO seed_state VALUES(1,'unchecked',NULL,NULL,NULL,'unknown',NULL,'unknown','[]')",
                        "CREATE TABLE seed_commands(id TEXT PRIMARY KEY, action TEXT NOT NULL, parameters TEXT NOT NULL, status TEXT NOT NULL, key_state TEXT, completion TEXT, result TEXT NOT NULL)",
                        "CREATE TABLE seed_events(sequence INTEGER PRIMARY KEY AUTOINCREMENT, event TEXT NOT NULL, command_id TEXT, event_id TEXT, source TEXT NOT NULL, target TEXT NOT NULL, detail TEXT NOT NULL, occurred_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')))",
                        "CREATE TABLE seed_receipts(id TEXT PRIMARY KEY, payload TEXT NOT NULL, result TEXT NOT NULL)",
                    ):
                        self.db.execute(sql)
                if self._state()["leaf"] not in STATES:
                    raise TransitionError("数据库包含未知的种子源 v2 状态")
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
        return self.db.execute("SELECT * FROM seed_state WHERE id=1").fetchone()

    def _command(self, command_id):
        row = self.db.execute("SELECT * FROM seed_commands WHERE id=?", (command_id,)).fetchone()
        if row is None:
            raise TransitionError("未知任务编号：" + command_id)
        return row

    def _snapshot(self):
        state = self._state()
        leaf = state["leaf"]
        outer_id = MACHINE.outer(leaf)
        outer_label, inner_label = STATES[leaf]
        active = self._command(state["active_id"]) if state["active_id"] else None
        last = self._command(state["last_id"]) if state["last_id"] else None
        result = self._command(state["last_result_id"]) if state["last_result_id"] else None
        return {
            "model": MODEL_ID,
            "model_version": MODEL_VERSION,
            "state_id": leaf,
            "outer_state": outer_id,
            "outer_state_label": outer_label,
            "inner_state": leaf,
            "inner_state_label": inner_label,
            # Compatibility names remain readable, but they no longer mean a task-progress state.
            "business_state": outer_label,
            "current_state": inner_label,
            "active_command_id": state["active_id"],
            "active_action": active["action"] if active else None,
            "task_state": "executing" if active else last["status"] if last else "idle",
            "configuration": {"status": state["config_status"], "version": state["config_version"]},
            "data_qualification": state["data_status"],
            "faults": json.loads(state["faults"]),
            "last_result": {"command_id": result["id"], "action": result["action"],
                            "status": result["status"], "key_state": result["key_state"]} if result else None,
        }

    def snapshot(self):
        with self._transaction():
            return self._snapshot()

    def _result(self, command_id, reason=None):
        row = self._command(command_id)
        result = {
            "command_id": row["id"], "action": row["action"],
            "parameters": json.loads(row["parameters"]), "status": row["status"],
            "key_state": row["key_state"], "snapshot": self._snapshot(),
        }
        if reason is not None:
            result["reason"] = reason
        self.db.execute("UPDATE seed_commands SET result=? WHERE id=?", (encode(result), command_id))
        return result

    def _log(self, event, source, target, detail, command_id=None, event_id=None):
        self.db.execute(
            "INSERT INTO seed_events(event,command_id,event_id,source,target,detail) VALUES(?,?,?,?,?,?)",
            (event, command_id, event_id, source, target, encode(detail)),
        )

    def _set_leaf(self, leaf):
        if leaf not in STATES:
            raise TransitionError("SCXML 转换产生未知叶子状态：" + leaf)
        self.db.execute("UPDATE seed_state SET leaf=? WHERE id=1", (leaf,))

    def _reset_configuration_if_needed(self, leaf):
        if MACHINE.outer(leaf) != "ready":
            self.db.execute("UPDATE seed_state SET config_status='unknown',config_version=NULL WHERE id=1")

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
            previous = self.db.execute("SELECT * FROM seed_commands WHERE id=?", (command_id,)).fetchone()
            if previous:
                if previous["action"] != action or previous["parameters"] != parameters_json:
                    raise TransitionError("同一任务编号不能用于不同动作或参数")
                return json.loads(previous["result"])
            state = self._state()
            if state["active_id"]:
                raise TransitionError("已有执行中的任务：" + state["active_id"])
            source = state["leaf"]
            target, request_event = request_transition(source, action)
            self.db.execute(
                "INSERT INTO seed_commands(id,action,parameters,status,result) VALUES(?,?,?,'accepted','{}')",
                (command_id, action, parameters_json),
            )
            self._set_leaf(target)
            if action == "parameter_dispatch":
                self.db.execute("UPDATE seed_state SET config_status='pending' WHERE id=1")
            if action == "laser_parameter_collect":
                # A new collection task never inherits the previous result's qualification.
                self.db.execute("UPDATE seed_state SET data_status='unknown' WHERE id=1")
            self.db.execute("UPDATE seed_state SET active_id=?,last_id=? WHERE id=1", (command_id, command_id))
            self._log(request_event, source, target, {"parameters": parameters, "task_accepted": True}, command_id)
            return self._result(command_id)

    def complete(self, request):
        if not isinstance(request, dict):
            raise TransitionError("反馈必须为 JSON 对象")
        command_id = identifier(request.get("command_id"))
        outcome = request.get("outcome")
        if outcome not in ("success", "failure"):
            raise TransitionError("outcome 必须是 success 或 failure")
        observation = request.get("observed_business_state")
        qualification = request.get("data_qualification", "unknown")
        if qualification not in DATA_QUALIFICATIONS:
            raise TransitionError("data_qualification 必须是 unknown、valid 或 invalid")
        payload = encode({"outcome": outcome, "observed_business_state": observation,
                          "data_qualification": qualification})
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
            target, event, key = completion_transition(source, command["action"], outcome, observation)
            self.db.execute(
                "UPDATE seed_commands SET status=?,key_state=?,completion=? WHERE id=?",
                ("succeeded" if outcome == "success" else "failed", key, payload, command_id),
            )
            self._set_leaf(target)
            self._reset_configuration_if_needed(target)
            if command["action"] == "parameter_dispatch":
                if outcome == "success":
                    self.db.execute("UPDATE seed_state SET config_status='valid',config_version=? WHERE id=1", (command_id,))
                else:
                    self.db.execute("UPDATE seed_state SET config_status='invalid' WHERE id=1")
            if command["action"] == "laser_parameter_collect":
                self.db.execute("UPDATE seed_state SET data_status=? WHERE id=1", (qualification,))
            self.db.execute("UPDATE seed_state SET active_id=NULL,last_result_id=? WHERE id=1", (command_id,))
            self._log(event, source, target, json.loads(payload), command_id)
            return self._result(command_id)

    def simulate(self, request):
        if not isinstance(request, dict):
            raise TransitionError("仿真反馈必须为 JSON 对象")
        command_id = identifier(request.get("command_id"))
        outcome = request.get("outcome")
        if outcome not in ("success", "failure"):
            raise TransitionError("仿真结果仅支持 success/failure；设备故障使用 events")
        with self.lock:
            command = self._command(command_id)
            state = self._state()["leaf"]
            expected = expected_observation(state, command["action"], outcome)
            observation = request.get("observed_business_state", expected)
            payload = {"command_id": command_id, "outcome": outcome, "observed_business_state": observation}
            if "data_qualification" in request:
                payload["data_qualification"] = request["data_qualification"]
            return self.complete(payload)

    def result(self, command_id):
        identifier(command_id)
        with self.lock:
            return json.loads(self._command(command_id)["result"])

    def report_event(self, request):
        """Report a confirmed physical fact and interrupt any conflicting task."""
        if not isinstance(request, dict):
            raise TransitionError("事件必须为 JSON 对象")
        event_id = identifier(request.get("event_id"))
        event = request.get("event")
        if not isinstance(event, str):
            raise TransitionError("event 必须是事件名称")
        observation = request.get("observed_business_state")
        payload = encode({"event": event, "observed_business_state": observation})
        with self._transaction():
            previous = self.db.execute("SELECT * FROM seed_receipts WHERE id=?", (event_id,)).fetchone()
            if previous:
                if previous["payload"] != payload:
                    raise TransitionError("同一事件编号不能用于不同确认事件")
                return json.loads(previous["result"])
            state = self._state()
            source = state["leaf"]
            target, machine_event, label = confirmation_transition(source, event, observation)
            active_id = state["active_id"]
            self._set_leaf(target)
            self._reset_configuration_if_needed(target)
            if event in ("fault_confirmed",):
                self.db.execute("UPDATE seed_state SET faults=? WHERE id=1", (encode(["unclassified"]),))
            elif event == "recovery_confirmed":
                self.db.execute("UPDATE seed_state SET faults='[]' WHERE id=1")
            if active_id:
                self.db.execute("UPDATE seed_commands SET status='interrupted',key_state='任务中断' WHERE id=?", (active_id,))
                self.db.execute("UPDATE seed_state SET active_id=NULL,last_result_id=? WHERE id=1", (active_id,))
                self._result(active_id, reason=label)
            self._log(machine_event, source, target, {**json.loads(payload), "confirmation": label}, active_id, event_id)
            result = {"event_id": event_id, "event": event, "interrupted_command_id": active_id, "snapshot": self._snapshot()}
            self.db.execute("INSERT INTO seed_receipts VALUES(?,?,?)", (event_id, payload, encode(result)))
            return result

    def history(self, limit=100):
        if not isinstance(limit, int) or not 1 <= limit <= 1000:
            raise TransitionError("limit 必须为 1–1000")
        with self.lock:
            rows = self.db.execute("SELECT * FROM seed_events ORDER BY sequence DESC LIMIT ?", (limit,)).fetchall()
            return [{**dict(row), "detail": json.loads(row["detail"])} for row in reversed(rows)]
