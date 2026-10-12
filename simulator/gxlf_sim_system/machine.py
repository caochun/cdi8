"""Transitions corresponding to docs/diagrams/seed-source-state-machine-draft.mmd.

Business state is the parent of the current leaf. Command failure does not itself
mean device fault. No implicit timer, reset, or shutdown request path is invented.
"""
from dataclasses import dataclass
from typing import Optional


class TransitionError(ValueError):
    """The event is not enabled or its evidence does not satisfy the diagram."""


# Mermaid identifier -> (outer business state, leaf label).
STATES = {
    "Idle": ("未就绪", "等待自检"),
    "Testing": ("未就绪", "自检中"),
    "TestOK": ("未就绪", "自检成功"),
    "TestFailed": ("未就绪", "自检失败"),
    "Checking": ("未就绪", "功能检查中"),
    "CheckFailed": ("未就绪", "功能检查失败"),
    "ConfigIdle": ("就绪", "等待参数下发"),
    "Configuring": ("就绪", "参数下发中"),
    "ConfigOK": ("就绪", "参数下发成功"),
    "ConfigFailed": ("就绪", "参数下发失败"),
    "Starting": ("就绪", "出光启动中"),
    "StartFailed": ("就绪", "出光启动失败"),
    "CollectIdle": ("出光", "等待采集"),
    "Collecting": ("出光", "参数采集中"),
    "CollectOK": ("出光", "采集成功"),
    "CollectFailed": ("出光", "采集失败"),
    "Fault": ("异常", "设备异常"),
}


@dataclass(frozen=True)
class Action:
    label: str
    requests: dict
    running: str
    success: str
    failure: str
    success_event: str
    failure_event: str
    success_key: str
    failure_key: str
    success_observation: Optional[str] = None
    failure_observation: Optional[str] = None


ACTIONS = {
    "power_on_self_test": Action(
        "开机自检", {"Idle": "开机自检请求", "TestFailed": "自检重试请求"},
        "Testing", "TestOK", "TestFailed", "自检成功反馈", "自检失败反馈", "自检成功", "自检失败"),
    "function_check": Action(
        "功能检查", {"TestOK": "功能检查请求", "CheckFailed": "功能检查重试请求"},
        "Checking", "ConfigIdle", "CheckFailed", "功能检查通过且业务就绪确认", "功能检查失败反馈",
        "功能检查成功", "功能检查失败", success_observation="就绪"),
    "parameter_dispatch": Action(
        "参数下发", {"ConfigIdle": "参数下发请求", "ConfigOK": "新的参数下发请求", "ConfigFailed": "参数下发重试请求"},
        "Configuring", "ConfigOK", "ConfigFailed", "参数配置成功反馈", "参数下发或校验失败反馈",
        "参数配置正常", "参数下发失败"),
    "seed_source_emit": Action(
        "种子源出光", {"ConfigOK": "出光请求", "StartFailed": "出光重试请求"},
        "Starting", "CollectIdle", "StartFailed", "稳定出光确认", "出光失败且仍就绪的反馈",
        "稳定出光正常", "出光启动失败", success_observation="出光", failure_observation="就绪"),
    "laser_parameter_collect": Action(
        "激光参数采集", {"CollectIdle": "参数采集请求", "CollectOK": "新的采集请求", "CollectFailed": "采集重试请求"},
        "Collecting", "CollectOK", "CollectFailed", "采集成功反馈", "采集失败反馈", "采集正常", "采集失败"),
}


@dataclass(frozen=True)
class Confirmation:
    label: str
    sources: frozenset
    target: str
    observation: str


CONFIRMATIONS = {
    "standby_confirmed": Confirmation("正常复位完成且就绪确认", frozenset({"出光"}), "ConfigIdle", "就绪"),
    "shutdown_confirmed": Confirmation("关机完成且未就绪确认", frozenset({"就绪", "出光"}), "Idle", "未就绪"),
    "abort_reset_confirmed": Confirmation("中止复位完成且未就绪确认", frozenset({"就绪", "出光"}), "Idle", "未就绪"),
    "fault_confirmed": Confirmation("设备故障确认", frozenset({"未就绪", "就绪", "出光"}), "Fault", "异常"),
    "recovery_confirmed": Confirmation("恢复后未就绪确认", frozenset({"异常"}), "Idle", "未就绪"),
}


def request_transition(state, action):
    if action not in ACTIONS:
        raise TransitionError("未知动作或图中未定义请求过程：" + str(action))
    spec = ACTIONS[action]
    if state not in spec.requests:
        raise TransitionError(f"{STATES[state][0]}/{STATES[state][1]} 不接受 {spec.label}")
    return spec.running, spec.requests[state]


def completion_transition(state, action, outcome, observed_business_state=None):
    if outcome not in {"success", "failure"}:
        raise TransitionError("反馈必须是 success 或 failure；图中未定义自动超时转换")
    spec = ACTIONS[action]
    if state != spec.running:
        raise TransitionError("反馈不对应当前执行状态")
    success = outcome == "success"
    expected = spec.success_observation if success else spec.failure_observation
    # Other task feedback cannot contradict the unchanged business state either.
    unchanged = STATES[spec.success if success else spec.failure][0]
    if expected is not None and observed_business_state != expected:
        raise TransitionError(f"该反馈还必须确认业务状态为 {expected}")
    if observed_business_state is not None and observed_business_state != unchanged:
        raise TransitionError("任务反馈与业务状态不一致；设备故障请使用独立确认事件")
    return (spec.success, spec.success_event, spec.success_key) if success else (spec.failure, spec.failure_event, spec.failure_key)


def confirmation_transition(state, event, observed_business_state):
    if event not in CONFIRMATIONS:
        raise TransitionError("未知确认事件：" + str(event))
    spec = CONFIRMATIONS[event]
    if STATES[state][0] not in spec.sources:
        raise TransitionError(f"{STATES[state][0]} 不接受 {spec.label}")
    if observed_business_state != spec.observation:
        raise TransitionError(f"必须明确确认业务状态为 {spec.observation}")
    return spec.target, spec.label
