# 系统监督约束登记表与场景转移矩阵

本文是[系统合法状态与监督契约](system-supervision-contract.md)的第一份可审查登记表。它把业务说明中的整体约束映射到当前 GXLF 组件 SCXML，并给出以后使用 [`device-simulator`](../../device-simulator/) 验证监督层的场景。

本文只登记监督层需要观察和判断的语义，不把 SCXML 状态名直接当成现场安全事实。状态名、确认事件和实际物理含义之间仍需要经过接口和业务确认。

## 登记规则

每条约束都要回答六个问题：

1. 什么实际证据使约束开始适用？
2. 初始覆盖是否已经完成？
3. 适用期间哪些事实必须持续有效？
4. `unknown`、失效和来源冲突分别如何处理？
5. 什么独立证据可以解除约束？
6. 流程取消、监督重启或下一发开始时，约束是否仍然存在？

约束登记使用以下状态：

```text
inactive → activating → active-valid
                         ↘ active-unknown / active-violated
                                   → recovery-required → released
```

`active-unknown` 表示不能证明仍然合法，不能继续放行受保护动作；`active-violated` 表示已经确认发生过失效，恢复为正常观测不能抹掉本轮事件。

## 当前 SCXML 可用证据

下表只列出与第一批监督场景直接相关的组件。`state` 是模型当前叶子状态，`outer` 是模型中的外层工作状态；最终监督事实应由状态、确认事件、来源和新鲜度共同决定。

| 组件模型 | 可观察状态或事件 | 可用于监督的初步语义 |
|---|---|---|
| `pump-state-machine.scxml` | `chargeCapable`、`chargePrepared`、`charging`、`chargingActive`、`charged`、`chargeComplete`、`armed`、`triggerReady`；`charging_started`、`charging_completed`、`protection_completed`、`trigger_window_invalidated` | 泵浦充电、储能、触发准备和保护动作的候选证据；`protection_completed` 是否等于储能消除仍需确认 |
| `switch-driver-state-machine.scxml` | `charged`、`chargeComplete`、`armed`、`triggerReady`、`dischargeFault`；`safe_discharge_completed`、`discharge_fault_confirmed` | 开关储能、触发准备和安全放电的候选证据 |
| `synchronization-state-machine.scxml` | `repeatMode`、`repeatConfigured`、`singleMode`、`singleConfigured`、`triggerSent`、`countVerified` | 重频/单次配方和同步触发窗口的候选证据；T0、T+3、T+5 的实时语义仍需专门验证 |
| `seed-source-state-machine.scxml` | `configured`、`emitting`、`stableEmission`、`collectionOk`、`standbyReady`；`emission_lost_confirmed` | 光源配置、出光稳定、出光丢失和返回待机的候选证据 |
| `preamp-state-machine.scxml` | `wakeup`、`wakeupReady`、`coarseLoopOk`、`fineLoopOk`、`energyMeterCleared`、`energyDataCollected`、`standbyReady` | 预放唤醒、粗/精闭环、能量计清零、采集和待机的候选证据 |
| `multipass-state-machine.scxml` | `collimationReady`、`shotReady`；`collimation_invalidated`、`shot_state_invalidated` | 光路准直和进入打靶状态的候选证据；60 个实例必须按实际参与集合聚合 |
| `target-alignment-state-machine.scxml` | `simulationPositioned`、`beamGuideComplete`、`targetReset`、`shotReady`、`targetRetracted`、`analysisComplete` | 模拟靶定位、光束引导、靶复位、进入打靶、收靶和分析的候选证据 |
| `control-environment-state-machine.scxml` | `areaCleared`、`clearanceComplete`、`interlocked`、`interlockEstablished`、`interlockTripped`；`interlock_tripped`、`door_unexpected_open`、`clearance_invalidated` | 清场、门锁闭、联锁建立和联锁触发的候选证据；人员计数和门控接口仍是外部事实 |
| `cooling-state-machine.scxml` | `purging`、`purgeActive`、`purgeComplete`；`purge_completed` | 片放吹扫进行中和结束的候选证据 |
| `vacuum-state-machine.scxml` | `vacuumHolding`、`vacuumStable`、`repressurizing`、`pressureRestored`、`atmospheric`；`holding_failed`、`pressure_restored` | 靶室真空稳定、失效、复压和恢复的候选证据 |

当前设备模拟器已经能够从这些 SCXML 建立隔离组件实例，返回快照和历史，并通过动作、模拟反馈或事件报告推进模型。它可以验证“模型状态怎样变化”，但不能单独证明某个状态名就具有现场安全含义。

## 监督约束登记表

### SV-01 联锁会话

| 项目 | 登记内容 |
|---|---|
| 适用范围 | 从清场和门锁闭开始，到控制器确认安全释放结束；可覆盖多个流程任务和多个发次操作 |
| 启用证据 | `areaCleared`、人员计数归零、屏蔽门锁闭、真空输入正常，随后控制器确认 `interlockEstablished` 或等价的 armed 状态 |
| 初始覆盖 | 必须确认所有纳入联锁的物理输入均有当前有效观测；缺少任一输入时为 `activating` 或 `active-unknown` |
| 保持要求 | 门禁正常、人员计数持续为零、真空正常、控制器无触发、相关服务健康 |
| 未知处理 | 阻止所有受保护动作；已进入危险工作状态时锁存并进入恢复路径 |
| 失效处理 | 立即撤销流程推进资格；按已确认的接口发起广播终止、报警和其他处置 |
| 解除证据 | 控制器确认释放，警灯/警报和语音结束条件成立，屏蔽门解除锁定；具体顺序待确认 |
| 流程取消 | 不释放约束；监督继续观察 |
| 模拟验证 | 控制环境状态机加真空状态机和外部 `personnelCountZero` 条件；注入 `interlock_tripped`、门意外打开、真空保持失败和观测失联 |

### SV-02 泵浦储能

| 项目 | 登记内容 |
|---|---|
| 适用范围 | 泵浦充电、已充电、待触发及实际储能尚未消除的期间；主发射和预电离分别建立独立约束实例 |
| 启用证据 | 泵浦动作受理后观察到 `charging`/`chargingActive`/`charged`/`armed`/`triggerReady`，或泵浦报告 `charging_started` |
| 初始覆盖 | 必须同时确认联锁会话、服务健康和泵浦来源未发生变化 |
| 保持要求 | 联锁正常、泵浦服务健康、储能事实未过期、没有冲突来源 |
| 未知处理 | 不能继续触发、开门或释放资源；已储能时保留未决物理动作 |
| 失效处理 | 根据泵浦所处阶段选择紧急停充、紧急泄放或紧急终止；普通流程取消不能替代这些动作 |
| 解除证据 | 泵浦确认实际储能消除；`protection_completed` 是否足够需要现场确认，不能只因进入 `standbyReady` 就推断安全 |
| 流程取消 | 约束继续存在，直到储能消除证据成立 |
| 模拟验证 | 泵浦模型动作 `charge`、`trigger_prepare`、`standby_reset`、`abort_reset`，注入充电完成、保护完成、超时和通信失联 |

### SV-03 开关储能

| 项目 | 登记内容 |
|---|---|
| 适用范围 | 开关充电完成至安全放电确认期间 |
| 启用证据 | `charged`/`armed`/`triggerReady` 或充电完成反馈 |
| 保持要求 | 联锁正常、开关服务健康、触发窗口和来源有效 |
| 未知处理 | 阻止触发和开门；保留未决储能 |
| 失效处理 | 进入安全放电或人工恢复路径；不能把流程结束当成放电完成 |
| 解除证据 | `safe_discharge_completed` 或现场确认的等价证据；`discharge_fault_confirmed` 进入恢复路径 |
| 模拟验证 | 开关模型动作 `charge`、`trigger_prepare`、`standby_reset`，注入安全放电成功和放电故障 |

### SV-04 光学工作

| 项目 | 登记内容 |
|---|---|
| 适用范围 | 光源出光、预放唤醒、闭环和多程工作态持续存在期间 |
| 启用证据 | 种子源 `stableEmission`，预放进入 `wakeupReady` 或闭环状态，或相关启动动作已实际开始 |
| 保持要求 | 出光稳定、预放状态有效、相关服务健康、联锁正常；参与多程实例的状态符合本发束线配置 |
| 未知处理 | 阻止准直、光束引导和受保护动作；已经启用时记录光学工作异常 |
| 失效处理 | 停止流程继续推进，必要时执行关光/待机；是否需要独立现场关光接口待确认 |
| 解除证据 | 所选光源确认待机或关光，预放确认退出工作态，相关启动动作结束 |
| 模拟验证 | 种子源 `seed_source_emit`/`standby_reset`，预放 `preamp_wakeup`/`standby_reset`，注入 `emission_lost_confirmed` 和配置失效 |

### SV-05 准直操作

| 项目 | 登记内容 |
|---|---|
| 适用范围 | 参与束线的多程放大实例执行准直，直到全部实例确认准直完成或进入明确失败状态 |
| 启用证据 | 任一参与多程实例收到 `collimation` 并进入动作中状态 |
| 保持要求 | 所选光源和预放工作态有效、片放吹扫停止、联锁正常、所有参与实例服务健康 |
| 未知处理 | 不能把已完成的准直结果自动当作持续有效；阻止后续光束引导 |
| 失效处理 | 保留具体实例和命令证据，按能力失败或系统异常处理 |
| 解除证据 | 全部参与实例确认 `collimationReady`，或全部完成待机/恢复并记录失败；不能由流程汇合点单独证明 |
| 模拟验证 | 多实例 `multipass-state-machine.scxml`，注入一个实例 `collimation_invalidated`、一个实例通信失联和全体完成 |

### SV-06 同步触发窗口

| 项目 | 登记内容 |
|---|---|
| 适用范围 | 同步系统进入触发倒计时至正式触发结果或明确异常终止 |
| 启用证据 | 同步系统进入 `triggerSent`，并记录同一 T0、发次、束线和命令关联 |
| 保持要求 | 泵浦和开关触发准备在规定窗口内完成；同步服务、联锁和储能约束持续有效 |
| 未知处理 | 不能假设已经触发或已经安全结束；进入时序异常和恢复路径 |
| 失效处理 | 根据现场规则选择紧急触发、紧急终止或人工处置；BPMN 定时器不能替代同步控制器的实时判据 |
| 解除证据 | 同步系统确认正式触发结果，或确认窗口已异常终止且储能处置约束已接管 |
| 模拟验证 | 同步模型 `trigger`、泵浦/开关 `trigger_prepare`；使用受控延迟和事件注入验证 T+3/T+5 边界、超时和重复进入 |

### SV-07 屏蔽门释放

| 项目 | 登记内容 |
|---|---|
| 适用范围 | 从提出开门意图到屏蔽门确认解除锁定并打开 |
| 启用证据 | 任何流程或人工请求开门 |
| 放行要求 | 联锁会话已释放；泵浦和开关储能约束已释放；光学工作已结束；未决动作和真空条件满足最终门槛 |
| 未知处理 | 拒绝开门并保留阻断原因 |
| 失效处理 | 门控服务异常或门状态冲突时进入人工恢复，不重复发送无界命令 |
| 解除证据 | 门控接口确认解锁/打开，人员和警示状态符合业务规则 |
| 模拟验证 | 控制环境 `unlock_door`，分别在储能约束 active、unknown、released 时请求开门，验证监督层授权结果 |

### SV-08 吹扫与跨发次资源约束

| 项目 | 登记内容 |
|---|---|
| 适用范围 | 冷却系统处于 `purging`/`purgeActive` 到 `purgeComplete` 期间，并可能与下一发准备重叠 |
| 启用证据 | `purge_start` 已受理并进入吹扫状态 |
| 保持要求 | 冷却资源不被冲突动作占用；下一发只能使用已确认不冲突的组件和服务 |
| 未知处理 | 阻止会影响吹扫或复用同一资源的动作；不能把上一发流程结束当成吹扫结束 |
| 解除证据 | `purge_completed` 并确认所有相关实例达到可复用状态 |
| 模拟验证 | 冷却模型与第二个逻辑发次同时运行，验证允许重叠和必须串行的资源集合 |

## 四个监督场景

### 场景 A：正常充电、触发和释放

| 步骤 | 组件观测 | 监督结果 | 流程可见结果 |
|---|---|---|---|
| 1 | 控制环境 `interlockEstablished`；真空 `vacuumStable`；人员归零 | SV-01 `active-valid` | 允许进入准备 |
| 2 | 泵浦、开关和同步处于参数有效；单次配方成立 | 充电能力 `allowed` | 可以发送充电动作 |
| 3 | 泵浦进入 `chargingActive`；开关进入 `charged` | SV-02、SV-03 激活 | 等待实际充电完成 |
| 4 | 泵浦 `chargeComplete`，开关 `triggerReady`，同步进入 `triggerSent` | SV-06 `active-valid` | 允许进入触发窗口 |
| 5 | T+3 前两路 `triggerReady`，T+5 同步确认结果 | 触发窗口释放或进入后处理 | 记录正式触发结果 |
| 6 | 泵浦 `protection_completed` 的现场语义已确认，开关 `safe_discharge_completed`，光源/预放待机 | SV-02、SV-03、SV-04 释放 | 监督层允许申请开门 |
| 7 | 控制环境确认解锁/打开 | SV-07 `released` | 本发安全释放完成 |

### 场景 B：倒计时期间联锁变为未知或失效

| 步骤 | 组件观测 | 监督结果 | 流程可见结果 |
|---|---|---|---|
| 1 | 同步 `triggerSent`，泵浦/开关仍有储能 | SV-02、SV-03、SV-06 active | 只能等待窗口结果或处置 |
| 2 | 门状态、人员、真空或控制器来源过期/冲突 | SV-01 `active-unknown` 或 `active-violated` | 立即撤销触发资格 |
| 3 | 监督层保存失效证据并发起已确认的终止/保护动作 | 约束进入 `recovery-required` | 流程转异常路径，不再推进正常节点 |
| 4 | 泵浦/开关分别报告停充、泄放或故障 | 能源约束按实际证据继续或结束 | 流程等待处置结果，不能直接结束 |
| 5 | 物理释放证据完整 | SV-01、SV-02、SV-03 释放或保持恢复锁存 | 需要人工恢复还是可以重新开始，由业务规则决定 |

### 场景 C：流程取消但泵浦仍然储能

| 步骤 | 组件观测 | 监督结果 | 流程可见结果 |
|---|---|---|---|
| 1 | 泵浦 `charged`/`armed`，SV-02 active-valid | 约束独立于流程实例 | 流程仍可被取消 |
| 2 | BPMN 实例取消 | 流程意图结束；SV-02 不变 | 运行记录为 cancelled，并记录未决物理动作 |
| 3 | 监督层继续观察泵浦和联锁 | SV-02 继续 active | 不允许开门、释放租约或开始冲突发次 |
| 4 | 泵浦报告安全停充/泄放完成 | SV-02 进入 released 或 recovery-required | 可进入人工确认或安全释放 |
| 5 | 监督层保存完整启用、取消、处置和解除证据 | 约束历史闭合 | 流程记录和系统监督记录分别保留 |

### 场景 D：监督进程重启时设备已处于储能状态

| 步骤 | 组件观测 | 监督结果 | 流程可见结果 |
|---|---|---|---|
| 1 | 重启后首次读取泵浦 `charged`/`armed`，没有连续启用历史 | SV-02 `activating` 或 `active-unknown` | 不允许触发、开门或恢复正常推进 |
| 2 | 首次读取联锁、真空或门禁任一缺失 | SV-01 `active-unknown` | 保守阻断，并显示缺失证据 |
| 3 | 通过同一来源建立当前状态和命令关联 | 只能逐步转为 active-valid；不能补写过去未观测的安全历史 | 进入专门恢复流程 |
| 4 | 泵浦确认储能消除、开关确认安全放电、联锁控制器确认释放 | 约束进入 released 或 recovery-required | 允许人工确认后释放资源 |
| 5 | 保存重启前后状态摘要和首次覆盖缺口 | 异常历史不可丢失 | 后续发次不能覆盖这次恢复记录 |

## `device-simulator` 验证边界

设备模拟器适合验证三个层次：

1. **组件局部状态**：动作是否只在允许状态执行，反馈是否进入预期 SCXML 状态。
2. **设施状态投影**：多个组件快照、事件和外部条件是否形成正确的系统事实。
3. **监督场景**：约束是否独立于流程激活、保持、锁存和释放。

当前模拟器具备以下基础能力：

- 从选定 SCXML 创建隔离设备实例；
- 获取设备快照和历史；
- 执行动作并观察命令结果；
- 通过模拟反馈或事件报告推进状态机；
- 为不同实例配置自动完成和反馈延迟；
- 通过独立环境承载多个组件，未来可加入监督层场景编排。

监督测试不能只调用“成功完成”反馈。还必须注入：

- 事件丢失和快照过期；
- 同一事实的来源冲突；
- 泵浦或开关进入储能后流程取消；
- 监督进程重启后的首次覆盖未知；
- 多实例中单个实例失效；
- 上一发吹扫与下一发准备的资源重叠。

模拟器验证的是控制和监督语义，不是现场安全认证。特别是 `protection_completed`、`safe_discharge_completed`、`interlockEstablished` 等状态或事件的物理含义，仍需由真实接口定义确认。

## 下一步实现顺序

1. 由业务和接口负责人审核 SV-01、SV-02、SV-03 的启用和释放证据。
2. 为泵浦、开关、同步、控制环境和真空建立一个最小隔离模拟环境。
3. 先实现并验证场景 A、C、D，再加入场景 B 的失效与恢复路径。
4. 监督层稳定后，再把流程中的重复系统判断迁移为能力请求和监督结果等待。
5. 最后扩展到光学、准直、吹扫重叠和完整后处理链路。

在 SV-01 至 SV-03 的物理证据尚未确认前，不应把当前 SCXML 的状态名直接写成生产安全放行规则。
