# 实验主干设计模型

[gxlf_experiment_backbone.bpmn20.xml](gxlf_experiment_backbone.bpmn20.xml) 根据[控制流程业务说明](../../../docs/domain/gxlf/process-control/集中管控系统控制流程业务说明.md)建立实验主干，包含阶段内的关键并行汇合、系统转换检查点与异常出口。

本目录是非执行设计，`isExecutable="false"`，不在 server 的 `bpmn/*.bpmn20.xml` 打包目录内。可以导入工作台查看；不能仅改变执行标志就下发设备动作。

该设计文件保留上一阶段的系统状态检查点，当前不再把它解释为设施系统定义。新的设施结构源文件是 [gxlf-facility.system-definition.xml](../system/gxlf-facility.system-definition.xml)；待发次运行模型完成后，主干中的检查点会迁移为设施事实和本发证据引用。

阶段操作、入口/持续条件、完成证据、异常去向、设计假设和未决项见[实验主干与系统状态对应表](../../../docs/domain/gxlf/process-control/实验主干与系统状态对应表.md)。当前可执行的十四类设备初始化仍使用 [gxlf_system_initialization.bpmn20.xml](../bpmn/gxlf_system_initialization.bpmn20.xml)；第一段连续的初始化加参数下发技术验证使用 [gxlf_initialization_parameter_dispatch.bpmn20.xml](../bpmn/gxlf_initialization_parameter_dispatch.bpmn20.xml)。
