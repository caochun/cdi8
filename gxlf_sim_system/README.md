# GXLF 联合实验仿真

当前保留一份完整流程模型和两份分系统状态模型：

- `models/laser-joint-fanout-experiment.yaml`：14 个动作节点和 1 个联合门禁，自检至关机。
- `models/excel-seed-source-state-machine.yaml`：种子源，实例数量由流程配置。
- `models/excel-shg-injector-state-machine.yaml`：二倍频组件。

`subsystem_fsm.py` 执行分系统动作和状态转移；`joint_fanout_experiment.py` 负责联合编排及 all_success 聚合。
早期单系统、双单实例、独立扇出演示的 YAML、专用执行器和入口已删除，历史版本保留在 Git。

```bash
python3 -m gxlf_sim_system   # 完整联合周期演示
make operator              # 本地操作台 http://127.0.0.1:8765
make test                  # 当前模型回归测试
```

操作台、JSONL 日志和只读回放见 [使用说明](../reviews/operator-console.md)。
模型正确性验证的设计见 [验证计划](../reviews/model-correctness-verification.md)。
