# GXLF 事实完整性矩阵

这张矩阵是 `examples/gxlf/system/gxlf-facility-state-projection.xml` 的审查基线。事实的业务取舍、角色和范围定义以[设施事实目录 v1](fact-catalog-v1.md)为准。矩阵描述事实本身的来源和完整性，不把流程活动或监督义务倒灌到事实模型。流程可以引用“流程准入使用”列中的事实；监督只能使用“监督用途”列明确标出的激活、解除或持续要求。实例集合事实的 `scopeRef` 必须与条件来源的实例集合一致。

矩阵中的 `unknown` 有统一含义：所需设备快照、动作状态或外部观测缺失、过期、来源改变、修订号回退、结构校验失败，或者组合事实的任一必要输入为 `unknown`。来源有效但条件明确不成立时才是 `violated`。当前投影的全局证据时限为 `2000 ms`；现场接入后应按具体观测契约收紧，而不能把过期值继续当作 `valid`。

| 事实 | 设备/观测来源 | 成立条件 | `unknown` 原因 | 允许被流程准入使用 | 允许驱动监督义务 |
| --- | --- | --- | --- | --- | --- |
| `simulationDevicesObserved` | `deviceConnectivity`；`all-components` 的 `state-out` | 所有模拟设备都能读取结构完整且新鲜的快照 | 任一设备断连、快照过期或代次/修订不连续 | `simulation-function-check`、`simulation-parameter-dispatch` | 否 |
| `allPowerChecked` | 14 个组件实例的设备快照 | 每个实例同时满足 XML 中声明的 `powerOn*` 和允许的外层状态 | 任一实例缺快照、状态不完整或集合成员不全 | `simulation-function-check`、`function-check` | 否；属于 `qualification` |
| `allFunctionReady` | 14 个组件实例的设备快照 | 每个实例满足其 `functionReady`、参数和运行准备状态集合 | 任一实例快照无效或必要状态未知 | `simulation-parameter-dispatch`、`parameter-dispatch` | 否；属于 `qualification` |
| `allParametersReceived` | 12 个参与参数接收的组件实例 | 每个实例处于其 `configured`/`parameterOk` 状态 | 任一参与实例缺快照、参数状态未知或实例集合不完整 | `preparation` | 否；属于 `qualification` |
| `allServicesHealthy` | `serviceHealth` 外部心跳观测 | 所有声明的服务都持续报告健康 | 心跳未接入、任一服务超时、断连或来源不可信 | `function-check`、`parameter-dispatch`、`preparation` | `interlock-session`、`pump-energy`、`switch-energy`、`collimation-operation`、`optical-operation` 的持续要求 |
| `shieldDoorLocked` | `shieldDoor` 外部观测 | 值为 `locked` | 安全门接口未接入、观测过期或值无法解释 | 间接通过 `interlockEstablished` | 间接通过 `interlock-session` |
| `personnelCountZero` | `personnelCount` 外部观测 | 值为 `0` | 人员计数器未接入、过期或计数无效 | 间接通过 `interlockEstablished` | 间接通过 `interlock-session` |
| `targetChamberVacuumStable` | `targetChamberVacuum` 外部观测 | 值为 `true` | 真空输入未接入、过期或质量不可信 | 间接通过 `interlockEstablished` | 间接通过 `interlock-session` |
| `titaniumPumpVacuumNormal` | `titaniumPumpVacuum` 外部观测 | 值为 `true` | 钛泵真空输入未接入、过期或质量不可信 | 间接通过 `interlockEstablished` | 间接通过 `interlock-session` |
| `interlockEstablished` | 上述四个安全输入事实 | 四项安全输入全部 `valid` | 任一输入 `unknown` | 间接通过 `preparation` | `interlock-session` 持续要求 |
| `pumpChargeComplete` | `pump` 设备快照 | 泵浦状态为 `chargeComplete` | 泵浦快照无效、设备集合缺失或状态未知 | 间接通过 `triggerWindowReady`，再由 `trigger` 使用 | 否 |
| `switchChargeComplete` | `switch-driver` 设备快照 | 开关状态为 `chargeComplete` | 开关设备快照无效或状态未知 | 间接通过 `chargePreparationReady`/`triggerWindowReady` | 否 |
| `synchronizationReady` | `synchronization` 设备快照 | 状态为 `singleConfigured` | 同步设备快照无效或配置状态未知 | 间接通过 `chargePreparationReady`/`triggerWindowReady` | 否 |
| `interlockArmed` | `interlockSession` 外部观测 | 值为 `armed` | 联锁会话接口未接入、过期或来源改变 | 间接通过 `interlockOperational` | `interlock-session` 激活事实 |
| `interlockReleased` | `interlockSession` 外部观测 | 值为 `released` | 联锁解除确认未接入、过期或来源改变 | 否 | `interlock-session` 解除事实 |
| `noInterlockTrip` | `interlockTripState` 外部观测 | 值为 `clear` | 联锁触发状态未接入、过期或来源不可信 | 间接通过 `interlockOperational` | `interlock-session` 持续要求 |
| `interlockOperational` | `interlockArmed`、`interlockEstablished`、`noInterlockTrip` | 三项事实全部 `valid` | 任一输入为 `unknown` | `preparation`，间接用于 `trigger` | `pump-energy`、`switch-energy`、`collimation-operation`、`optical-operation` 持续要求 |
| `pumpEnergyActive` | `pump` 快照及 `charge` 动作快照 | 任一泵浦处于 `charging/charged/armed`，或充电动作活动 | 泵浦快照/动作活动状态不完整或无法确认 | 否 | `pump-energy` 激活事实 |
| `switchEnergyActive` | `switch-driver` 快照及 `charge` 动作快照 | 任一开关处于 `charged/armed`，或充电动作活动 | 开关快照/动作活动状态不完整或无法确认 | 否 | `switch-energy` 激活事实 |
| `pumpEnergyAbsent` | `pumpEnergySafety` 外部安全观测 | 值为 `safe` | 储能消除接口未接入、过期或未给出物理确认 | 否 | `pump-energy` 解除事实 |
| `switchEnergyAbsent` | `switchEnergySafety` 外部安全观测 | 值为 `safe` | 储能消除接口未接入、过期或未给出物理确认 | 否 | `switch-energy` 解除事实 |
| `collimationExecuting` | `multipass` 的 `collimation` 动作快照 | 任一准直动作活动 | 动作快照缺失、过期或动作状态不一致 | 否 | `collimation-operation` 激活事实 |
| `collimationIdle` | `multipass` 的 `collimation` 动作快照 | 所有准直动作明确不活动 | 任一设备或动作没有有效快照 | 否 | `collimation-operation` 解除事实 |
| `purgeStopped` | `cooling` 设备快照 | 冷却设备外层状态为 `ready` | 冷却设备快照无效；`ready` 与实际停吹扫的对应关系尚未由适配器证明 | 否 | `collimation-operation` 持续要求 |
| `multipassCollimated` | `multipass` 设备快照 | 外层状态为 `collimated` 或 `shotCapable` | 多程设备快照无效或准直结果未知 | 间接通过 `emissionPreparationReady` | 否 |
| `preampFineLoopReady` | `preamp` 设备快照 | 状态为 `fineLoopOk` | 预放快照无效或精闭环状态未知 | 间接通过 `emissionPreparationReady` | 否 |
| `selectedOpticalDeliveryReady` | `selectedOpticalDelivery` 外部观测 | 值为 `ready` | 中央控制选源证据未接入、过期或未证明选择范围 | 间接通过 `emissionPreparationReady` | 否 |
| `targetShotReady` | `target-alignment` 设备快照 | 状态为 `shotReady` | 靶瞄快照无效或位置状态未知 | 间接通过 `emissionPreparationReady` | 否 |
| `measurementMotionReady` | `measurement` 设备快照 | 状态为 `motionPrepared` | 测量设备快照无效或运动状态未知 | 间接通过 `emissionPreparationReady` | 否 |
| `independentShotMotionReady` | `independentShotMotion` 外部观测 | 值为 `ready` | 独立运动接口未接入、过期或证据范围不明 | 间接通过 `emissionPreparationReady` | 否 |
| `diagnosticsPrepared` | `diagnostics` 设备快照 | 状态为 `deviceConfirmed` | 诊断设备快照无效或确认状态未知 | 间接通过 `emissionPreparationReady` | 否 |
| `pumpEmissionPrepared` | `pump` 设备快照 | 状态为 `emissionPrepared` | 泵浦快照无效或发射准备状态未知 | 间接通过 `emissionPreparationReady` | 否 |
| `emissionPreparationReady` | 其列出的设备事实和外部观测事实 | 十项准备条件全部 `valid` | 任一准备条件 `unknown`，尤其是外部选源、运动、服务或联锁输入未接入 | 否 | 否；是组合事实 |
| `chargePreparationReady` | 设备状态、联锁、服务和同步配方事实 | XML 中声明的充电前设备配置全部 `valid` | 任一设备、服务、联锁或配方事实 `unknown` | 否 | 否；是组合事实 |
| `triggerWindowReady` | 泵浦/开关/同步设备、联锁、服务和两项配方事实 | 触发窗口全部前置条件 `valid` | 任一充电、同步、联锁、服务或配方输入 `unknown` | `trigger` | 否；是组合事实 |
| `preampSingleConfigured` | `preampRecipe` 外部配方观测 | 值为 `single` | 配方接口未接入、过期、版本/作用域不明 | 间接通过 `chargePreparationReady`/`triggerWindowReady` | 否；属于 `qualification` |
| `measurementSingleConfigured` | `measurementRecipe` 外部配方观测 | 值为 `single` | 配方接口未接入、过期、版本/作用域不明 | 间接通过 `chargePreparationReady`/`triggerWindowReady` | 否；属于 `qualification` |
| `opticalOperationActive` | `seed`、`shg`、`preamp` 设备状态和动作快照 | 任一光源处于 `emitting`、任一出光动作活动，或预放处于 `wakeup`/唤醒动作活动 | 任一相关设备/动作快照不完整，无法确认是否仍在工作 | 否 | `optical-operation` 激活事实 |
| `opticalOperationReleased` | `seed`、`shg`、`preamp` 设备状态和动作快照 | 三类设备都处于 `ready`，且相关动作明确不活动 | 任一相关设备或动作缺有效退出确认 | 否 | `optical-operation` 解除事实 |

## 审查规则

- `qualification` 事实（当前为 `allPowerChecked`、`allFunctionReady`、`allParametersReceived`、`preampSingleConfigured`、`measurementSingleConfigured`）只允许服务流程准入或完成条件，不能作为 operating constraint 的激活、解除或持续要求。
- 组合事实可以被流程准入使用，但不能因为某个流程引用它就改变基础事实的来源。组合事实的 `unknown` 必须向上传播，不能用旧值补齐。
- 监督激活、解除和持续要求只使用矩阵中明确标记的设施状态事实。流程命令成功、流程节点完成和 `fac:hold` 都属于运行证据或流程语义，不能替代这些事实。
- 矩阵中的“待接入”来源保留在 XML 中。接口未接入时 API 应返回 `unknown`，而不是删除事实或返回 `valid`。

每次新增事实、修改条件或接入外部观测时，应同步修改 XML、矩阵和相应的设施独立测试；如果事实只服务于某次发次，应改为带作用域的运行证据，而不是继续扩展设施事实集合。
