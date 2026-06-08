# GXLF 流程引擎测试体系

测试分三层：

1. `test_static_model_validation.py`
   - 不运行流程，只检查模型结构、自洽性、字段支持矩阵和已知语义 gap。
   - 目标是防止模型写了引擎不认识的字段却无人察觉。

2. `test_engine_semantics.py`
   - 使用小型 fake DAG 和 fake adapter，测试引擎核心语义。
   - 覆盖 DAG 调度、fan-out、callback 成功/失败/超时、guard wait/release、stop、`started` guard。

3. `test_model_scenarios.py` + `scenarios/**/*.yaml`
   - 使用真实 `gxlf-firing-flow.yaml` 执行声明式场景。
   - 用于验证真实模型和引擎组合后的行为，同时记录模型声明但引擎尚未支持的策略 gap。

## 运行

```bash
make test
make test-static
make test-engine
make test-scenarios
```

## 场景 YAML 示例

```yaml
id: n01_recipe_not_loaded
title: N01 配方未加载时等待，恢复后继续
suite: model_scenario
initial_context:
  flags:
    recipe_loaded: false
run:
  max_nodes: 1
expect_before_update:
  waiting_nodes: [N01]
  commands_dispatched: 0
then:
  update_context:
    flags:
      recipe_loaded: true
expect:
  flow_status: completed
  completed_nodes: [N01]
```

`suite: model_gap` 用于把模型中已声明、但引擎尚未完整支持的策略显式固定下来。它不是通过测试失败表达问题，而是让 gap 进入可跟踪状态。
