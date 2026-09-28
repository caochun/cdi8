# GXLF 实验流程仿真：分系统状态机

当前分支完成第一个开发步骤：根据 Excel 一次性整理出的定义，实现“光纤种子源组件”独立状态机。

模型文件：`gxlf_sim_system/models/excel-seed-source-state-machine.yaml`

执行器：`gxlf_sim_system/subsystem_fsm.py`

使用 Python 3.10 或更高版本安装依赖：

```bash
python3 -m pip install -e .
```

运行测试：

```bash
python3 -m unittest gxlf_sim_system.tests.test_seed_source_state_machine -v
```

也可以运行 `make test`。

调用示例：

```python
from gxlf_sim_system import load_seed_source_state_machine

machine = load_seed_source_state_machine()
machine.start("power_on_self_test")
print(machine.complete_success())
```

状态机包含开机自检、功能检查、参数下发、出光、参数采集、待机/复位、异常中止、关机，以及故障锁定、通信异常和执行超时处理。
