# 操作模型与系统模型

实验控制至少要回答三个不同问题：设施由什么组成、设施现在处于什么现实状态、某一次实验接下来要做什么。它们分别由系统定义模型、设施状态投影、发次运行模型和 BPMN 协作表达。

| 模型 | 回答的问题 | 主要内容 |
|---|---|---|
| 系统定义模型（`.system-definition.xml`） | 设施是什么？ | 分系统、设备类型、实例数量、能力、接口、拓扑和系统不变量 |
| 设备模型（SCXML） | 单台设备如何工作？ | 本地状态、动作准入、反馈、故障和恢复 |
| 设施状态投影 | 设施现在是什么状态？ | 从 Tango/HTTP 观测计算上电、功能、真空、联锁、充电等事实 |
| 发次运行模型 | 这一次实验进行到哪里？ | 发次编号、配方、阶段、任务证据、采集历史、触发结果和异常 |
| 操作模型（BPMN） | 这一次实验如何推进？ | 顺序、并行、汇合、等待、超时、人工确认和异常出口 |

当前 GXLF 设施定义位于 [`gxlf-facility.system-definition.xml`](../../examples/gxlf/system/gxlf-facility.system-definition.xml)。它不描述设备当前状态，也不包含某一次发射的参数或历史。设备 SCXML 位于 [`examples/gxlf/components`](../../examples/gxlf/components/)，业务依据位于 [`集中管控系统控制流程业务说明.md`](../domain/gxlf/process-control/集中管控系统控制流程业务说明.md)。

设施定义之后，实例解析由 [`gxlf-facility-binding.xml`](../../examples/gxlf/system/gxlf-facility-binding.xml) 声明，实时设施事实由 [`gxlf-facility-state-projection.xml`](../../examples/gxlf/system/gxlf-facility-state-projection.xml) 声明。前者不伪造现场 Tango 名称，后者不保存发次阶段。

系统定义模型只声明“有哪些能力”和“哪些连接必须存在”。例如，它声明泵浦具有 `charge` 和 `trigger-prepare` 能力，60 条束线具有固定的组件映射，并声明屏蔽门、人员计数和真空是安全联锁输入。它不声明“先充电再触发”，也不保存“本发已经充电”的事实；前者属于 BPMN，后者属于设施状态投影和发次证据。

设备动作是否能够接受，由设备 SCXML 决定；设施条件是否满足，由状态投影根据实时观测计算；某次流程能否放行，则由 BPMN 同时检查发次运行模型和设施状态投影。这样可以避免把静态结构、实时物理状态和本发历史混成一个“当前状态”字段。

SysML 可以作为系统定义的工程设计语言：用 block/part 表示组成，用 port/connect 表示接口和拓扑，用 requirement/constraint 表示数量、束线覆盖和安全约束。仓库中的受限 XML 是运行时校验格式；后续如采用 SysML，应由 SysML 生成该 XML，而不是让 BPMN 直接解析完整 SysML。

旧的综合 `system-state-model` 文件已经删除。设施状态投影和发次运行模型需要在系统定义稳定后分别实现，不能通过恢复旧综合模型来替代。
