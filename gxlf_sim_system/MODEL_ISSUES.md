# GXLF 模型问题清单

本文记录 `gxlf_sim_system/models/` 下三份模型文件之间的交叉一致性问题：

- `gxlf-firing-flow.yaml`
- `interface-contracts.yaml`
- `service-state-machines.yaml`

当前仿真系统可以跑通黄金路径，是因为运行时加入了少量、范围很窄的兼容规则。但如果这三份 YAML 要作为后续实现的权威契约，下面这些问题仍建议修正。

## 摘要

模型校验命令：

```bash
python3 -m gxlf_sim_system validate
```

黄金路径仿真命令：

```bash
python3 -m gxlf_sim_system run --events 5
```

当前黄金路径仿真结果为：59 个节点全部完成，0 个失败。但校验器会报告若干模型警告。

## P0 问题

### 1. 多程放大系统名称和扇出粒度不一致

三份文件对多程放大对象的命名和实例粒度不一致：

- `gxlf-firing-flow.yaml` 使用 `多程放大系统`，并按 60 路束线建模，`fan_out: by_beam_line`。
- `service-state-machines.yaml` 使用 `多程放大组件`，并按 10 路束组建模，`selector: by_beam_group`。
- `interface-contracts.yaml` 的 N14 也使用 `多程放大组件`，并按 `by_beam_group` 建模。

影响：

- N01、N14 无法在严格状态机中直接找到对应服务模板，必须靠运行时别名兼容。
- fan-out 数量不一致：60 路 vs 10 路。
- 实例编号规则不一致：`bl01..bl60` vs `bg01..bg10`。

建议修正：

- 选定一个规范名称：`多程放大系统` 或 `多程放大组件`。
- 选定一个规范扇出粒度：按束线 60 路，或按束组 10 路。
- 同步更新：
  - `gxlf-firing-flow.yaml` 的 `systems` 和节点 `target.system`
  - `service-state-machines.yaml` 的 `system_instance_catalog`
  - `service-state-machines.yaml` 的 `service_type_templates.applies_to`
  - `interface-contracts.yaml` 的 `node_contracts.target_systems`

### 2. N18 / N19 在三份文件中的语义错位

`service-state-machines.yaml` 的确认口径写明：

- N18 是“进入打靶状态”，对接 `多程放大组件`，10 路，按 `beam_group` 扇出。
- N19 是“打靶运动准备”，业务指令为“测量打靶运动准备”，对接 `测量取样组件`，60 路，按 `beam_line` 扇出。

但 `gxlf-firing-flow.yaml` 当前写法是：

- N18 为 `测量打靶运动准备`，对接 `测量取样组件`，command 为 `MeasShotMotionReady`。
- N19 为 `打靶运动准备`，对接 `测量取样组件`，command 为 `ShotMotionReady`。

而 `interface-contracts.yaml` 当前写法是：

- N18 为 `进入打靶状态`，对接 `多程放大组件`，command 为 `EnterShotState`。
- N19 为 `打靶运动准备`，对接 `测量取样组件`，command 为 `MeasShotMotionReady`。

影响：

- 流程节点语义和接口契约不一致。
- N18、N19 无法作为稳定的权威契约行使用。
- 仿真系统必须通过 command 别名和幂等兼容才能跑通当前黄金路径。

建议修正：

- 修改 `gxlf-firing-flow.yaml`，使 N18 与接口契约一致：`EnterShotState`，对接多程放大束组。
- 修改 N19，使其使用 `MeasShotMotionReady`，对接测量取样束线。
- 如果确实存在独立的 `ShotMotionReady` 命令，需要同时补充到 `measurement_sample.commands` 和 `interface-contracts.yaml` 中。

### 3. N19 使用了待确认的占位 command

`gxlf-firing-flow.yaml` 中 N19 使用 `ShotMotionReady`，并标注了“待确认”。但接口契约和状态机中定义的是 `MeasShotMotionReady`。

影响：

- 严格按状态机查找 command 时，`measurement_sample.commands` 中不存在 `ShotMotionReady`。
- 适配器方法名不一致。
- 日后对接真实 Tango 服务时容易出现方法名漂移。

建议修正：

- 将 N19 command 统一改为 `MeasShotMotionReady`。
- 如果业务方确认 `ShotMotionReady` 是独立命令，则需要补齐：
  - `interface-contracts.yaml` 的 node contract
  - `service-state-machines.yaml` 的状态迁移
  - 真实 Tango adapter 方法映射

### 4. N53 靶瞄数据分析与 N54 靶瞄收靶缺少顺序依赖

`gxlf-firing-flow.yaml` 当前允许：

- N53 `靶瞄数据分析` 在 `激光参数采集` 后执行。
- N54 `靶瞄收靶` 在 `正式发射完成` 后执行。

两者都操作 `靶瞄准定位系统`。如果调度器先执行 N54，靶瞄业务状态会进入 `retracted`；而状态机中 `TargetDataAnalysis` 默认不是从 `retracted` 状态执行。

影响：

- DAG 上 N54 可以合法早于 N53 执行。
- 严格状态机仿真会拒绝 N53。
- 实际设备上可能出现“先收靶，再做依赖靶位姿的数据分析”的语义风险。

建议修正：

- 如果数据分析必须在收靶前完成，则让 N54 依赖 N53。
- 如果数据分析读取的是已归档数据，可以显式允许 `TargetDataAnalysis` 从 `retracted` 状态执行，并在说明中写明“分析归档数据，不依赖当前靶位置”。

## P1 问题

### 5. 同步分系统配方状态被建模成互斥单状态

同步分系统有多个独立配方加载动作：

- `SyncPreampRepRateRecipe`
- `SyncMeasRepRateRecipe`
- `PreampSingleShotRecipe`
- `MeasSingleShotRecipe`

当前状态机把它们表达为互斥的 `business_state`，例如：

- `preamp_reprate_loaded`
- `meas_reprate_loaded`
- `preamp_single_loaded`
- `meas_single_loaded`

影响：

- 流程层允许某些配方节点并行或相邻执行。
- 一个配方加载完成后，可能导致另一个配方命令在状态机中变成非法。
- 这类状态更像多个独立布尔事实，而不是互斥单态。

建议修正：

- 将同步配方 readiness 改为 flags，例如：
  - `preamp_reprate_loaded: true`
  - `meas_reprate_loaded: true`
  - `preamp_single_loaded: true`
  - `meas_single_loaded: true`
- 或者增加组合态。
- 或者允许配方加载命令从兄弟配方已加载态继续执行。

### 6. 第二次 `SyncTrigger` 在状态机中不自然合法

流程中 `SyncTrigger` 被用于两条链：

- 主发射同步触发
- 预电离同步触发

第一次触发完成后，同步分系统进入 `triggered`。但状态机中的 `SyncTrigger` 没有允许从 `triggered` 再次进入触发倒计时。

影响：

- N47 `预电离同步触发` 在严格状态机执行时会被拒绝。
- 当前仿真系统需要运行时兼容才能让第二次触发通过。

建议修正：

- 拆分触发状态，例如：
  - `main_triggered`
  - `preion_triggered`
- 或者允许 `SyncTrigger` 从 `triggered` 进入 `trigger_countdown`，但增加阶段 guard，限定只用于预电离链路。

### 7. `WarningMusicOn` 在警示已开启时不幂等

N32 `警灯警示音乐开启` 可能使 `控制环境组件` 进入 `warning_active`。随后 N46 `预电离警示音乐开启` 再次下发 `WarningMusicOn`。

但状态机中 `WarningMusicOn` 只允许从：

- `warning_off`
- `idle`

执行。

影响：

- 如果 N32 后声光警示仍然处于 active 状态，N46 会被严格状态机拒绝。
- 业务上“再次确认/开启警示音乐”更像幂等操作。

建议修正：

- 允许 `WarningMusicOn` 从 `warning_active` 和 `music_active` 重入。
- 或者将 N46 改为状态确认类命令，而不是再次触发开启命令。

### 8. 回调 `task_state` 枚举与结果码映射不一致

`interface-contracts.yaml` 的 `status_callback_interface.required_fields.task_state` 枚举中没有 `rejected`。

但 `result_code_mapping.table` 中使用了：

```yaml
task_state: rejected
```

影响：

- 如果按结果码映射生成回调，会违反回调字段枚举。
- 实现方必须临时选择是使用 `rejected`，还是映射为已有合法状态。

建议修正：

- 将 `rejected` 加入回调 `task_state` 枚举。
- 或者把结果码映射中的 `task_state: rejected` 改为已有合法状态，例如 `failed`，并使用 `result_status: rejected` 表达拒绝语义。

## P2 待确认项

以下不一定是错误，但建议在定稿前确认：

- `gxlf-firing-flow.yaml` 中仍有多处 `待确认`，包括超时时间、最终汇合策略等。
- 屏蔽门与人员计数接口仍为 `protocol: tbd`。
- 已废弃的 timed sequence 模板仍保留在文件中，当前更像文档说明。
- 安全联锁 release 条件中有“语音播报本次实验结束”等描述，但未完整建模为可执行节点。

## 当前仿真运行时兼容规则

为了在不修改 YAML 的前提下跑通黄金路径，当前仿真系统加入了以下窄兼容规则：

- 将 `多程放大系统` 视为 `多程放大组件` 的别名。
- 将 `ShotMotionReady` 视为 `MeasShotMotionReady` 的别名。
- 将同步配方命令视为可累积事实，而不是互斥单态。
- 允许同步系统在 `triggered` 后执行第二次 `SyncTrigger`，用于预电离触发链。
- 警示音乐/警灯命令在警示已开启时按幂等成功处理。
- 允许 `TargetDataAnalysis` 在 `TargetRetract` 后执行，假设其分析的是归档数据。

