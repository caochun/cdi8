# GXLF 联调示例

本目录保存工作台内置 GXLF 示例的权威模型源文件。平台首次启动时导入模型库，用户编辑产生的草稿和版本不覆盖这里的文件。

## 流程与组件

`bpmn/` 中有六个流程：

| 流程 | 职责 |
|---|---|
| `component_initialization` | 四类组件并行上电检查和功能检查 |
| `parameter_dispatch` | 四类组件参数下发 |
| `optical_frontend_preparation` | 选定光源出光、同步预放重频、预放唤醒、闭环与多程准直 |
| `formal_emission` | 正式发射前置条件、第二次精闭环、能量计清零和触发证据 |
| `post_emission_processing` | 光源及预放数据采集、待机和外部后处理条件 |
| `emission_task_lifecycle` | 依次调用前面五个子流程 |

`scxml/` 中的模型：

| 组件 | model_id | model_version |
|---|---|---:|
| 种子源 | `seed-source-reference-v2` | 2 |
| 二倍频 | `shg-injection-hierarchical-v2` | 2 |
| 再生与双程放大 | `preamp-hierarchical-v3` | 3 |
| 多程放大 | `multipass-hierarchical-v3` | 3 |

这些元数据版本与工作台为不可变 XML 快照生成的版本 ID 分开使用。

## 当前执行范围

初始化要求组件处于未就绪；上电检查只确认设备均已上电，功能检查通过后才取得业务就绪资格。参数下发后，前期准备流程可以选择种子源、二倍频或两者参与供光。

当前动作参数为空对象。诊断、泵浦、测量、开关、同步、靶瞄、吹扫和屏蔽门等尚未建立独立 SCXML 的分系统，由明确标识的演示条件设备提供证据。外部条件默认 false，自动完成动作不会自动满足这些条件。

正式发射完成表示触发链路完成，完整任务仍需等待发射后处理结束。真实分系统、配方参数、束线 / 能量拓扑、发次证据和物理停止策略仍需接入。

工作台只运行一个顶层任务。停止后设备已受理的动作可能继续；下一次运行会拒绝仍有活动命令的设备。组件状态持久化，重复初始化需要先确认设备回到未就绪。

## 验证与业务资料

启动方式见[项目说明](../../README.md)，默认全 Java 联调测试为 [JavaWorkbenchE2eIT](../../server/src/test/java/cn/cdi8/workbench/JavaWorkbenchE2eIT.java)。[gxlf_lifecycle_smoke.py](../../tests/e2e/gxlf_lifecycle_smoke.py) 保留为可选对照脚本。

- [状态定义](../../docs/domain/gxlf/component-states/README.md)
- [前期准备](../../docs/domain/gxlf/process-control/种子源至多程放大前期准备流程.md)
- [正式发射](../../docs/domain/gxlf/process-control/正式发射流程.md)
- [发射后处理](../../docs/domain/gxlf/process-control/发射后处理流程.md)

## 规则实现与固定版本验证

启动资格、完成证据和持续资格检查位于后端 `scenarios/gxlf/`，原 BPMN 的 `${tango.xxx(...)}` 入口由兼容门面保留。工作台发布后的独立流程 key 仍关联对应 GXLF 规则。

使用自动启动的隔离 Java 服务验证版本发布与双光源任务：

```bash
make java-workbench-smoke
```

测试固定内置模型版本，创建全新 Java 多设备环境，明确注入外部许可并完成完整生命周期；不修改日常工作台的数据。
