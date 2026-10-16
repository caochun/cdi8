# SCXML 分系统仿真器

模拟器的 Python 包名为 `gxlf_component_simulator`，统一执行各分系统的 SCXML 状态机。SCXML 是完整模型和状态转换的唯一来源，保存在包内的 `gxlf_component_simulator/models/` 并随安装包分发，GUI 直接加载这些模型绘制状态图。标签、动作绑定、确认事件和上下文默认值都写在 SCXML 标准 `datamodel` 中，由同一个 `SCXMLMachine` 解析、同一个 `ComponentRuntime` 持久化任务和事件。

当前可选模型：

| 名称 | 模型 |
|---|---|
| `seed` | [光纤种子源组件](gxlf_component_simulator/models/seed-source-state-machine.scxml) |
| `shg` | [二倍频宽带激光注入组件](gxlf_component_simulator/models/shg-injection-state-machine.scxml) |
| `preamp` | [再生与双程放大组件](gxlf_component_simulator/models/preamp-state-machine.scxml) |
| `multipass` | [多程放大系统](gxlf_component_simulator/models/multipass-state-machine.scxml) |
| `reference` | `seed` 模型别名（同一份 SCXML） |

任务请求、反馈、超时和故障中断保存在任务记录中，不会添加“执行中”设备状态。设备状态只表示 SCXML 中定义的资格和工作阶段。每个 SQLite 文件只属于一个 SCXML 模型，打开其他模型或旧数据库会明确拒绝。

## 命令行

```bash
PYTHONPATH=simulator python3 -m gxlf_component_simulator.cli --component seed demo
PYTHONPATH=simulator python3 -m gxlf_component_simulator.cli --component preamp demo
PYTHONPATH=simulator python3 -m gxlf_component_simulator.cli --component preamp --db /tmp/preamp.sqlite3 snapshot
```

手动驱动实例：

```bash
PYTHONPATH=simulator python3 -m gxlf_component_simulator.cli --component seed --db /tmp/seed.sqlite3 request power_on_check power-check-1
PYTHONPATH=simulator python3 -m gxlf_component_simulator.cli --component seed --db /tmp/seed.sqlite3 simulate power-check-1 success
PYTHONPATH=simulator python3 -m gxlf_component_simulator.cli --component seed --db /tmp/seed.sqlite3 snapshot
```

安装后脚本名为 `component-sim` 和 `component-panel`。

## HTTP、Tango 和 GUI

本地 HTTP 网关可以创建多个同一 SCXML 模型的独立实例：

```bash
PYTHONPATH=simulator python3 -m gxlf_component_simulator.tango.gateway \
  --component preamp --mode local --devices 01 02 --port 8766
```

可选 GUI 使用同一个 Tango `ComponentDevice` 和同一份 SCXML 模型。状态图由
SCHVIZ（SCION 的 SCXML 可视化组件）在 Qt WebEngine 中渲染，布局、缩放和层级
展开由 SCXML 图本身驱动，不再由 GUI 手工布置连线：

```bash
python3 -m pip install -e './simulator[gui]'
component-panel --component preamp --demo

# 用自定义 SCXML 启动种子源模型（可执行仿真）
PYTHONPATH=simulator python3 -m gxlf_component_simulator.gui \
  --model simulator/gxlf_component_simulator/models/seed-source-state-machine.scxml --demo
```

GUI 将 SCHVIZ 和 SCION 的浏览器资源随 Python 包一起安装，因此运行时不需要网络。
界面状态图、按钮准入和设备快照都由所选 SCXML 派生，不再单独维护组件状态机代码。
使用 `--demo` 启动时，顶部“组件”下拉框会同时列出种子源、二倍频注入、再生与双程放大和多程放大四个已建模组件；切换组件会切换到对应的 Tango 仿真实例、SCXML 状态图、动作集合和事件历史。外部 `--device` 模式只显示当前连接设备对应的模型。

## 验证

```bash
PYTHONPATH=simulator python3 -m unittest discover -s simulator/tests -v
```


## Flowable 总控适配服务

总控使用独立的多类型 Tango 适配服务，单模型 `tango.gateway` 仍用于组件调试：

```bash
PYTHONPATH=simulator python3 -m gxlf_component_simulator.tango.control_bridge --demo
```

默认启动四类真实 Tango 组件各一个，以及显式的演示同步/环境条件设备。启动输出包含各设备 Tango 地址，可让组件 GUI 通过 `component-panel --component preamp --device <Tango地址> --allow-simulation` 连接同一实例。服务通过 `DeviceProxy.subscribe_event` 订阅 `Snapshot CHANGE_EVENT`，将事件以 NDJSON 长连接转发给 Java，不执行 BPMN，也不自动生成完成反馈。

组件 `Snapshot.state_revision` 为持久化事件序号，重复反馈不增加版本，重启后继续保留。初始化资格网关使用这个字段区分旧事件与资格建立后的失效事件。

组件自身继续使用 SCXML 与 SQLite；演示条件设备独立于 SCXML，初始条件均为 false。更多接口、反馈操作和业务范围见[总控说明](../README.md)。


## 上电检查与功能检查（2026-10-15）

四类组件统一执行 `power_on_check → function_check → parameter_dispatch`。上电检查确认所辖设备均已上电，不执行上电操作；功能检查测试设备功能是否正常。二倍频、预放和多程沿用 `notReady/powerOnOk → ready/functionReady → ready/parameterOk`；种子源参考模型对应为 `notReady/powerOnChecked → ready/qualified → ready/configured`。未知反馈不代表检查通过。

`power_on_self_test`、`selfTestOk`、种子源的 `configIdle/configOk` 已替换。种子源当前模型为 `seed-source-reference-v2`，二倍频为 `shg-injection-hierarchical-v2`，再生与双程放大为 `preamp-hierarchical-v3`，多程放大为 `multipass-hierarchical-v3`。旧模型数据库因状态或资格语义变化拒绝复用，不自动迁移历史结果。默认 CLI、GUI、组件网关和总控演示适配服务均使用带模型版本的新数据库路径，原文件保留；显式指定旧路径时请改用新路径并重新完成初始化。

## 种子源状态分层

种子源按[组件状态定义](../docs/component-states/光纤种子源组件.md)建模：业务状态是 SCXML 复合状态，关键状态是其内部状态。四个复合状态为“未就绪”“就绪”“出光”“异常”。明确提供“稳定出光正常”“采集正常”“复位/待机完成”“关机正常”等状态；“上电检查通过，待功能检查”和“功能检查未通过”是用于保持检查顺序的补充资格状态。

`standby_reset` 和 `abort_reset` 成功且就绪资格得到复核时，种子源进入 `ready/standbyReady`，其他组件使用各自的待机状态；复位后重新下发参数才可出光。`shutdown` 成功进入 `notReady/shutdownNormal`，再次使用前重新检查。

出光父状态中的两个子状态都要求持续稳定供光。采集失败记录为任务失败，仍保持出光；“采集正常”不自动授予数据有效资格。异常子状态按故障锁定、通信异常、执行超时的优先级显示，完整原因集合保存在上下文，全部原因清除并完成安全复位确认后才退出异常。详细事件契约见 [SCXML 注释](gxlf_component_simulator/models/seed-source-state-machine.scxml)和[模型说明](../docs/seed-source-state-machine-draft.md)。

功能检查迁移只出现在允许检查的子状态。父状态内的公共阶段切换使用 `type="internal"`，执行器计算进入和退出序列并记录在事件历史的 `detail.transition` 中。异常状态可执行 `shutdown`，完成后仍须确认“异常”，保持原故障；直接确认使用 `shutdown_while_fault_confirmed`，关机不自动解除锁定。
