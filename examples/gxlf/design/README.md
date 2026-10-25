# 实验主干设计模型

[gxlf_shot_run_backbone.bpmn20.xml](gxlf_shot_run_backbone.bpmn20.xml) 根据[控制流程业务说明](../../../docs/domain/gxlf/process-control/集中管控系统控制流程业务说明.md)建立新的发次主干，包含初始化、参数接收、发射准备、同步触发、后处理和发次结束。

本目录是非执行设计，`isExecutable="false"`。它只表达 BPMN 的操作编排、并行汇合、设施放行条件和发次证据边界，不直接下发设备动作。

模型复用：

- [设施系统定义](../system/gxlf-facility.system-definition.xml)：设备、端口、拓扑、能力和不变量；
- [设施实例绑定](../system/gxlf-facility-binding.xml)：逻辑实例和束线映射；
- [设施状态投影](../system/gxlf-facility-state-projection.xml)：设施观测、事实和放行谓词；
- [发次运行模型](../../../contracts/shot-run.schema.json)：本发阶段和证据记录。

`fac:requires` 表示设施放行条件，`run:stage` 和 `run:records` 表示本发阶段及证据。设备动作接入和 Flowable 扩展运行时将在后续实现。
