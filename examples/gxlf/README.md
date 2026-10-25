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

`gxlf_initialization_parameter_dispatch` 曾是第一段可执行技术验证切片，但它依赖已经删除的综合系统状态模型。当前保留该 BPMN 作为业务草稿，待设施状态投影和发次运行模型建立后重新接入；不能把它当作当前可运行入口。

初始化要求组件处于未就绪；上电检查只确认设备均已上电，功能检查通过后才取得业务就绪资格。参数下发后，前期准备流程可以选择种子源、二倍频或两者参与供光。

十四类设备初始化 BPMN 同样处于待迁移状态。新的系统定义按业务名义声明设备数量，但不代表当前工作台已经按 259 台规模完成联调。

[`gxlf-facility.system-definition.xml`](system/gxlf-facility.system-definition.xml) 是当前唯一的设施系统定义源文件。它声明 14 类分系统、60 条束线资源、设备能力、端口、Tango/HTTP/建安接口、光路/同步/控制/联锁拓扑及显式连接，以及数量、覆盖和安全不变量。它不声明设备当前状态，也不包含发次阶段、参数、采集历史或正式发射结果。

[`gxlf-facility-binding.xml`](system/gxlf-facility-binding.xml) 声明逻辑实例、束线映射和外部接口如何由运行环境解析；[`gxlf-facility-state-projection.xml`](system/gxlf-facility-state-projection.xml) 声明设备观测如何组合为设施事实和可复用放行谓词。两者都不保存某一次发射的阶段或结果。

设备的动态状态仍由 `components/` 下的 SCXML 描述；发次运行模型和 BPMN 主干将分别在后续建模。旧的综合状态模型和设施状态投影已经删除，避免继续把设施结构和发次运行语义混在一起。

正式发射完成表示触发链路完成，完整任务仍需等待发射后处理结束。真实分系统、配方参数、束线 / 能量拓扑、发次证据和物理停止策略仍需接入。

工作台只运行一个顶层任务。停止后设备已受理的动作可能继续；下一次运行会拒绝仍有活动命令的设备。组件状态持久化，重复初始化需要先确认设备回到未就绪。

## 验证与业务资料

启动方式见[项目说明](../../README.md)。当前不保留自动化测试源码，验证通过启动 Java Workbench、创建十四类设备环境并手工运行流程完成。

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

使用 Java 17。手工联调应重点检查全体上电屏障、缺设备拒绝、单设备功能失败和系统状态证据；不代表完整发射流程或现场规模已经验收。
