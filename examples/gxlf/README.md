# GXLF 联调示例

本目录保存工作台内置 GXLF 示例的权威模型源文件。平台首次启动时导入模型库，用户编辑产生的草稿和版本不覆盖这里的文件。

## 流程与组件

当前不再打包旧的可执行 GXLF BPMN。新的发次主干设计位于 [`design/gxlf_shot_run_backbone.bpmn20.xml`](design/gxlf_shot_run_backbone.bpmn20.xml)，标记为 `isExecutable="false"`，用于表达设施放行条件和发次证据边界。

`components/` 保存十四类设备模型。其中原四类为：

| 组件 | model_id | model_version |
|---|---|---:|
| 种子源 | `seed-source-reference-v2` | 2 |
| 二倍频 | `shg-injection-hierarchical-v2` | 2 |
| 再生与双程放大 | `preamp-hierarchical-v3` | 3 |
| 多程放大 | `multipass-hierarchical-v4` | 4 |

其余十类为泵浦、测量、频率转换、靶瞄、真空、诊断、同步、开关驱动、冷却、控制环境。详见 [设备建模说明](components/建模说明.md)。

## 当前建模范围

设施结构由 [`gxlf-facility.system-definition.xml`](system/gxlf-facility.system-definition.xml) 声明；逻辑实例和束线映射由 [`gxlf-facility-binding.xml`](system/gxlf-facility-binding.xml) 声明；实时设施事实由 [`gxlf-facility-state-projection.xml`](system/gxlf-facility-state-projection.xml) 声明；本发数据边界由 [`shot-run.schema.json`](../../contracts/shot-run.schema.json) 声明。

新的发次 BPMN 主干只编排初始化、参数接收、发射准备、同步触发、后处理和结束，并通过 `fac:requires`、`run:stage` 和 `run:records` 引用设施事实及本发证据。设备动作接入和 Flowable 扩展运行时仍待完成。

设备动态状态由 `components/` 下的 SCXML 描述。设施当前状态、发次阶段和 BPMN 令牌不再放进同一个综合 XML。

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
