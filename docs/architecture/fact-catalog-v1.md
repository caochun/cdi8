# 设施事实目录 v1

这份目录先于流程准入和监督义务。它回答“哪些状态值得作为设施事实长期提供”，而不是罗列流程步骤。流程可以引用目录中的事实，监督只能引用标为“监督输入”的事实；某次发次、命令或流程节点的完成情况仍属于运行证据。

## 角色和范围

| 角色 | 含义 | 允许的使用 |
| --- | --- | --- |
| `state` | 当前设施或设备相关状态，有明确实时来源 | 可以被流程和监督引用 |
| `derived` | 由已定义事实、设备快照或动作状态组合得到的当前设施状态 | 可以被流程和监督引用，但必须传播输入的 `unknown` |
| `qualification` | 一次检查、配置或准备形成的资格，可能需要本发作用域 | 只能被流程准入/完成条件引用，不能驱动监督义务 |
| 运行证据 | 命令结果、配方版本、本发阶段和流程完成记录 | 由命令/发次记录保存，不进入设施事实投影 |

事实的范围统一是“当前设施”。如果状态必须绑定发次、命令、配方版本或流程实例，不能继续放在 `state` 或 `derived` 中，应建立运行证据。

v1 另外区分两种设施事实范围：`facility` 表示整个当前设施，`instance-set` 表示一个 `instanceSet` 内的当前实例集合。实例集合事实仍然是设施状态，不是某个流程实例的状态；它必须在 XML 中声明 `scopeRef`。束线和本发选中实例属于能力依赖或运行证据上下文，不在事实投影中伪装成第三种动态范围。

## v1 事实集合

### 设备观测和当前设备状态

这些事实来自设备快照、动作状态或外部观测，描述当前是否可观测以及设备是否处于某个物理/运行状态。

| 事实 | 角色 | 语义 | 监督输入 |
| --- | --- | --- | --- |
| `simulationDevicesObserved` | `state` | 所有模拟设备均有可验证、未过期快照 | 否 |
| `allServicesHealthy` | `state` | 所有声明服务的健康和心跳有效 | 是；所有持续义务 |
| `shieldDoorLocked` | `state` | 屏蔽门确认锁闭 | 间接；`interlock-session` |
| `personnelCountZero` | `state` | 人员计数确认归零 | 间接；`interlock-session` |
| `targetChamberVacuumStable` | `state` | 靶室真空确认稳定 | 间接；`interlock-session` |
| `titaniumPumpVacuumNormal` | `state` | 钛泵真空确认正常 | 间接；`interlock-session` |
| `pumpChargeComplete` | `state` | 泵浦当前充电完成 | 否 |
| `switchChargeComplete` | `state` | 开关当前充电完成 | 否 |
| `synchronizationReady` | `state` | 同步系统具备当前触发能力 | 否 |
| `interlockArmed` | `state` | 联锁控制器已启用 | 是；`interlock-session` 激活 |
| `interlockReleased` | `state` | 联锁控制器确认解除 | 是；`interlock-session` 解除 |
| `noInterlockTrip` | `state` | 联锁控制器确认没有触发 | 是；`interlock-session` 持续要求 |
| `pumpEnergyAbsent` | `state` | 泵浦储能由独立安全接口确认消除 | 是；`pump-energy` 解除 |
| `switchEnergyAbsent` | `state` | 开关储能由独立安全接口确认消除 | 是；`switch-energy` 解除 |
| `collimationExecuting` | `state` | 至少一个多程设备的准直动作正在执行 | 是；`collimation-operation` 激活 |
| `collimationIdle` | `state` | 所有多程设备的准直动作均明确停止 | 是；`collimation-operation` 解除 |
| `purgeStopped` | `state` | 冷却设备确认停止吹扫；适配器必须证明 `ready` 与该物理含义一致 | 是；`collimation-operation` 持续要求 |
| `multipassCollimated` | `state` | 多程设备当前准直结果达标 | 否 |
| `preampFineLoopReady` | `state` | 预放精闭环当前达标 | 否 |
| `selectedOpticalDeliveryReady` | `state` | 所选束线的实际选源和上游输出已核验 | 否 |
| `targetShotReady` | `state` | 靶瞄系统进入当前打靶状态 | 否 |
| `measurementMotionReady` | `state` | 测量打靶运动准备完成 | 否 |
| `independentShotMotionReady` | `state` | 独立打靶运动准备结果确认就绪 | 否 |
| `diagnosticsPrepared` | `state` | 诊断设备当前具备发射准备条件 | 否 |
| `pumpEmissionPrepared` | `state` | 泵浦当前完成发射准备 | 否 |

`pumpEnergyActive`、`switchEnergyActive` 和 `opticalOperationActive` 是当前活动状态，分别由设备状态与动作状态组合得到，属于 `derived`，但可以直接激活监督义务。`opticalOperationReleased` 是对应的安全退出状态，也属于 `derived`。

### 组合设施事实

| 事实 | 角色 | 组合含义 | 监督输入 |
| --- | --- | --- | --- |
| `interlockEstablished` | `derived` | 屏蔽门、人员、靶室真空和钛泵真空全部成立 | 是；`interlock-session` 持续要求 |
| `interlockOperational` | `derived` | 联锁已启用、安全输入成立且无触发 | 是；泵浦、开关、准直和光学义务持续要求 |
| `pumpEnergyActive` | `derived` | 泵浦正在充电、已储能或等待触发 | 是；`pump-energy` 激活 |
| `switchEnergyActive` | `derived` | 开关正在充电、已储能或等待触发 | 是；`switch-energy` 激活 |
| `opticalOperationActive` | `derived` | 光源出光或预放工作 | 是；`optical-operation` 激活 |
| `opticalOperationReleased` | `derived` | 光源和预放均退出工作并确认就绪 | 是；`optical-operation` 解除 |
| `emissionPreparationReady` | `derived` | 发射准备所需设备、选源、运动、服务和联锁条件同时成立 | 否 |
| `chargePreparationReady` | `derived` | 充电前设备配置、联锁、服务和配方条件同时成立 | 否 |
| `triggerWindowReady` | `derived` | 触发窗口前充电、同步、联锁、服务和配方条件同时成立 | 否 |

组合事实不产生新的设备观测。任一必要输入为 `unknown` 时，组合结果必须是 `unknown`；只有输入明确不满足时才是 `violated`。

### 实例集合范围

以下事实在 v1 中明确绑定到一个实例集合；集合内的默认聚合是 `all`，XML 中声明 `quantifier="any"` 的条件才使用任一实例语义：

| 实例集合 | 事实 |
| --- | --- |
| `pump` | `pumpChargeComplete`、`pumpEnergyActive`、`pumpEnergyAbsent`、`pumpEmissionPrepared` |
| `switch-driver` | `switchChargeComplete`、`switchEnergyActive`、`switchEnergyAbsent` |
| `synchronization` | `synchronizationReady`、`preampSingleConfigured`、`measurementSingleConfigured` |
| `multipass` | `collimationExecuting`、`collimationIdle`、`multipassCollimated` |
| `cooling` | `purgeStopped` |
| `preamp` | `preampFineLoopReady` |
| `target-alignment` | `targetShotReady` |
| `measurement` | `measurementMotionReady` |
| `diagnostics` | `diagnosticsPrepared` |

`allPowerChecked`、`allFunctionReady`、`allParametersReceived` 和准备类组合事实跨越多个实例集合，因此保持 `facility` 范围。当前仿真配置每类设备只部署一个实例；多实例测试使用多个运行设备验证集合内的 `all/any` 语义。

### 流程资格

这些条目保留在投影中，供初始化或发次流程使用，但不属于监督的事实来源：

| 事实 | 角色 | 语义 | 后续处理 |
| --- | --- | --- | --- |
| `allPowerChecked` | `qualification` | 当前参与设备完成上电检查 | 流程完成证据必须按命令和设备范围记录 |
| `allFunctionReady` | `qualification` | 当前参与设备完成并保持功能检查结果 | 明确检查批次和有效期 |
| `allParametersReceived` | `qualification` | 当前参与设备接收并应用参数 | 绑定配方版本和本发范围 |
| `preampSingleConfigured` | `qualification` | 预放实际确认单次配方 | 绑定配方版本/作用域 |
| `measurementSingleConfigured` | `qualification` | 测量实际确认单次配方 | 绑定配方版本/作用域 |

这些资格可以被 `simulation-function-check`、`simulation-parameter-dispatch`、`function-check`、`parameter-dispatch`、`preparation` 或 `trigger` 间接使用，但不能成为 operating constraint 的激活、解除或持续要求。

## 暂缓纳入的事实

以下内容在系统定义中已有接口或设计意图，但 v1 不把它们伪装成已成立的设施事实：

- `serviceHealth` 的现场心跳协议；
- 屏蔽门、人员、真空、联锁会话和联锁触发状态的真实接口；
- 泵浦和开关储能消除确认；
- 实际选源、独立打靶运动和同步配方的范围/版本协议；
- 专用触发时间窗口和资源预约状态。

接口未接入时，保留对应观测和事实定义，但运行结果必须是 `unknown`，不能返回 `valid`，也不能通过删除事实来获得流程放行。

## 冻结后的实施约束

1. 事实 XML 的 `role`、`scope` 和 `scopeRef` 必须与本目录一致；新增事实先更新目录，再修改投影。
2. 监督义务只能引用 `state` 或 `derived`，且必须在目录中标记为监督输入。
3. 流程准入可以引用资格和组合事实，但命令结果、本发完成和流程节点状态必须单独写入运行证据。
4. 事实的来源、条件、时效和 `unknown` 原因应在事实完整性矩阵中逐项维护。
