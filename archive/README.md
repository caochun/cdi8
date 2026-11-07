# 历史资料与独立原型

本目录保存历史讨论、被替代的设计和独立展示原型，不参与 Maven 构建、模型库导入或运行时加载。文中的“当前实现”、验证结果和待办均属于形成时的背景，不能作为现行契约或验收依据。

| 归档内容 | 保存位置 | 当前入口 |
|---|---|---|
| 监督概念稿、约束登记、业务复审和早期职责划分 | [架构讨论](docs/architecture) | [监督运行边界](../docs/architecture/facility-runtime.md) |
| 早期综合系统状态、流程映射及阶段解读 | [GXLF 历史设计](docs/domain/gxlf/process-control) | [业务原文](../docs/domain/gxlf/process-control/集中管控系统控制流程业务说明.md)、[目标 BPMN](../examples/gxlf/design/gxlf_shot_run_target.bpmn20.xml) |
| 前一版不可执行发次主干 | [旧 BPMN](examples/gxlf/design/gxlf_shot_run_backbone.bpmn20.xml) | [当前设计说明](../examples/gxlf/design/README.md) |
| NIF/ICCS 调研与 Broadview 静态展示 | [调研说明](docs/NIF_ICCS控制系统方案详解.md)、[激光链路](docs/broadview/laser-chain.html)、[诊断展示](docs/broadview/diagnostics.html) | 独立参考资料 |
| 历史实施清单 | [todo.md](todo.md) | [当前范围与验证](../docs/development/validation.md) |

现行代码围绕“设备 → 快照与观测验证 → 设施事实 → 系统监督与命令准入”维护，同时保留工作台、Flowable、BPMN 及其必要依赖。归档只移动仓库资料，不迁移或清理 `data/` 中的用户模型、版本和运行证据。
