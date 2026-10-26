# 操作流程与实验设施模型约束

流程模型回答“这一次实验要执行哪些操作、按什么顺序、如何等待和失败”；设施系统定义回答“设施由哪些组成部分构成、这些组成部分如何连接、能力和安全约束是什么”。两者不再共享一个综合状态 XML。

两种模型的职责边界见[操作模型与系统模型](operational-and-declarative-models.md)，设施定义源文件为 [`gxlf-facility.system-definition.xml`](../../examples/gxlf/system/gxlf-facility.system-definition.xml)。

## 四层约束

| 层 | 建模位置 | 责任 |
|---|---|---|
| 设备本地约束 | 组件 SCXML | 单设备状态、动作准入、忙闲互斥、反馈和本地故障 |
| 设施组成与能力约束 | `system-definition` | 分系统、设备数量、实例集合、接口、拓扑、能力输入依赖、输出成立条件、结构约束和安全设计说明 |
| 设施现实状态 | `facility-state-projection` | 从 Tango/HTTP 事件和快照计算上电、功能、真空、联锁、充电等事实 |
| 发次操作约束 | BPMN + 发次运行模型 | 本发参数、阶段、动作证据、顺序、并行、等待、超时、人工确认和异常出口 |

流程节点不能替代设备状态证据；设备状态也不能代替本发历史。设施定义中的能力说明“谁能做什么、依赖哪些输入、保持到什么时候”；是否满足依赖要结合本发绑定和实时事实判断。

## 系统定义格式

系统定义使用 `https://cdi8.cn/system-definition/v1` 命名空间：

```xml
<system-definition xmlns="https://cdi8.cn/system-definition/v1"
                   id="gxlf-facility"
                   label="GXLF 激光发射实验设施"
                   version="1">
  <interfaces>...</interfaces>
  <componentTypes>...</componentTypes>
  <instanceSets>...</instanceSets>
  <topology>...</topology>
  <mappings>...</mappings>
  <structuralConstraints>...</structuralConstraints>
  <annotations>...</annotations>
</system-definition>
```

`componentType` 声明设备类型、SCXML 文件引用以及 `ports/port` 和 `capabilities/capability`；数量只在 `instanceSet` 中声明。`topology/paths/path` 包含 `endpoints` 与 `connections`；连接的 `source/target` 显式引用对象和端口。`mappings` 统一声明实例分组及选源范围，绑定文件只引用这些映射。完整格式见[设施 XML 模型约定](facility-xml-models.md)。系统定义不包含发次编号、配方、当前状态或历史结果。

60 条束线、十套预放覆盖及前八束可选二倍频由资源、实例集合和映射共同声明并校验；`structuralConstraints` 补充数量及覆盖断言。尚未实现的联锁处置、独占租约、T0/T+3/T+5 窗口放在 `annotations/notes` 中作为设计说明，不能解释为已执行的安全规则。风淋门范围及建安接口协议等未决项位于 `annotations/issues`。

## 当前迁移边界

旧的综合 `system-state-model` 文件和旧的可执行 GXLF BPMN 已从示例源码中移除。当前 `examples/gxlf/design/` 只保留不可执行的发次主干设计，使用 `fac:requires`、`fac:action`、`run:stage` 和 `run:records` 表达设施放行、设备动作和本发证据。

服务端和工作台识别、校验和预览设施定义、绑定及投影。可执行初始化切片位于 `examples/gxlf/processes/`。旧 `SystemStateModel/SystemStateRuntime`、旧动作契约和场景策略已删除。

可执行动作以 `flowable:delegateExpression="${facilityActions}"` 标记，`fac:action` 必须声明 typeRef、capabilityRef、collectionVar、expectedStateRef。集合中的每个实例拥有独立命令 ID；结果必须关联同一命令和设备来源，且成功到达目标状态。参数携带 shot_id、recipe_ref 和 version。循环再次进入任务会创建新命令，重查结果保持原命令 ID。

`fac:requires` 是 serviceTask 的进入条件，任务执行期间不会持续要求原有状态保持不变（动作本身可能改变它）。运行中会监视设备失联、来源变化、通信中断，并执行动作/流程超时；动作取消和超时不会伪造设备已停止，未决动作保留为 unknown。能力上的 `requirements/inputRequirement maintain="execution"` 已接入动作期间持续依赖监控，输入未知或失效时停止流程推进；其规则定义在系统模型中，详见[设施能力的输入依赖](capability-dependencies.md)。全局持续安全条件、联锁广播及恢复门槛仍须独立实现。

`fac:completion factRef="…" timeout="PT2M"` 或 `guardRef="…"` 是活动完成条件：全部本发命令验收成功后等待指定设施条件为 valid，超时失败。也支持只声明完成条件、不发送动作的等待任务。投影发布事实变化，运行协调器关联当前等待后调用 Flowable `trigger()`，具体契约见[活动完成条件](activity-completion.md)。

`run:stage` 在任务进入时写入阶段；每设备结果自动进入 `shotRun.evidence`。其他证据通过独立 `shotRuns.record` 任务写入；`run:records` 仍仅为设计标注，可执行 BPMN 会拒绝它。普通完成、失败、取消分别写入 completed/failed/cancelled；后两者携带 failure。

初始化切片使用 `simulationFunctionCheckAllowed` 和 `simulationParameterDispatchAllowed`，只用于模拟设备。服务心跳、真空、屏蔽门和人员计数未接入时保持 unknown，不能用“连接正常”替代安全或服务健康证据。
