# GXLF 联调示例

本目录保存工作台内置 GXLF 示例的权威模型源文件。平台首次启动时导入模型库，用户编辑产生的草稿和版本不覆盖这里的文件。

## 流程与组件

当前可执行入口为 [`processes/gxlf_shot_run_smoke.bpmn20.xml`](processes/gxlf_shot_run_smoke.bpmn20.xml)，覆盖十四类设备上电/功能检查及十二类设备参数接收；按设备实例记录命令、目标状态和本发证据。新的发次主干设计位于 [`design/gxlf_shot_run_backbone.bpmn20.xml`](design/gxlf_shot_run_backbone.bpmn20.xml)，标记为 `isExecutable="false"`，用于表达设施放行条件和发次证据边界。

`components/` 保存十四类设备模型。其中原四类为：

| 组件 | model_id | model_version |
|---|---|---:|
| 种子源 | `seed-source-reference-v2` | 2 |
| 二倍频 | `shg-injection-hierarchical-v2` | 2 |
| 再生与双程放大 | `preamp-hierarchical-v3` | 3 |
| 多程放大 | `multipass-hierarchical-v4` | 4 |

其余十类为泵浦、测量、频率转换、靶瞄、真空、诊断、同步、开关驱动、冷却、控制环境。详见 [设备建模说明](components/建模说明.md)。

## 当前建模范围

设施结构由 [`gxlf-facility.system-definition.xml`](system/gxlf-facility.system-definition.xml) 声明；逻辑实例的运行地址及束线映射引用由 [`gxlf-facility-binding.xml`](system/gxlf-facility-binding.xml) 声明；实时设施事实由 [`gxlf-facility-state-projection.xml`](system/gxlf-facility-state-projection.xml) 声明；本发数据边界由 [`shot-run.schema.json`](../../contracts/shot-run.schema.json) 声明。

新的发次 BPMN 主干只编排初始化、参数接收、发射准备、同步触发、后处理和结束，并通过 `fac:requires`、`fac:action`、`run:stage` 和 `run:records` 引用设施事实及本发证据。完整主干仍是不可执行设计；初始化切片已具备目标状态校验、超时、取消和逐设备证据。正式发射、安全联锁和资源服务仍待接入。

设备动态状态由 `components/` 下的 SCXML 描述。设施当前状态、发次阶段和 BPMN 令牌不再放进同一个综合 XML。

## 验证与业务资料

启动方式见[项目说明](../../README.md)。`make java-workbench-smoke` 执行真实 Java Tango 链路的隔离端到端测试。

- [状态定义](../../docs/domain/gxlf/component-states/README.md)
- [前期准备](../../docs/domain/gxlf/process-control/种子源至多程放大前期准备流程.md)
- [正式发射](../../docs/domain/gxlf/process-control/正式发射流程.md)
- [发射后处理](../../docs/domain/gxlf/process-control/发射后处理流程.md)

## 规则实现与固定版本验证

旧 GXLF 场景策略、综合系统状态运行时和兼容命令入口已经删除。所有设施动作通过 `fac:action` → `FacilityActionRuntime` → Java bridge → JTango → Java SCXML 设备执行。当前端到端验证见 `server/src/test/java/cn/cdi8/workbench/NewChainIT.java`；运行 `make java-workbench-smoke`。

初始化切片在上电检查、功能检查、参数接收三个活动分别声明 `fac:completion`：全部本发动作验收成功后，等待 `allPowerChecked`、`allFunctionReady`、`allParametersReceived` 当前成立。投影变化促使协调器检查并推进，等待超时则失败。详见[活动完成条件](../../docs/architecture/activity-completion.md)。

构建并执行新的初始化切片与异常路径测试：

```bash
make java-workbench-smoke
```

使用 Java 17。手工联调应重点检查全体上电屏障、缺设备拒绝、单设备功能失败和系统状态证据；不代表完整发射流程或现场规模已经验收。

能力输入依赖定义在系统模型中：预放唤醒依赖同步重频，粗/精闭环依赖选定光源及同步，多程准直沿预放输出递归检查选定光源和同步。投影只映射逐设备事实；BPMN 调用能力自动应用依赖。实例按束线和预放组展开，`sourceSelections` 支持前八束选用二倍频。详见[设施能力的输入依赖](../../docs/architecture/capability-dependencies.md)。当前只覆盖这部分光路条件，完整安全和正式发射链路仍待接入。

设施 XML 已统一集合与引用结构，并使用 XSD 加 Java 语义校验。旧格式不兼容，详见[模型约定](../../docs/architecture/facility-xml-models.md)。未修改的内置示例会在启动时更新；用户修改过的旧格式模型需按新格式编辑/导入并重新发布。
