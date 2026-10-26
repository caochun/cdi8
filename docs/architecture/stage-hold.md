# BPMN 阶段持续保持

系统定义描述能力与输出的输入依赖，设施投影给出事实和 guard，BPMN 指定业务上的保持范围与失效路径。`fac:hold` 在普通内嵌子流程上声明阶段约束；范围从进入子流程开始，到所有内部执行分支结束为止，包含设备任务、任务之间的计时等待以及 `fac:completion` 等待。

## BPMN 表达

```xml
<bpmn:error id="alignmentHoldLost" errorCode="ALIGNMENT_HOLD_LOST" />

<bpmn:subProcess id="alignment" name="准直与复核期间保持入射光">
  <bpmn:extensionElements>
    <fac:hold errorRef="alignmentHoldLost">
      <fac:input typeRef="multipass" capabilityRef="collimation"
                 requirementRef="incident-light" collectionVar="multipassTargets" />
    </fac:hold>
  </bpmn:extensionElements>
  <!-- 开始 → 准直任务 → 稳定等待 → 复核任务 → 结束 -->
</bpmn:subProcess>

<bpmn:boundaryEvent id="holdLost" attachedToRef="alignment" cancelActivity="true">
  <bpmn:errorEventDefinition errorRef="alignmentHoldLost" />
</bpmn:boundaryEvent>
<bpmn:serviceTask id="handleHoldLoss" name="记录异常，结束本发"
  flowable:expression="${shotRuns.fail(execution, holdFailureReason)}" />
<bpmn:sequenceFlow id="failureRoute" sourceRef="holdLost" targetRef="handleHoldLoss" />
```

`fac` 的命名空间为 `https://cdi8.cn/facility/v1`。保持声明是项目扩展，子流程和边界错误事件使用 BPMN/Flowable 原有执行机制。完整可执行模型见 [gxlf_stage_hold.bpmn20.xml](../../examples/gxlf/processes/gxlf_stage_hold.bpmn20.xml)。

| 元素 | 属性与语义 |
|---|---|
| `fac:hold` | 每个普通子流程最多一个，`errorRef` 必须引用带 errorCode 的 BPMN error；至少包含一项 input 或 condition，所有项为 AND |
| `fac:input` | 四项必填：组件类型 `typeRef`、能力 `capabilityRef`、该能力的输入依赖 `requirementRef`、目标设备集合变量 `collectionVar` |
| `fac:condition` | 引用一个投影 `factRef` 或 `guardRef`；保持高层组合条件，不自动推导拓扑依赖 |

`fac:input` 沿系统定义中的输入/输出契约递归展开。示例的 `multipass/collimation/incident-light` 会展开本发束线对应的预放唤醒、所选光源出光及同步重频模式，无须在 BPMN 中重复这些设备。它引用输入条件，并不调用准直动作，也不要求多程组件一直停在某个精确叶子状态。

`collectionVar` 在进入时读取，结合本发 `beamlines`、`sourceSelections`、逻辑实例绑定确定具体设备，并将展开结果固定到本次保持记录。缺少设备、空集合、错误选路或未绑定实例按 unknown 走异常路径；修改变量不能悄悄改换已建立保持范围中的设备。运行中切换资源应先退出原范围，再进入新的范围。

也可以保持投影已有的高层条件，例如 `<fac:condition factRef="interlockEstablished"/>`。该条件按投影自身的范围求值，不因 `collectionVar` 自动缩小。当前真实联锁尚未接入，加入此条件会因 unknown 阻止进入，这是保守语义。

## 与其他条件的区别

| 声明 | 何时检查 | 不成立后的行为 |
|---|---|---|
| `fac:requires` | 任务进入时 | 失败 |
| 系统能力 `maintain="execution"` | 单次命令下发到结果验收 | 结束运行并留存命令依赖证据 |
| `fac:completion` | 本任务全部命令验收之后 | 等待，直至成立或超时 |
| 子流程 `fac:hold` | 整个子流程生命周期 | violated 和 unknown 都锁存，触发声明的边界错误路径 |

阶段保持显式延长所引用输入的使用范围，即使原能力输入声明 `maintain="start"`，阶段内也持续检查。上游输出的递归依赖继承这个范围。原有命令依赖仍独立存在，命令成功只释放其自身的监控；没有 `fac:hold` 的模型保持原有语义。

## Java 执行链

1. 原始 XML 校验扩展位置、字段及基数；跨模型校验输入/事实引用和匹配的边界错误事件。不能把拼错的安全声明当成注释忽略。
2. 子流程进入时，`FacilityHoldRuntime` 创建独立 UUID 和展开后的保持记录，登记监控后核验入口。任何不成立条件都在执行内部任务之前进入错误路径。
3. `FacilityDependencyRuntime` 复用逐设备观测、有效期和三值判定，投影变化回调只锁存失效，不调用 Flowable。即使条件在下一次轮询前恢复，也不覆盖已观测失效。
4. `WorkflowService` 在命令和完成等待推进之前检查保持范围，并周期补读观测。设备任务回调、普通服务任务入口和结束事件再次核验。正常退出前的最后一次检查若发现新失效，则回滚退出事务，由协调器对恢复后的子流程执行异常路径。
5. 失效时先保存子流程内的命令、依赖和等待证据，再由引擎事务调用 `ErrorPropagation`，触发 `errorRef` 对应的中断型边界事件。该子流程的任务、分支和计时器取消，正常出口不会执行；边界后的业务任务决定如何记录失败或处置。示例直接调用 `shotRuns.fail` 结束本发。
6. 子流程全部分支正常结束时释放监控。循环再次进入建立新 ID。取消或全局中止也释放并保留终态。后端监控覆盖丢失时，即使恢复后的快照健康，也按 unknown 处理，不能据此证明中断期间持续有效。

中断子流程不会自动向设备发送停止命令，也不会伪造下游设备故障。已下发但尚无终态的动作保留为 `unresolvedDeviceActions`；已经验收成功的动作继续保留成功证据。异常处理需要的持续条件必须有自己的显式保持范围。

现有全局通信完整性/设备重启/整发超时中止策略仍独立适用。阶段失效优先检查和路由，但全局故障不会因进入异常路径而被自动解除。

## 观察与试验

运行 API 新增 `holds`，包含本次激活 ID、子流程、起止时间、outcome、valid/violated/unknown、具体依赖、路径和观测来源。终态 outcome 包括 released、failed、cancelled、interrupted。`shotRun.evidence` 中对应 `stage-hold`；`holdFailure`、`holdFailureReason` 供异常路径读取。被中断的完成等待仍在 `waits` 中保留。

运行调试页的等待面板显示活跃保持范围，子流程节点详情显示依赖链与失效原因，执行证据区保存结果。

在模型库选择 `gxlf_stage_hold`，建立种子源、同步、预放、多程各一台的模拟环境，设置束线 `[1]`、默认光源 `seed`。开启自动成功反馈后，模型自行上电、自检、接收参数，建立重频、唤醒和出光，执行准直、等待 5 秒及观测复核，退出保持阶段后切换到单次模式。保持阶段内对种子源注入 `emission_lost_confirmed`，或者使同步退出重频，可观察边界异常路径。故障测试使用真实 Java 模拟器 `ReportEvent` 或设备动作，经 JTango 与投影触发失效。

这是技术验证阶段划分，未声称是业务说明中全部准直安全要求的最终落实。特别是门禁、人员清场、吹扫、真实端口光功率及联锁处置仍需外部观测和设备接口。

## 支持范围与验证

支持普通内嵌子流程中的多个任务、并行分支、计时等待、完成条件等待及顺序循环重入。暂不支持保持子流程内再嵌套子流程、事件子流程、事务/补偿、多实例/异步活动、内部边界事件、脚本/发送/人工无等待任务、特殊结束事件或隐式出口；校验会拒绝这些组合。`callActivity` 仍遵循现有拒绝策略。每个保持子流程必须有唯一匹配的边界错误事件及异常出口。

执行 `mvn -Pnew-chain-e2e verify`。模型和运行测试覆盖引用/声明拒绝、入口失败、跨任务/计时等待失效、并行分支独立结束、正常释放后切换模式、循环重新建立范围、所选 SHG 与同步递归依赖、unknown、瞬时失效锁存、监控覆盖丢失和取消后的未决物理动作。覆盖丢失测试清除运行时监控来验证保守路径，完整后端崩溃恢复测试仍是后续工作。
