# 操作模型与系统模型

实验控制至少要回答三个不同问题：设施由什么组成、设施现在处于什么现实状态、某一次实验接下来要做什么。它们分别由系统定义模型、设施状态投影、发次运行模型和 BPMN 协作表达。

| 模型 | 回答的问题 | 主要内容 |
|---|---|---|
| 系统定义模型（`.system-definition.xml`） | 设施是什么？ | 分系统、设备类型、实例数量、能力及其输入依赖、输出条件、接口、拓扑、结构约束和安全设计说明 |
| 设备模型（SCXML） | 单台设备如何工作？ | 本地状态、动作准入、反馈、故障和恢复 |
| 设施状态投影 | 设施现在是什么状态？ | 从 Tango/HTTP 观测计算上电、功能、真空、联锁、充电等事实 |
| 发次运行模型 | 这一次实验进行到哪里？ | 发次编号、配方、阶段、任务证据、采集历史、触发结果和异常 |
| 操作模型（BPMN） | 这一次实验如何推进？ | 顺序、并行、汇合、等待、超时、人工确认和异常出口 |

当前 GXLF 设施定义位于 [`gxlf-facility.system-definition.xml`](../../examples/gxlf/system/gxlf-facility.system-definition.xml)。它不描述设备当前状态，也不包含某一次发射的参数或历史。设备 SCXML 位于 [`examples/gxlf/components`](../../examples/gxlf/components/)，业务依据位于 [`集中管控系统控制流程业务说明.md`](../domain/gxlf/process-control/集中管控系统控制流程业务说明.md)。

设施定义之后，实例解析由 [`gxlf-facility-binding.xml`](../../examples/gxlf/system/gxlf-facility-binding.xml) 声明，实时设施事实由 [`gxlf-facility-state-projection.xml`](../../examples/gxlf/system/gxlf-facility-state-projection.xml) 声明。前者不伪造现场 Tango 名称，后者不保存发次阶段。

系统定义模型声明“有哪些能力”“如何连接”，以及“执行能力时哪些输入必须保持有效”。规则可以引用动态事实，但不保存当前状态。例如，它声明泵浦具有 `charge` 和 `trigger-prepare` 能力，60 条束线具有固定的组件映射，并声明屏蔽门、人员计数和真空是安全联锁输入。它不声明“先充电再触发”，也不保存“本发已经充电”的事实；前者属于 BPMN，后者属于设施状态投影和发次证据。

设备动作是否能够接受，由设备 SCXML 决定；设施事实由状态投影根据实时观测计算；跨设备输入依赖由运行协调器按系统定义和本发实际选路检查并持续监控；流程额外的进入与完成条件由 BPMN 声明。能力输入及输出契约见[设施能力的输入依赖](capability-dependencies.md)。这样可以避免把静态结构、实时物理状态和本发历史混成一个“当前状态”字段。

BPMN 通过 `fac:requires` 声明进入条件，通过 `fac:completion` 声明完成条件。投影发布事实变化，由运行协调器结合本发命令结果唤醒等待活动；投影本身不调用流程引擎。完成条件的等待、超时和证据语义见[活动完成条件](activity-completion.md)。

SysML 可以作为系统定义的工程设计语言：用 block/part 表示组成，用 port/connect 表示接口和拓扑，用 requirement/constraint 表示数量、束线覆盖和安全约束。仓库中的受限 XML 是运行时校验格式；后续如采用 SysML，应由 SysML 生成该 XML，而不是让 BPMN 直接解析完整 SysML。

旧的综合 `system-state-model` 文件已经删除。设施状态投影和发次运行模型已经有声明文件及基础解析/运行骨架；初始化切片已接入终态与逐设备证据；外部安全观测和完整发射闭环仍需继续实现，不能通过恢复旧综合模型来替代。

设施模型的集合层次、引用命名、统一映射、条件组合及 XSD/语义校验见[设施 XML 模型约定](facility-xml-models.md)。安全和时序设计说明不等于运行时已执行规则。
