# Docs

文档按用途分组：

- `process-control/`：当前主线的集中管控发射流程业务说明和流程图。
- `scenarios/`：实验场景、运行过程和业务语境。
- `seed-source-state-machine.scxml`：种子源 v2 状态机的 SCXML 1.0 权威描述。
- `shg-injection-state-machine.scxml`：二倍频宽带激光注入组件状态机的 SCXML 1.0 描述。
- `preamp-state-machine.scxml`：再生与双程放大组件状态机的 SCXML 1.0 描述。
- `multipass-state-machine.scxml`：多程放大系统状态机的 SCXML 1.0 描述。
- `diagrams/`：由状态机模型整理出的最新 Mermaid 源文件与 SVG 图。

`simulator/` 使用统一的 `SCXMLMachine`、`ComponentModel` 和 `ComponentRuntime` 同时执行种子源、二倍频宽带激光注入组件、再生/双程放大组件与多程放大系统；模型元数据直接保存在各自 SCXML 中。

其中 `diagrams/shg-injection-state-machine.mmd` 是二倍频组件 SCXML 的展示图源文件。
`diagrams/preamp-state-machine.mmd` 是再生与双程放大组件 SCXML 的展示图源文件。
`diagrams/multipass-state-machine.mmd` 是多程放大系统 SCXML 的展示图源文件。
- `archive/`：历史需求报告、接口拆分、早期推导和会议过程记录。
