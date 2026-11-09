# 文档导航

当前主线是 **设备 → 快照与观测验证 → 设施事实 → 系统监督与命令准入**。监督及此前的运行职责在一个 Java 后端中实现；Flowable 与工作台在另一个 Java 后端中实现。

- [项目入口与启动](../README.md)：模块、服务、构建和启动命令。
- [模块命名与代码组织](architecture/modules.md)：模块依赖、Java 包、适配器与模拟器边界。
- [监督运行边界](architecture/facility-runtime.md)：独立监督、权威准入、持久化与恢复。
- [观测、事实、证据与监督的分层基线](architecture/evidence-layer-boundaries.md)：各层定义来源、当前 GXLF 模型盘点和后续建模规则。
- [GXLF 事实完整性矩阵](architecture/fact-integrity-matrix.md)：逐事实记录来源、成立条件、`unknown` 传播、流程 guard 和监督用途。
- [设施事实目录 v1](architecture/fact-catalog-v1.md)：冻结当前设施事实、组合事实、流程资格和暂缓接入项。
- [核心代码与验证范围](development/validation.md)：链路上的实现位置、验证顺序和未完成接口。
- [工作台架构](architecture/workbench.md)与[工作台操作](development/workbench.md)：模型版本、模拟环境和流程调试。
- [设施 XML 模型](architecture/facility-xml-models.md)与[自定义模型契约](architecture/custom-model-contracts.md)：模型结构、引用和校验。
- [能力输入依赖](architecture/capability-dependencies.md)、[完成条件](architecture/activity-completion.md)、[阶段保持](architecture/stage-hold.md)：保留的流程扩展。
- [Java SCXML / Tango 模拟设备](architecture/java-device-simulator.md)与[接口契约](interfaces/README.md)：设备执行、快照、命令和事件。
- [GXLF 示例](../examples/gxlf/README.md)：十四类组件、三个可执行验证流程和一个不可执行目标设计。
- [GXLF 业务依据](domain/gxlf/process-control/集中管控系统控制流程业务说明.md)、[状态定义](domain/gxlf/component-states/README.md)、[设施定义说明](domain/gxlf/process-control/系统定义模型设计.md)、[发次记录说明](domain/gxlf/process-control/发次运行模型设计.md)。

[归档目录](../archive/README.md)保存历史方案、NIF 调研和独立展示原型。可执行模型源文件集中在 `examples/`，用户模型与运行历史保存在 `data/`；业务需求和不可执行设计不等于当前已经完成的执行范围。
