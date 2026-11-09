# 观测、事实、证据与监督的分层基线

这份文档规定设备观测、设施事实、运行证据、流程 guard 和系统监督之间的定义边界。它用于检查模型是否完整，不把流程引擎变成设施运行模型的上游。

## 定义来源

每一层有自己的定义来源。实验流程可以发现使用场景和遗漏，但不能因为下游需要某个对象就创造上游对象。

| 层次 | 权威定义来源 | 产物 | 不负责的内容 |
| --- | --- | --- | --- |
| 设备状态 | SCXML、设备模型和设备协议 | 设备状态、动作、命令结果和故障 | 设施业务事实、流程阶段 |
| 设备观测 | Tango 属性、事件、命令和连接语义 | 带来源、修订号、时间和质量的规范化观测 | 业务放行和监督义务 |
| 设施事实 | 设施结构、物理状态和领域语义 | 当前设施状态的 `valid / violated / unknown` 断言 | 本发次是否执行过动作 |
| 运行证据 | 命令结果、配方、数据和本发记录 | 有作用域、有时间的过程证据 | 永久设施状态 |
| 流程准入 | 流程操作的进入和完成语义 | 对事实或证据的流程决策 | 创建或改变事实含义 |
| 系统监督 | 安全规则、运行约束和持续责任 | 激活、保持、失效、恢复和解除义务 | 流程编排和页面状态 |

关系是单向投影和引用：

```text
设备状态 ──Tango──> 规范化观测 ──投影──> 设施事实 ──证据输入──> 系统监督
                                            │
                                            └──引用──> 流程准入 / 完成条件
命令结果、配方和本发操作 ───────────────────────────────> 运行证据
```

监督可以使用事实作为要求，也可以使用命令结果作为准入证据，但监督不能反向定义事实。流程只能读取设施结果，并把本发范围、动作和结果保存为运行证据。

## 每层的完整性

### 设备观测和快照

快照完整性独立于当前是否有流程引用它，至少包括：

- 结构：状态、动作、命令、结果、配置、故障、来源和修订号字段符合设备契约；
- 时间：事件和补读都能更新观测，观测年龄不超过契约限制；
- 顺序：同一 `source_id` 的 `state_revision` 不回退，来源变化会切断连续覆盖；
- 语义：活动命令、动作、任务状态和最近结果相互一致；
- 协议：Tango 属性、CHANGE_EVENT、命令、结果查询和历史查询的行为一致。

设施接收的快照统一带有 snapshot_kind、source_id 和 state_revision。组件快照还必须包含模型摘要、状态、动作、命令结果、上下文和动作契约；条件源快照必须包含条件集合和同样的来源/修订信息。tango-adapter 在事件、启动补读和命令补读进入设施运行时之前执行结构和一致性校验。

### 设施事实

事实描述设施当前成立的状态。每个事实必须有含义、适用范围、观测来源、三值判定和时效策略。事实可以是基础事实，也可以是基础事实的组合，但不能依赖 BPMN 活动 ID 或某次发次 ID。

如果一个状态只表示“本次命令已经成功”或“本发已经完成某步骤”，它应进入运行证据。只有当它表示有明确范围和有效期的设施资格时，才可以作为设施事实，并且需要显式说明范围和过期语义。

### 运行证据

运行证据必须带有本发、命令、活动或配方作用域，以及产生时间、来源和结果。设备状态恢复正常不能自动补写已经失败的本发证据；当前设施事实也不能证明某个本发动作曾经成功。

### 系统监督

每条监督义务必须独立声明：

- 激活事实；
- 持续要求；
- 安全解除事实；
- `unknown`、过期和断连时的处理；
- 失效是否锁存；
- 恢复和人工解除条件；
- 对命令准入的影响。

监督义务的完整性不能用“流程中存在一个异常分支”来代替。

## GXLF 当前模型盘点

事实集合的业务取舍和角色冻结见 [设施事实目录 v1](fact-catalog-v1.md)；完整的逐事实来源、条件、`unknown` 传播、guard 和监督用途见 [GXLF 事实完整性矩阵](fact-integrity-matrix.md)。本节保留分层盘点，用于解释分类和待接入项；目录和矩阵共同构成逐项审查清单。

以下盘点针对 `gxlf-facility-state-projection.xml` 和 `gxlf-facility.system-definition.xml`。`保留`表示语义属于该层；`复核`表示当前 XML 可以运行，但还需要确认作用域；`待接入`表示来源已经声明，运行时仍应保持 `unknown`。

### 观测

| ID | 分类 | 当前判断 |
| --- | --- | --- |
| `deviceConnectivity` | 设备连接和快照可读性 | 保留 |
| `deviceState` | 设备状态快照 | 保留；它是设备事实的输入，不必被流程直接引用 |
| `serviceHealth` | 服务健康和心跳 | 待接入 |
| `shieldDoor`、`personnelCount`、`windShower` | 建安和环境安全输入 | 待接入；`windShower` 当前没有对应事实，需确认是否保留 |
| `targetChamberVacuum`、`titaniumPumpVacuum` | 真空安全输入 | 待接入 |
| `interlockEvent`、`interlockSession`、`interlockTripState` | 联锁控制器观测 | `interlockEvent` 是事件输入；其余为状态输入 |
| `pumpEnergySafety`、`switchEnergySafety` | 储能消除确认 | 待接入 |
| `selectedOpticalDelivery`、`independentShotMotion` | 中央控制和运动结果 | 待接入；接口待确认 |
| `preampRecipe`、`measurementRecipe` | 同步配方状态 | 复核作用域，应区分当前设施配置和本发配方确认 |

### 设施事实

#### 物理和当前运行状态

这些事实可以作为设施状态模型的基础：

`simulationDevicesObserved`、`shieldDoorLocked`、`personnelCountZero`、`targetChamberVacuumStable`、`titaniumPumpVacuumNormal`、`interlockEstablished`、`interlockArmed`、`interlockReleased`、`noInterlockTrip`、`interlockOperational`、`pumpChargeComplete`、`switchChargeComplete`、`synchronizationReady`、`pumpEnergyActive`、`switchEnergyActive`、`pumpEnergyAbsent`、`switchEnergyAbsent`、`collimationExecuting`、`collimationIdle`、`purgeStopped`、`multipassCollimated`、`preampFineLoopReady`、`opticalOperationActive`、`opticalOperationReleased`。

其中 `pumpEnergyActive`、`switchEnergyActive`、`collimationExecuting` 和 `opticalOperationActive` 还被监督义务用作激活事实。它们的定义必须先依据物理运行语义确定，再由监督引用。

#### 资格和配置状态

下列事实目前由设备状态组合得到，但需要确认它们是不是“当前设施资格”，还是“某次操作的完成证据”：

`allPowerChecked`、`allFunctionReady`、`allParametersReceived`、`preampSingleConfigured`、`measurementSingleConfigured`。

如果它们只对某个发次或某组命令有效，应从设施事实中拆出带作用域的运行证据；如果它们表示设施当前仍保持的资格，则应补充有效期、作用域和失效条件。

#### 组合运行状态

`selectedOpticalDeliveryReady`、`targetShotReady`、`measurementMotionReady`、`independentShotMotionReady`、`diagnosticsPrepared`、`pumpEmissionPrepared`、`emissionPreparationReady`、`chargePreparationReady`、`triggerWindowReady` 是设施能力或运行准备状态的组合结果。

它们可以保留在事实层，但必须明确是“当前设施状态”还是“本发准备结果”。`triggerWindowReady` 还涉及同步时间语义，不能仅以静态状态组合代替真正的时间窗口契约。

### 流程准入

`simulationFunctionCheckAllowed`、`simulationParameterDispatchAllowed`、`functionCheckAllowed`、`parameterDispatchAllowed`、`preparationAllowed` 和 `triggerAllowed` 属于流程操作的放行判断。

它们可以引用设施事实，但不应成为基础事实，也不应被 `FacilitySupervision` 当作持续监督义务。新的 `fac:operation` 只携带操作引用，由 facility-server 计算准入；监督失效时，准入证据应变为 `unknown` 或 `violated`，而不是由流程自行推断。

### 当前流程引用的边界

当前可执行 smoke 流程的引用关系如下。动作结果属于本发证据；完成条件读取设施事实；两者必须同时成立，不能用设施事实替代本次动作结果。

| BPMN 活动 | 本发证据 | 设施事实 | 流程 guard | 当前分类结论 |
| --- | --- | --- | --- | --- |
| `power_check` | 各设备 `power-on-check` 命令结果 | `allPowerChecked` | 无 | `allPowerChecked` 需要确认是持续资格还是本发完成证据 |
| `function_check` | 各设备 `function-check` 命令结果 | `allFunctionReady` | `simulationFunctionCheckAllowed` | guard 是流程放行，事实仍需独立定义 |
| `receive_parameters` | 各设备 `parameter-receive` 命令结果 | `allParametersReceived` | `simulationParameterDispatchAllowed` | 配置确认需要明确本发配方作用域 |
| `gxlf_stage_hold.alignment.review` | 保持范围内的观测复核 | `simulationDevicesObserved` | `fac:hold` 由 workbench 管理 | `fac:hold` 不能替代系统监督义务 |

这张表描述使用关系，不改变事实和监督模型的定义来源。

### 监督义务

当前系统定义声明了五条持续义务：

| ID | 激活语义 | 解除语义 | 当前状态 |
| --- | --- | --- | --- |
| `interlock-session` | 联锁控制器启用 | `interlockReleased` | 已建模，外部输入待接入 |
| `pump-energy` | `pumpEnergyActive` | `pumpEnergyAbsent` | 已建模，安全泄能动作仍待明确 |
| `switch-energy` | `switchEnergyActive` | `switchEnergyAbsent` | 已建模，安全泄能动作仍待明确 |
| `collimation-operation` | `collimationExecuting` | `collimationIdle` | 已建模 |
| `optical-operation` | `opticalOperationActive` | `opticalOperationReleased` | 已建模 |

这些义务应继续由系统定义和设施监督实现拥有，不能由 BPMN 的 `fac:hold` 或异常分支替代。

状态投影中的 `fact@role` 是这条边界的机器可校验表达：`state` 表示设施当前状态，`qualification` 表示检查或配置形成的资格，`derived` 表示状态事实的组合。监督义务不能直接或间接依赖 `qualification`；流程仍可以把资格事实作为活动完成条件，但本发证据必须另行记录。

## 后续建模规则

1. 先定义设备观测契约，再定义事实投影；事实不能为了满足监督引用而临时增加。
2. 先定义设施事实和物理运行语义，再定义监督义务；监督不能决定事实的名称和含义。
3. 命令结果、本发阶段和流程完成情况进入运行证据；不能自动升级为永久设施事实。
4. 流程只引用稳定的事实和 operation ID，不把 BPMN 结构、流程实例或页面状态写入设施模型。
5. 外部观测即使尚未接入，也应保留模型声明并返回 `unknown`，不能删掉以获得“全通过”。
6. 追踪矩阵用于审查覆盖关系，不作为运行时输入；运行时只加载结构化设施模型和设备契约。

## 本轮实施顺序

1. 为上述分类补充来源章节、作用域和状态，确认资格事实与运行证据的边界。
2. 在 `facility-contract` 的模型校验中增加事实/guard/监督引用的完整性检查；已加入 `fact@role` 以及监督对资格事实的依赖检查，运行 API 只增加事实角色字段。
3. 为快照契约、事实三值和监督生命周期分别增加设施独立验收场景。
4. 只有在分类确认后，才修改投影 XML 或拆分新的运行证据模型。
