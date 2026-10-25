# 操作流程与实验设施模型约束

流程模型回答“这一次实验要执行哪些操作、按什么顺序、如何等待和失败”；设施系统定义回答“设施由哪些组成部分构成、这些组成部分如何连接、能力和安全约束是什么”。两者不再共享一个综合状态 XML。

两种模型的职责边界见[操作模型与系统模型](operational-and-declarative-models.md)，设施定义源文件为 [`gxlf-facility.system-definition.xml`](../../examples/gxlf/system/gxlf-facility.system-definition.xml)。

## 四层约束

| 层 | 建模位置 | 责任 |
|---|---|---|
| 设备本地约束 | 组件 SCXML | 单设备状态、动作准入、忙闲互斥、反馈和本地故障 |
| 设施结构约束 | `system-definition` | 分系统、设备数量、实例集合、资源、接口、拓扑和系统不变量 |
| 设施现实状态 | 后续设施状态投影 | 从 Tango/HTTP 事件和快照计算上电、功能、真空、联锁、充电等事实 |
| 发次操作约束 | BPMN + 发次运行模型 | 本发参数、阶段、动作证据、顺序、并行、等待、超时、人工确认和异常出口 |

流程节点不能替代设备状态证据；设备状态也不能代替本发历史。设施定义中的能力只说明“谁能做什么”，不表示能力当前可用。

## 系统定义格式

系统定义使用 `https://cdi8.cn/system-definition/v1` 命名空间：

```xml
<system-definition xmlns="https://cdi8.cn/system-definition/v1"
                   id="gxlf-facility"
                   name="GXLF 激光发射实验设施"
                   version="1">
  <componentTypes>...</componentTypes>
  <instanceSets>...</instanceSets>
  <topology>...</topology>
  <invariants>...</invariants>
</system-definition>
```

`componentType` 声明设备类型、数量、SCXML 状态机、端口和能力；`instanceSet` 声明实际实例编号模式；`topology` 声明束线、光路、同步、控制和联锁视图，内部的 `connection` 声明具体连接边；`mapping` 声明实例分配；`invariant` 声明数量、覆盖、资源独占和安全输入约束。系统定义不能包含发次编号、配方、当前状态、采集历史或正式发射结果。

系统定义中的不变量包括 60 条束线、10 套预放覆盖关系、8 条可选二倍频束线、屏蔽门/人员/真空安全输入和 T0/T+3/T+5 同步窗口。业务说明中尚未确定的风淋门范围及建安接口协议在模型中保留为未决项。

## 当前迁移边界

旧的综合 `system-state-model` 文件已经删除。现有初始化 BPMN 仍是上一阶段的流程草稿，引用旧的系统状态扩展；它们在发次运行模型和设施状态投影完成后重新接入，不应继续被解释为新的系统定义模型。

服务端和工作台已经能够识别、校验和预览 `system-definition/v1` 文件。下一步应实现设施状态投影，再让 BPMN 在具体任务上引用设施能力和实时事实；发次运行模型单独保存本次实验的阶段和证据。
