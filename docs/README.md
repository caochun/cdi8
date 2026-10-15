# Docs

当前总控实现及启动方式见[项目 README](../README.md)，可执行流程位于 `src/main/resources/processes/`，通过 Tango 命令与属性事件连接组件。

文档按用途分组：

- `process-control/`：当前主线的集中管控发射流程业务说明和流程图。
- [资格汇合网关](bpmn/qualification-gateway.md)：初始化流程中的自定义菱形业务节点，封装全体等待、当前资格核验和期间失效监控。
- [种子源至多程放大前期准备流程](process-control/种子源至多程放大前期准备流程.md)：四类组件前期准备的顺序、并行、状态契约及外部守卫，附 [BPMN 定义](bpmn/optical-frontend-preparation.bpmn)和[概览图](bpmn/optical-frontend-preparation.svg)。
- `scenarios/`：实验场景、运行过程和业务语境。
- `seed-source-state-machine.scxml`：种子源 v3 状态机的 SCXML 1.0 权威描述。
- `shg-injection-state-machine.scxml`：二倍频宽带激光注入组件状态机的 SCXML 1.0 描述。
- `preamp-state-machine.scxml`：再生与双程放大组件状态机的 SCXML 1.0 描述。
- `multipass-state-machine.scxml`：多程放大系统状态机的 SCXML 1.0 描述。
- `diagrams/`：由状态机模型整理出的最新 Mermaid 源文件与 SVG 图。

`simulator/` 使用统一的 `SCXMLMachine`、`ComponentModel` 和 `ComponentRuntime` 同时执行种子源、二倍频宽带激光注入组件、再生/双程放大组件与多程放大系统；模型元数据直接保存在各自 SCXML 中。

其中 `diagrams/shg-injection-state-machine.mmd` 是二倍频组件 SCXML 的展示图源文件。
`diagrams/preamp-state-machine.mmd` 是再生与双程放大组件 SCXML 的展示图源文件。
`diagrams/multipass-state-machine.mmd` 是多程放大系统 SCXML 的展示图源文件。
- `archive/`：历史需求报告、接口拆分、早期推导和会议过程记录。
