# GXLF 系统约束、状态投影与实验操作模型复审

依据：[集中管控系统控制流程业务说明](../domain/gxlf/process-control/集中管控系统控制流程业务说明.md)。本次是在原型阶段对职责和业务关系的修订，不代表现场安全接口已完成或完整发次已可执行。

## 三类模型各自回答什么

| 模型 | 问题 | 当前入口 |
|---|---|---|
| 系统定义 | 系统如何组成；某项能力依赖什么；在什么实际工作状态下必须持续满足哪些要求；什么证据允许解除 | [gxlf-facility.system-definition.xml](../../examples/gxlf/system/gxlf-facility.system-definition.xml) |
| 状态投影与系统监督 | 设备现在在做什么；观测是否可信；事实是否成立；适用中的约束是否失效 | [gxlf-facility-state-projection.xml](../../examples/gxlf/system/gxlf-facility-state-projection.xml)、`FacilityStateRuntime`、`FacilitySupervision` |
| BPMN | 本发如何进入/退出这些状态；操作的先后和并行关系；异常后怎样中断、处置和决定恢复 | [gxlf_shot_run_target.bpmn20.xml](../../examples/gxlf/design/gxlf_shot_run_target.bpmn20.xml) |

系统义务的寿命跟随实际设备/控制器状态。BPMN 的任务结束、子流程退出、取消整发都不能证明设备已停止、储能已消除或联锁已解除。模型归档用于说明本次执行依据，不引入运行时版本分支。

## 约束的两种表达

能力自身的执行要求仍放在 `componentTypes/componentType/capabilities/capability/requirements`。例如准直：

```xml
<capability id="collimation" label="光路准直" kind="control" actionRef="collimation">
  <requirements>
    <inputRequirement id="incident-light" maintain="execution"
      pathRef="optical-path" inputPortRef="optical-in" signalRef="alignment-light" />
    <factRequirement id="interlock" factRef="interlockOperational" maintain="execution" />
    <factRequirement id="services" factRef="allServicesHealthy" maintain="execution" />
    <factRequirement id="purge-stopped" factRef="purgeStopped" maintain="execution" />
  </requirements>
</capability>
```

`inputRequirement` 按所选束线递归解析端口输出及其上游依赖。`factRequirement` 引用投影中的系统事实。`start` 在命令下发前检查，`execution` 在该命令核验期间持续检查；任何非 valid 结果都不能放行。BPMN 无须重复列出这些固有要求。

不依赖某个 BPMN 活动寿命的工作状态义务，放在系统定义根下的 `operatingConstraints`：

```xml
<operatingConstraint id="pump-energy" label="泵浦充电、储能或待触发期间保持联锁"
  activationFactRef="pumpEnergyActive" releaseFactRef="pumpEnergyAbsent">
  <requirements>
    <factRequirement id="requirement-1" factRef="interlockOperational" maintain="execution" />
    <factRequirement id="requirement-2" factRef="allServicesHealthy" maintain="execution" />
  </requirements>
</operatingConstraint>
```

这里的 `execution` 指该工作状态义务的整个适用期间。该容器只接受事实要求，不接受 BPMN 变量或本发 `collectionVar`。启用/解除条件也必须是由设备/控制器观测得到的事实，不能由“要求恰好成立”来开关义务。

当前五条系统工作状态约束：

| 规则 | 启用证据 | 解除证据 | 保持要求 |
|---|---|---|---|
| `interlock-session` | 控制器确认 armed | 控制器确认 released | 门禁/人员/真空输入、无联锁触发、服务健康 |
| `pump-energy` | 泵浦充电动作，或 charging/charged/armed 状态 | 实际储能消除确认 | 联锁正常、服务健康 |
| `switch-energy` | 开关充电动作，或 charged/armed 状态 | 实际储能消除确认 | 联锁正常、服务健康 |
| `collimation-operation` | 任一参与多程设备有正在执行的准直命令 | 全部参与多程设备均确认准直动作已结束 | 吹扫停止、联锁正常、服务健康 |
| `optical-operation` | 光源正在出光、预放处于唤醒工作态或相应启动动作在执行 | 光源/预放均确认回到就绪且相应启动动作已结束 | 联锁正常、服务健康；不要求预放唤醒后永久保持同步重频 |

准直的逐束入射光仍由能力的结构依赖解析。当前这种按命令选路的递归监控不等于已经取得独立于流程的现场光路选通/光功率观测；取消流程后的逐束光路监督和现场绑定仍需补齐。新增系统工作状态监督证明了寿命分离机制，不能据此声称所有物理约束均已覆盖。

## 投影与监督语义

- `devicePredicate` 默认对当前部署参与集合取 `all`，`quantifier="any"` 可表示其中任一设备进入某种工作状态；没有设备观测返回 unknown，不作空集合通过。
- `actionPredicate` 同时检查快照的 `active_action`、`active_command_id` 和观测完整性，区分“正在执行准直”与“准直结果达标”。动作字段缺失或互相矛盾返回 unknown。`actionRef` 必须存在于实际 SCXML 中。
- `allPowerChecked`、`allFunctionReady` 覆盖已检查后仍正常的工作状态。设备从 functionReady 进入 parameterOk 不再使“功能就绪”变假；功能就绪按明确叶子状态定义，排除 resetReady、standbyReady 等仍需重新检查的待机状态，故障、关机及未检查状态也不满足。它们是当前事实，不能替代本发命令成功证据。
- `allParametersReceived` 是参数阶段即时完成判据，不是永久历史标记。准备、第二次精闭环、正式发射等本发历史由流程和关联结果保存。
- `interlockEstablished` 保留为“四类联锁物理输入成立”的事实，标签明确这个范围。另有 `interlockArmed`、`interlockReleased`、`noInterlockTrip`。真正可用的 `interlockOperational` 是启用、输入正常、无触发的合取。
- `emissionPreparationReady` 展开业务 6.22 的 AND 里程碑，并保留独立打靶运动准备链；实际选源及该独立链路尚未接入，故不能误判为完成。
- `chargePreparationReady`、`triggerWindowReady` 要求独立预放/测量单次配方的真实确认；现有统一 `single_mode` 不能冒充两路配方都已生效。

监督在事件到来、周期补读及读取当前状态时求值，不需要有 Flowable 实例。观察到启用事实后义务持续有效，只有解除事实 valid 且启用事实明确不成立才可退出；两者同时成立按矛盾证据处理。设备进入 fault、失联、观测过期，都不能作为解除证据。

适用期间 observed violated/unknown 会锁存，恢复为正常观测也不能消除已经发生的失效。安全退出后结束这次义务，但保留 `lastIncident`，供协调器发现“发生并恢复于两次轮询之间”的异常。之后再次启用才建立新一轮监督。启动时首次观测已处于工作状态，因缺少从安全状态开始的覆盖，按 unknown 锁存；不能用一次健康快照证明重启期间一直安全。

监督当前状态通过 `GET /api/facility/state` 的 `constraints` 返回：启用/解除证据、具体要求、active、status、latched 和最近异常。运行调试同时显示“当前系统监督”和“本发结束时快照”，查看已结束实验时不会把历史状态误显示成实时状态。

`WorkflowService` 消费监督结果，失效时停止正常推进，保存结束快照和未决物理动作。观测监听器不调用 Flowable。当前的实际运行策略是保守结束本发；设计 BPMN 中的业务异常分流、现场广播、停充/泄放和人工恢复尚未执行化，不能声称停止流程已经完成这些物理动作。未解除的工作状态规则不能在模型切换时被删除或替换。

## 逐段业务复审结果

| 业务依据 | 系统定义/投影 | BPMN 修订与原型判断 |
|---|---|---|
| 3.1、6.1 初始化与参数 | 当前检查/配置事实；真实服务健康仍为外部观测 | 上电全部完成→功能检查全部完成→本发参数；去掉“尚未初始化就要求功能就绪”的循环前置。真空参数接口缺口明确保留 |
| 4、6.8—6.10 清场/联锁 | 区分物理输入、控制器启用、触发、解除 | 清场→人员归零→锁门→输入核验→请求启用→控制器确认。6.2—6.7 已要求联锁，原型选择先建立联锁再执行这些动作；与原图并行布局的矛盾需现场确认 |
| 6.2—6.7、6.14 光学准备 | 保留输入链递归；准直执行要求停吹扫；准直达标是结果 | 所选光源出光与同步重频→预放唤醒后，闭环和准直分别推进；不凭空增加“准直必须等待第一次精闭环”条件 |
| 6.11—6.20 靶瞄链 | 分别声明准直达标、靶瞄、测量运动及独立运动结果 | 同步测量重频→测量靶瞄准备/靶预定位→模拟靶定位；模拟靶定位与准直共同到达光束引导，随后展开三条结果链；诊断准备从模拟靶定位分出 |
| 6.21—6.22 发射准备完成 | 明确九项及独立运动链的合取 | 是核验里程碑，不伪造“准备完成”命令或成功表达式 |
| 6.23—6.31 充电前 | 充电能力引用准备事实；两个单次配方确认独立 | 诊断设置、晶体位姿、泵浦准备、第二次精闭环→同步预放单次→能量计清零、同步测量单次→测量准备、开关充电汇合后才到泵浦充电。预放单次配方的归属纠正到同步系统 |
| 6.32—6.33 充电/警示 | 充电、储能、待触发工作状态的系统义务 | 充电前开启警灯/音乐，完成后停音乐并保留警灯。75±3 秒记录为业务时限，设备时基/验收尚未接入；最大储能等待时长不自行编造 |
| 6.34—6.37 正式触发 | 进入窗口前核验，正式结果判据未决 | 以同一 T0 表示 T+3 准备截止及 T+5 物理触发；超时进入异常路径。BPMN 定时器只表达业务设计，实时触发/抑制必须由同步控制器实现 |
| 6.38—6.57 后处理/预电离 | 继续监督实际储能和联锁状态 | 分开数据、待机、预电离准备→充电→触发准备/触发→采集→待机，以及测量分析、收靶、波形、吹扫；预电离阶段不能直接复用正式充电所有准入条件 |
| 6.58 正常开门 | 物理安全退出、联锁解除确认独立 | “后处理证据汇总”不等于已确认的开门门槛。释放策略设为未决阻断；不把并行 join 当成业务已确认 |
| 4 异常/人工请求 | 联锁触发及观测不可信；工作状态义务不随取消消失 | 联锁触发的广播终止/解锁/报警；充电中停充；已充电未触发泄放；其他故障；人工审查恢复或结束。紧急触发/紧急停车保留单独请求路径，未擅自解释为普通停机 |

图纸的“打靶运动准备”与“测量打靶运动准备”没有合并。同步预放/测量的重频与单次配置仍需要独立现场通道；当前单一同步 SCXML 模式只能做原型验证。靶瞄收靶/分析的并发关系也必须结合设备忙闲与状态可达性解决后才能执行化。

正常解除前补充所选光源关光/待机及光源、预放退出工作状态的确认，这是由系统工作状态约束推导出的原型收尾步骤；业务 6.4 支持关光，但现场关光与当前 SCXML 待机动作的对应关系仍需确认。真空参数初始化也保留为独立未实现节点，不能因组件缺少参数动作而从业务流程中消失。

## `fac:hold` 的位置

保留它用于**本次实验额外选择的**跨任务条件，例如人为要求准直完成后继续照明几秒以观察稳定性。它不负责启动或解除系统固有安全义务。

`gxlf_stage_hold` 已改名为“附加光学保持试验”。5 秒只是技术测试窗口，不是业务准直要求，也不意味着光消失时已完成的准直结果自动失效。内置真实契约现在会因为外部联锁未知阻止其受保护动作；端到端拓扑/阶段保持测试通过 `FacilityTestModels.opticalOnly` 显式装载隔离契约，既不伪造真实联锁事实，也不修改生产默认安全规则。

## 验证及继续执行化的边界

运行 `mvn -Pworkbench-e2e verify`，覆盖严格 XML/跨模型引用、工作状态量词、动作观测、首次监督覆盖未知、瞬时异常锁存、故障不等于安全退出、未知联锁阻止下发，以及真实 Java JTango 联动下“取消 BPMN 后监督继续”和“没有 fac:hold 时系统异常仍中止流程”。

初始化切片继续可运行。完整发次 BPMN 明确 `isExecutable="false"`，不含直接写成功的表达式。下一步应逐项实现真实安全输入/控制器会话、实际选源与配方观测、储能消除确认、全局异常处置及专用时序契约，再按阶段执行化。持久化完整系统异常历史、后端崩溃恢复、逐束物理依赖的脱离流程监督仍在 `todo.md` 中，不能用当前内存锁存代替完整安全审计。

工作台允许非执行设计模型通过结构/引用校验并发布用于预览：仅免除“必须存在可执行流程”和“设计服务任务必须填写执行实现”两项执行要求，其余校验继续执行。启动入口仍逐个要求 `isExecutable=true`，端到端测试验证此设计能够发布但无法启动，也不会发送设备命令。
