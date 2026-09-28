# GXLF 实验流程仿真

当前完成前两个开发步骤：根据 Excel 一次性整理出的定义，实现“光纤种子源组件”独立状态机，以及基于该状态机的最小实验流程。

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

依次执行：开机自检 → 功能检查 → 参数下发 → 出光 → 参数采集 → 待机/复位 → 关机。
这是单分系统的验证流程，不代表完整发射实验。命令行示例逐个动作显式回报成功，
不模拟真实设备反馈或耗时；运行时只读取已整理的 YAML，不读取 Excel。

运行所有测试：

```bash
make test
```

流程模型：`gxlf_sim_system/models/seed-source-experiment.yaml`。

流程执行器：`gxlf_sim_system/experiment.py`。

流程状态为 `idle → running → succeeded/failed`，节点状态为
`waiting → running → succeeded/failed`。每次运行具有独立编号；
下发命令后等待结果确认，成功回报还须符合分系统模型声明的成功状态。
命令被拒绝、执行失败或结果不符时停止推进，下游节点保留 `waiting`。
失败不会自动复位、补偿或关机；重试、跨系统条件、并行调度、超时和联锁留到后续步骤实现。

手动分步驱动：

```python
from gxlf_sim_system import load_seed_source_state_machine, load_seed_source_experiment

machine = load_seed_source_state_machine()
experiment = load_seed_source_experiment(machine)
dispatch = experiment.dispatch_next()  # 只启动开机自检，不视为成功
result = experiment.complete(dispatch, success=True)  # 模拟该任务的完成反馈
print(experiment.snapshot())
```

`complete` 要求反馈匹配当前运行、节点和任务；旧任务或重复反馈被拒绝。
一个流程实例只执行一次，成功关机后可以为同一分系统新建下一次流程实例。
第二步修正了原模型中开机前置状态的顿号解析，允许“关机完成”后再次开机。
分系统成功/失败后的业务状态继续按第一步模型保留，流程放行同时检查任务和主状态，
不会仅凭业务状态的“就绪/出光”标签推进。

单独调用分系统：

```python
from gxlf_sim_system import load_seed_source_state_machine

machine = load_seed_source_state_machine()
machine.start("power_on_self_test")
print(machine.complete_success())
```

状态机包含开机自检、功能检查、参数下发、出光、参数采集、待机/复位、异常中止、关机，以及故障锁定、通信异常和执行超时处理。
