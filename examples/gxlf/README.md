# GXLF 联调示例

本目录保存工作台内置 GXLF 示例的权威模型源文件。平台首次启动时导入模型库，用户编辑产生的草稿和版本不覆盖这里的文件。

## 流程与组件

`bpmn/` 中除下列六个四组件演示流程外，新增了 [`gxlf_system_initialization`](bpmn/gxlf_system_initialization.bpmn20.xml)：对十四类设备全体并行上电检查，全部完成后才并行执行功能检查，分别报告 `powered` 和 `initialized`。

| 流程 | 职责 |
|---|---|
| `gxlf_initialization_parameter_dispatch` | 十四类组件初始化、参数下发及技术验证版发射准备 |

`components/` 替换原 `scxml/`，保存十四类设备模型。其中原四类为：

| 组件 | model_id | model_version |
|---|---|---:|
| 种子源 | `seed-source-reference-v2` | 2 |
| 二倍频 | `shg-injection-hierarchical-v2` | 2 |
| 再生与双程放大 | `preamp-hierarchical-v3` | 3 |
| 多程放大 | `multipass-hierarchical-v4` | 4 |

其余十类为泵浦、测量、频率转换、靶瞄、真空、诊断、同步、开关驱动、冷却、控制环境。详见 [设备建模说明](components/建模说明.md)。多程动作使用 `collimation` 和 `enter_shot`。

这些元数据版本与工作台为不可变 XML 快照生成的版本 ID 分开使用，不作为运行业务状态。

## 当前执行范围

完整实验的[主干设计 BPMN](design/gxlf_experiment_backbone.bpmn20.xml)与系统状态的对应关系已整理在[实验主干与系统状态对应表](../../docs/domain/gxlf/process-control/实验主干与系统状态对应表.md)。主干按业务说明保留并行、屏障、证据等待和异常路径，明确标为非执行设计；不改变下面的实际执行范围。

新增的 `gxlf_initialization_parameter_dispatch` 是第一段可执行技术验证切片：同一份系统状态记录从 `uninitialized` 经过 `powered`、`initialized`、`configuring` 到 `configured`，并对十二类可参数化设备执行参数下发，再执行技术验证版发射准备动作链。它暂时使用当前状态和命令终态作为参数生效判据，并使用模拟控制环境联锁和真空状态假设；生产发次/版本关联、完整安全联锁和双通道证据仍需替换为正式证据协议。

初始化要求组件处于未就绪；上电检查只确认设备均已上电，功能检查通过后才取得业务就绪资格。参数下发后，前期准备流程可以选择种子源、二倍频或两者参与供光。

十四类设备初始化已接入独立 BPMN。工作台选择入口“GXLF 十四类设备初始化”，配置十四类设备实例、应用环境后即可调试。Java 端到端测试每类使用一个实例，验证 28 条命令；不代表业务名义的 259 台规模已经验收。

[`gxlf-experiment.system.xml`](system/gxlf-experiment.system.xml) 声明从初始化到现场释放的 17 个系统状态。命名设备事实、进入条件、持续条件和转换阻断统一表达；完整联锁、双通道同步、本发时序及后处理历史证据不足的转换明确阻断。完整实验主干已经有非执行 BPMN 业务设计；十四类设备的后续全生命周期操作尚未接入可执行 BPMN。六步建模过程见 [GXLF 系统状态模型设计](../../docs/domain/gxlf/process-control/系统状态模型设计.md)。

正式发射完成表示触发链路完成，完整任务仍需等待发射后处理结束。真实分系统、配方参数、束线 / 能量拓扑、发次证据和物理停止策略仍需接入。

工作台只运行一个顶层任务。停止后设备已受理的动作可能继续；下一次运行会拒绝仍有活动命令的设备。组件状态持久化，重复初始化需要先确认设备回到未就绪。

## 验证与业务资料

启动方式见[项目说明](../../README.md)。全 Java 联调测试由 [JavaWorkbenchE2eIT](../../server/src/test/java/cn/cdi8/workbench/JavaWorkbenchE2eIT.java) 覆盖，包含十四类初始化正常/失败路径，以及原四组件 GXLF 演示生命周期。

- [状态定义](../../docs/domain/gxlf/component-states/README.md)
- [前期准备](../../docs/domain/gxlf/process-control/种子源至多程放大前期准备流程.md)
- [正式发射](../../docs/domain/gxlf/process-control/正式发射流程.md)
- [发射后处理](../../docs/domain/gxlf/process-control/发射后处理流程.md)

## 规则实现与固定版本验证

原六流程演示的场景检查位于后端 `scenarios/gxlf/`，原 BPMN 的 `${tango.xxx(...)}` 入口由兼容门面保留。十四类初始化通过通用 `componentCommand` 与 `systemStates` 执行，不依赖原四组件场景策略。

使用自动启动的隔离 Java 服务验证版本发布与双光源任务：

```bash
make java-workbench-smoke
```

使用 Java 17。测试创建隔离 Java 多设备环境，验证全体上电屏障、缺设备拒绝、单设备功能失败和系统状态证据，同时回归原双光源演示；不修改日常工作台的数据。设备测试另覆盖实际充电完成、保护吹扫、真空失效和联锁异常锁存。
