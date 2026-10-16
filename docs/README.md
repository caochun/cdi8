# Docs

当前总控实现及启动方式见[项目 README](../README.md)，可执行流程位于 `src/main/resources/processes/`，通过 Tango 命令与属性事件连接组件。

文档按用途分组：

- [控制对象状态定义](component-states/README.md)：按 2026-10-15 Excel 清单拆分的各分系统、组件状态定义，保留原表字段和行号。
- `process-control/`：当前主线的集中管控发射流程业务说明；运行流程图以 `src/main/resources/processes/` 下的 BPMN 为准。
- [种子源至多程放大前期准备流程](process-control/种子源至多程放大前期准备流程.md)：四类组件前期准备的顺序、并行、状态契约及外部守卫；对应[运行 BPMN](../src/main/resources/processes/optical_frontend_preparation.bpmn20.xml)，流程图通过总控页面查看。
- [种子源 SCXML](../simulator/gxlf_component_simulator/models/seed-source-state-machine.scxml)：种子源单一层级可执行模型的权威描述，按复合状态和内部状态表达最新清单。
- `reference` 入口仍作为上述种子源单一层级模型的兼容别名；异常作为主状态，当前故障类别由异常下的叶子状态表示，完整故障集合、故障前状态和下电结果保存在上下文。
- [二倍频 SCXML](../simulator/gxlf_component_simulator/models/shg-injection-state-machine.scxml)：二倍频宽带激光注入组件状态机。
- [再生与双程放大 SCXML](../simulator/gxlf_component_simulator/models/preamp-state-machine.scxml)：再生与双程放大组件状态机。
- [多程放大 SCXML](../simulator/gxlf_component_simulator/models/multipass-state-machine.scxml)：多程放大系统状态机。
- `archive/`：历史需求报告、接口拆分、早期推导、会议记录和旧版流程图。

`simulator/gxlf_component_simulator/` 使用统一的 `SCXMLMachine`、`ComponentModel` 和 `ComponentRuntime` 同时执行种子源、二倍频宽带激光注入组件、再生/双程放大组件与多程放大系统；四份 SCXML 和模型元数据统一保存在包内的 `models/` 中。

组件状态图通过[模拟器 GUI](../simulator/README.md)直接加载 SCXML 查看。
