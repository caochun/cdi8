# Python SCXML 分系统仿真器（历史实现）

本目录保留历史 Python 协议实现。工作台已使用 Java bridge、Java 模拟环境管理和 Java 多设备服务，构建、启动及全链路测试均不依赖本目录。当前设备模型已统一迁移到 [`examples/gxlf/components/`](../examples/gxlf/components/)，原 `scxml/` 四文件已删除；本目录的资源路径和动作绑定尚未适配新模型，不作为当前版本的运行或验证入口。日常使用见[项目说明](../README.md)，模块边界见[架构说明](../docs/architecture/workbench.md)。

以下保留旧实现的说明和命令记录，不代表对当前十四类模型的兼容承诺。

模拟器的 Python 包名为 `gxlf_component_simulator`，统一执行各分系统的 SCXML 状态机。SCXML 模型的权威源码位于仓库的 `examples/gxlf/scxml/`，运行时直接加载这些模型。wheel / sdist 会将模型打包至包内 `gxlf_component_simulator/models/`，支持脱离仓库安装。标签、动作绑定、确认事件和上下文默认值都写在 SCXML 标准 `datamodel` 中，由同一个 `SCXMLMachine` 解析、同一个 `ComponentRuntime` 持久化任务和事件。

当前可选模型：

| 名称 | 模型 |
|---|---|
| `seed` | [光纤种子源组件](../examples/gxlf/scxml/seed-source-state-machine.scxml) |
| `shg` | [二倍频宽带激光注入组件](../examples/gxlf/scxml/shg-injection-state-machine.scxml) |
| `preamp` | [再生与双程放大组件](../examples/gxlf/scxml/preamp-state-machine.scxml) |
| `multipass` | [多程放大系统](../examples/gxlf/scxml/multipass-state-machine.scxml) |
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

安装后脚本名为 `component-sim` 和 `control-tango-bridge`。

## Web 工作台调试

设备操作和状态图查看统一使用[Web 工作台](../README.md)，命令行 `component-sim` 保留用于模型调试。浏览器绘图资源位于 `frontend/public/vendor/schviz/`，Python 服务及安装包只需要组件模型。

安装 Tango 依赖并启动演示适配服务：

```bash
python3 -m pip install -e './simulator[tango]'
PYTHONPATH=simulator python3 -m gxlf_component_simulator.tango.control_bridge --demo
```

演示设备默认等待明确的成功或失败反馈。需要动作自动完成时，添加 `--auto-complete --completion-delay-ms 500`；该设置不会自动满足外部条件。需要全新演示状态时，可显式添加 `--reset-data`，它只清理当前所选演示实例的状态文件。

启动 Java 工作台后，可在“模型开发”中创建独立试运行，在“联调配置”中设置组件实例，在“运行调试”中发送反馈、注入确认事件和查看状态图。设备命令、查询和事件订阅统一由 `tango.control_bridge` 提供。

## 验证

```bash
PYTHONPATH=simulator python3 -m unittest discover -s simulator/tests -v
```


## Flowable 总控适配服务

总控使用独立的多类型 Tango 适配服务，可分别设置各类演示实例的数量：

```bash
PYTHONPATH=simulator python3 -m gxlf_component_simulator.tango.control_bridge --demo \
  --seed-count 1 --shg-count 1 --preamp-count 10 --multipass-count 60
```

默认启动四类真实 Tango 组件各一个，以及显式的演示同步/环境条件设备；所有组件实例由一个 Tango Device Server 进程承载。服务通过 `DeviceProxy.subscribe_event` 订阅 `Snapshot CHANGE_EVENT`，将事件以 NDJSON 长连接转发给 Java，不执行 BPMN。使用 `--auto-complete` 时，演示设备才会在延迟后自动生成成功反馈。

真实 Tango 环境使用 `--devices devices.json` 接入。此时适配服务不会创建 Device Server，`devices.json` 中每一条设备映射就是一个实际实例；实例数量由配置条目决定，不再使用 `--*-count` 参数。

组件 `Snapshot.state_revision` 为持久化事件序号，重复反馈不增加版本，重启后继续保留。总控使用组件快照和命令 ID 核验初始化分支的当前状态；该字段用于事件去重和重启后的状态补读。

组件自身继续使用 SCXML 与 SQLite；演示条件设备独立于 SCXML，初始条件均为 false。更多接口、反馈操作和业务范围见[总控说明](../README.md)。


## 上电检查与功能检查（2026-10-15）

四类组件统一执行 `power_on_check → function_check → parameter_dispatch`。上电检查确认所辖设备均已上电，不执行上电操作；功能检查测试设备功能是否正常。二倍频、预放和多程沿用 `notReady/powerOnOk → ready/functionReady → ready/parameterOk`；种子源参考模型对应为 `notReady/powerOnChecked → ready/qualified → ready/configured`。未知反馈不代表检查通过。

`power_on_self_test`、`selfTestOk`、种子源的 `configIdle/configOk` 已替换。种子源当前模型为 `seed-source-reference-v2`，二倍频为 `shg-injection-hierarchical-v2`，再生与双程放大为 `preamp-hierarchical-v3`，多程放大为 `multipass-hierarchical-v3`。旧模型数据库因状态或资格语义变化拒绝复用，不自动迁移历史结果。默认 CLI 和总控演示适配服务均使用带模型版本的新数据库路径，原文件保留；显式指定旧路径时请改用新路径并重新完成初始化。

## 种子源状态分层

种子源按[组件状态定义](../docs/domain/gxlf/component-states/光纤种子源组件.md)建模：业务状态是 SCXML 复合状态，关键状态是其内部状态。四个复合状态为“未就绪”“就绪”“出光”“异常”。明确提供“稳定出光正常”“采集正常”“复位/待机完成”“关机正常”等状态；“上电检查通过，待功能检查”和“功能检查未通过”是用于保持检查顺序的补充资格状态。

`standby_reset` 和 `abort_reset` 成功且就绪资格得到复核时，种子源进入 `ready/standbyReady`，其他组件使用各自的待机状态；复位后重新下发参数才可出光。`shutdown` 成功进入 `notReady/shutdownNormal`，再次使用前重新检查。

出光父状态中的两个子状态都要求持续稳定供光。采集失败记录为任务失败，仍保持出光；“采集正常”不自动授予数据有效资格。异常子状态按故障锁定、通信异常、执行超时的优先级显示，完整原因集合保存在上下文，全部原因清除并完成安全复位确认后才退出异常。详细事件契约见 [SCXML 注释](../examples/gxlf/scxml/seed-source-state-machine.scxml)。

功能检查迁移只出现在允许检查的子状态。父状态内的公共阶段切换使用 `type="internal"`，执行器计算进入和退出序列并记录在事件历史的 `detail.transition` 中。异常状态可执行 `shutdown`，完成后仍须确认“异常”，保持原故障；直接确认使用 `shutdown_while_fault_confirmed`，关机不自动解除锁定。
