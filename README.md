# GXLF 实验流程仿真

当前完成前三个开发步骤：光纤种子源独立状态机、单系统最小实验，以及多个分系统前置条件组合。

第 4 步增加了三实例并行扇出和 `all_success` 聚合。实例数量是仿真配置，不是 Excel 对光纤种子源数量的声明。

第 3 步增加了独立的“二倍频宽带激光注入组件”状态机和双分系统联合流程。两个分系统仍按顺序调度，联合门禁采用 AND 语义。

在进入第 5 步前，联合流程已扩展为“种子源 fan-out + 单实例二倍频组件”：种子源三个仿真实例按 `all_success` 聚合，二倍频组件使用一个实例，之后由联合门禁做跨分系统 AND 判断。

模型文件：`gxlf_sim_system/models/excel-seed-source-state-machine.yaml`

执行器：`gxlf_sim_system/subsystem_fsm.py`

使用 Python 3.10 或更高版本安装依赖：

```bash
python3 -m pip install -e .
```

运行最小实验仿真：

```bash
python3 -m gxlf_sim_system
```

运行双分系统联合仿真：

```bash
python3 -m gxlf_sim_system.joint_demo
```

运行并行扇出仿真：

```bash
python3 -m gxlf_sim_system.fanout_demo
```

运行联合 fan-out 仿真：

```bash
python3 -m gxlf_sim_system.joint_fanout_demo
```

依次执行：开机自检 → 功能检查 → 参数下发 → 出光 → 参数采集 → 待机/复位 → 关机。
这是单分系统的验证流程，不代表完整发射实验。命令行示例逐个动作显式回报成功，
不模拟真实设备反馈或耗时；运行时只读取已整理的 YAML，不读取 Excel。

运行所有测试：

```bash
make test
```

流程模型：`gxlf_sim_system/models/seed-source-experiment.yaml`。

双分系统流程模型：`gxlf_sim_system/models/laser-joint-experiment.yaml`。

联合流程执行器：`gxlf_sim_system/composite_experiment.py`。

并行扇出模型：`gxlf_sim_system/models/seed-source-fanout-experiment.yaml`。

并行扇出执行器：`gxlf_sim_system/parallel_experiment.py`。

联合 fan-out 模型：`gxlf_sim_system/models/laser-joint-fanout-experiment.yaml`。

联合 fan-out 执行器：`gxlf_sim_system/joint_fanout_experiment.py`。

流程执行器：`gxlf_sim_system/experiment.py`。

流程状态为 `idle → running → succeeded/failed`，节点状态为
`waiting → running → succeeded/failed`。每次运行具有独立编号；
下发命令后等待结果确认，成功回报还须符合分系统模型声明的成功状态。
命令被拒绝、执行失败或结果不符时停止推进，下游节点保留 `waiting`。
失败不会自动复位、补偿或关机；自动超时检测和联锁监测留到第 5 步实现。

手动分步驱动：

```python
from gxlf_sim_system import load_seed_source_state_machine, load_seed_source_experiment
from gxlf_sim_system.simulation import simulated_success_evidence

machine = load_seed_source_state_machine()
experiment = load_seed_source_experiment(machine)
dispatch = experiment.dispatch_next()  # 只启动开机自检，不视为成功
result = experiment.complete(
    dispatch, success=True,
    evidence=simulated_success_evidence(dispatch.action),  # 明确使用演示用模拟反馈
)
print(experiment.snapshot())
```

`complete` 要求反馈匹配当前运行、节点和任务；旧任务或重复反馈被拒绝。
一个流程实例只执行一次，成功关机后可以为同一分系统新建下一次流程实例。
第二步修正了原模型中开机前置状态的顿号解析，允许“关机完成”后再次开机。
模型现为 2.0：成功分支按 K 列投影，失败统一投影为“异常”，不会把失败的功能检查
标为“就绪”或把失败的出光标为“出光”。这是 Excel 未明确失败 K 值时的建模约定；
它表示业务资格无效，不表示物理设备已经停止出光。

单独调用分系统：

```python
from gxlf_sim_system import load_seed_source_state_machine
from gxlf_sim_system.simulation import simulated_success_evidence

machine = load_seed_source_state_machine()
started = machine.start("power_on_self_test")
print(machine.complete_success(
    task_id=started.task_id,
    evidence=simulated_success_evidence("power_on_self_test"),
))
```

状态机包含开机自检、功能检查、参数下发、出光、参数采集、待机/复位、异常中止、关机，以及故障锁定、通信异常和执行超时处理。

模型与 Excel 的对应关系：

| Excel 列 | 模型字段与行为 |
|---|---|
| D 关键状态描述 | `key_state_description`，原文行描述，不冒充成功或失败结果 |
| E/F 执行前状态 | `precondition` |
| G/H 正常结果 | `success`；H 写入 `current_state` 和兼容字段 `key_state` |
| I/J 异常结果 | `failure`；J 写入 `current_state` 和 `key_state` |
| K 业务状态 | 成功时的 `business_state`，供流程投影使用 |
| L 业务状态描述 | `business_state_description` 保留全文；部分转为反馈条件和失效事件 |
| M 判定条件 | `state_condition` 保留全文；`completion` 定义实际反馈判据 |
| N 对流程影响 | `flow_impact` 保留全文；当前最小流程仍统一失败阻断 |
| O 状态定义 | `state_definition`，进入快照和历史，是语义分类而不是放行信号 |

`CompletionEvidence` 包含成功回调、实际观测状态和条件字典。只有匹配模型的
`observed_states` 且所有 `required_conditions` 都为布尔值 `True`，才能完成成功转移。
缺失条件、错误反馈或失败回调都会转入动作异常。反馈必须携带当前 `task_id`，
防止被异常打断的旧任务完成后来重试的同名动作。
`simulation.py` 提供的是明确列出的演示反馈，不读取真实设备，也不证明真实系统条件满足。

“通用异常处置”的三行是独立事件，不在实验动作序列中：

```python
experiment.report_exception("communication_error")
# 也可直接对分系统调用：machine.report_exception("fault_lock")
```

`fault_lock`、`communication_error`、`execution_timeout` 均可在动作执行中触发，
终止当前任务并置主状态、业务状态为“异常”。经流程接口上报时会立即阻断流程。
直接操作分系统后，流程在后续完成反馈或下发时发现异常；本阶段没有后台事件总线。
`abort_reset` 可抢占并取消当前任务，只有回报故障原因解除及安全复位等证据后才完成；
成功后按第 9 行保留“主状态就绪、业务状态未就绪”。普通关机不抢占执行中的任务。

通用异常恢复要求显式证据：

```python
machine.recover_from_exception(conditions={
    "cause_cleared": True,
    "reset_verified": True,
    "safe_position": True,
    "resources_released": True,
})
```

恢复先回到“未就绪/复位待机完成”。Excel 未定义保持上电时待机重入的具体动作，
当前通过关机、重新自检和功能检查恢复资格，不虚构原表中不存在的转移。
`invalidate("configuration_changed" / "emission_lost" / "readiness_lost")` 可显式报告
配置、出光、就绪条件失效并退出业务资格。这些事件及其条件必须由调用方上报；
自动心跳检测、计时器、持续联锁监测和补偿调度尚未实现。
模型的 `semantics` 和 `open_questions` 分别列出开发补充规则和原表未明确的部分。
