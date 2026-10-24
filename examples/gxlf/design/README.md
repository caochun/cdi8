# 实验主干设计模型

[gxlf_experiment_backbone.bpmn20.xml](gxlf_experiment_backbone.bpmn20.xml) 根据[控制流程业务说明](../../../docs/domain/gxlf/process-control/集中管控系统控制流程业务说明.md)建立实验主干，包含阶段内的关键并行汇合、系统转换检查点与异常出口。

本目录是非执行设计，`isExecutable="false"`，不在 server 的 `bpmn/*.bpmn20.xml` 打包目录内。可以导入工作台查看；不能仅改变执行标志就下发设备动作。

系统声明复用 [gxlf-experiment.system.xml](../system/gxlf-experiment.system.xml)，每个转换的 `operation` 关联主干节点。状态谓词和未决转换的 `blocked` 保持原有执行语义，业务缺口没有替换成演示布尔量。

阶段操作、入口/持续条件、完成证据、异常去向、设计假设和未决项见[实验主干与系统状态对应表](../../../docs/domain/gxlf/process-control/实验主干与系统状态对应表.md)。当前可执行的十四类设备初始化仍使用 [gxlf_system_initialization.bpmn20.xml](../bpmn/gxlf_system_initialization.bpmn20.xml)；第一段连续的初始化加参数下发技术验证使用 [gxlf_initialization_parameter_dispatch.bpmn20.xml](../bpmn/gxlf_initialization_parameter_dispatch.bpmn20.xml)。
