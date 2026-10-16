# 光纤种子源组件层级状态机（历史 v4 说明）

依据[光纤种子源组件状态定义](component-states/光纤种子源组件.md)修订。本页保留历史 v4 的状态说明；当前权威模型为[单一层级可执行 SCXML](../simulator/gxlf_component_simulator/models/seed-source-state-machine.scxml)，模拟器和 GUI 共用该文件。

本文中的 v4 内容仅用于追溯历史设计；当前实现以单一层级可执行参考 SCXML 为准。

## 状态分层

文档“业务状态”对应 SCXML 复合状态，“关键状态”对应内部状态。复合状态本身也是状态，这里区分父子层级。

| 复合状态 | 内部状态 |
| --- | --- |
| 未就绪 `notReady` | 未上电/离线 `unchecked`、关机正常 `shutdownNormal`；补充上电检查通过待功能检查 `powerOnOk`、功能检查未通过 `checkFailed` |
| 就绪 `ready` | 业务就绪完成 `functionReady`、参数配置正常 `parameterOk`、复位/待机完成 `standbyReset` |
| 出光 `emitting` | 稳定出光正常 `stableEmission`、采集正常 `collectionOk` |
| 异常 `fault` | 故障锁定 `faultLock`、通信异常 `commFault`、执行超时 `timeoutFault` |

原表 11 条定义对应 10 个不同内部状态；正常待机与异常中止共用“复位/待机完成”。两个补充资格状态用于落实先检查上电、再检查功能的既有约束，不是动作执行中状态。上电检查只确认设备是否已经上电，不执行上电。

`functionReady` 的完整标签沿用原表“业务就绪完成（具备倍频注入/旁路判定能力）”。其中能力描述是否适用于种子源，仍需业务确认。

## 初始化、配置和出光

初始进入 `unchecked`，表示尚无可用上电证据，不视为已经正常关机。上电检查通过进入 `powerOnOk`，功能检查通过且就绪资格确认后进入 `functionReady`。功能检查失败进入 `checkFailed`，上电检查失败撤销既有上电证据并回到 `unchecked`。

参数校验生效进入 `parameterOk`，只有此状态接受出光。重配请求使其回到 `functionReady` 并撤销旧配置资格，失败后不能继续使用旧参数出光。稳定出光确认后进入 `stableEmission`，采集完成进入 `collectionOk`；两个子状态都必须持续稳定供光，且保留生效配置版本。

功能检查迁移只定义在 `powerOnOk`、`checkFailed`、`functionReady` 和 `standbyReset` 上，与动作准入一致。`parameterOk` 不响应功能检查结果，不能通过父状态继承取得额外迁移。

再次采集请求使 `collectionOk` 回到 `stableEmission`，清除本次数据资格。采集失败且供光条件仍满足时保留 `stableEmission`，失败保存在任务记录。“采集正常”不自动授予数据有效资格，完整性、范围、时间戳和发次归属等检查仍须独立完成。

## 复位、异常中止与关机

| 确认事实 | 事件 | 目标状态 |
| --- | --- | --- |
| 安全待机完成、资源释放、无未清故障且就绪资格有效 | `standby.completed` | `ready/standbyReset` |
| 异常中止后安全复位完成，且上述就绪条件有效 | `abort.completed` | `ready/standbyReset` |
| 仅安全复位完成，缺少就绪资格 | `standby.completed-unready` / `abort.completed-unready` | `notReady/unchecked` |
| 就绪资格失效且已安全关光 | `qualification.invalidated-safe` | `notReady/unchecked` |
| 安全下电完成 | `shutdown.completed` | 非异常状态进入 `notReady/shutdownNormal` |

`standby_reset` 和 `abort_reset` 成功反馈要求确认“就绪”；缺少资格时使用对应的未就绪确认事件。普通成功回调不能代替完整确认。复位撤销配置资格，下一次出光前必须重新下发参数。关机后再次使用必须重新检查上电和功能。

三个异常子状态均可执行 `shutdown`。成功反馈必须确认业务状态仍为“异常”，任务结果为“安全下电完成”，设备仍保持原异常子状态和完整故障原因。外部直接确认使用 `shutdown_while_fault_confirmed`；非异常状态仍使用 `shutdown_confirmed`。下电成功不能解释为故障恢复。

## 异常分类与恢复

故障、通信异常和执行超时事件直接进入对应子状态。多原因并存时按故障锁定、通信异常、执行超时的优先级显示；完整已确认原因集合保存在 `context.faults`，低优先级事件不覆盖锁定。运行中通信丢失进入通信异常，不推断设备已经下电。

出光期间配置失效、稳定供光条件丢失或未经安全关光确认的资格丢失，进入故障锁定。就绪期间参数失效则回到 `functionReady`，等待重新配置。

仅完整恢复事件 `recovery.completed` 可以退出异常，表示全部阻断原因清除、安全复位已验证。恢复后进入 `unchecked`，重新检查资格，不恢复历史出光或配置。模型不提供部分故障解除事件。

## 执行边界

SCXML 表达层级和迁移；动作绑定、标签、上下文默认值以及 `configuration_valid_states`、`fault_codes` 策略均保存在同一文件中。运行时据此保留或撤销配置资格、累积故障原因。

复合状态到其内部状态的阶段切换显式指定 `type="internal"`，保持父状态，不触发父状态的退出和重新进入。跨复合状态的迁移使用默认 `external`；子状态之间的迁移保留共同父状态。原子状态的自迁移仍会退出、重新进入自身，`internal` 不能改变这一规则。

执行器解析迁移类型，按 SCXML §3.13 计算迁移域以及由内向外的退出序列、由外向内的进入序列。事件历史的 `detail.transition` 记录源节点、迁移类型及这两个序列，可核对父状态是否保持。当前模型不含 `onentry`、`onexit` 动作；轻量执行器仍不执行这些元素中的可执行内容，也不求值 `cond`。

迁移事件表示已经确认的事实。设备反馈适配层负责核验阈值、稳定时长、联锁、资源释放及恢复条件，这些确认条件通过事件契约表达。

命令接受、进度和普通失败保存在任务中，不新增“执行中”状态。运行时检查任务互斥，确认事件可以中断当前任务，被中断任务的迟到反馈不能推进状态。安全命令的主动抢占调度不在本模型内实现。

历史模型标识为 `seed-source-hierarchical-v4`。当前默认模型标识为 `seed-source-reference-v2`；旧模型数据库拒绝直接复用，避免历史状态被解释为新定义。
