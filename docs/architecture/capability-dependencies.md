# 设施能力的输入依赖

系统定义声明跨设备的依赖规则；设施状态投影声明观测如何映射为事实；发次选择束线和光源；运行协调器检查并监控当前动作的依赖。BPMN 调用能力时自动采用其系统契约，无须重复罗列上游设备。

`path` 本身只表示连接。依赖由能力明确声明，不能从拓扑推断“下游任何动作都要求全部上游保持工作”。设备自身动作准入仍由 SCXML 处理。

## 声明规则

以下内容属于 `system-definition`，不是状态投影。示例均来自 GXLF 设施定义。

```xml
<!-- 位于 componentType/capabilities 下 -->
<capability id="collimation" label="光路准直" kind="control" actionRef="collimation">
  <requirements>
    <inputRequirement id="incident-light" pathRef="optical-path" inputPortRef="optical-in"
                      signalRef="alignment-light" maintain="execution" />
  </requirements>
</capability>

<!-- 位于预放 componentType/ports 下 -->
<port id="optical-out" direction="out" interfaceRef="optical-link">
  <outputs>
    <output id="alignment-light" signalRef="alignment-light" factRef="preampAwake">
      <requirements>
        <inputRequirement id="incident-light" pathRef="optical-path" inputPortRef="optical-in"
                          signalRef="alignment-light" />
        <inputRequirement id="repeat-clock" pathRef="trigger-path" inputPortRef="command-in"
                          signalRef="preamp-repeat" />
      </requirements>
    </output>
  </outputs>
</port>
```

`requirements/inputRequirement` 指定路径、输入端口、所需信号。`outputs/output` 指定输出信号、提供者自身的事实及其进一步依赖的输入。信号必须在 `signals/signal` 中声明。多个输入要求为 AND；每条束线的每项输入必须唯一选路。上游输出的依赖递归展开，输出依赖存在环、端口/信号不存在、路径不匹配、未知属性均拒绝建模。三个模型的完整结构和校验约定见[设施 XML 模型约定](facility-xml-models.md)。

能力依赖的 `maintain` 必填：

| 值 | 有效范围 |
|---|---|
| `start` | 在命令下发前检查；下发后不再要求该条件保持 |
| `execution` | 从命令下发前检查开始，持续到该命令结果及目标状态验收结束 |

`output/requirements` 内部的 `inputRequirement` 继承使用者的保持范围。能力执行成功后依赖释放；同一 BPMN 活动中的下一个设备命令建立自己的依赖。后续 `fac:completion` 等待不延长已经结束的设备动作依赖。跨任务、分支和等待的保持范围通过 BPMN 子流程 `fac:hold` 显式声明，复用这里的输入契约和递归选路，见[阶段持续保持](stage-hold.md)；不能把单次 `execution` 解释为整发持续约束。

`alignment-light` 特指准直/预放闭环使用的光路条件。它不代表正式发射模式下的所有光学输出，也不要求重频模式永久保持。没有声明输入依赖的上电检查、参数接收等能力，不自动获得光路约束。

## 事实映射

设施状态投影新增 `deviceFacts`，只声明单设备观测判据：

```xml
<deviceFacts>
  <deviceFact id="seedEmitting" typeRef="seed" label="种子源保持出光" outerStates="emitting" />
  <deviceFact id="preampAwake" typeRef="preamp" label="预放保持唤醒" outerStates="wakeup" />
</deviceFacts>
```

`states` 与 `outerStates` 为可选状态集合，二者至少声明一个，匹配任一即满足。使用复合状态允许设备在保持出光/唤醒的同时进入其其他子状态。观测不存在、过期、断连、来源变化或修订倒退均为 unknown，不能建立有效输入。系统模型引用的事实必须存在且组件类型一致；在激活模型组时交叉校验。

当前 GXLF 使用四个事实：种子源出光、二倍频出光、预放唤醒、同步重频模式。设备状态表示模型确认的业务条件；当前没有另外接入端口光功率等实测传感器证据。

## 实例和本发选路

系统定义用 `instanceSet` 的数量和 `{index}` 模板展开逻辑实例；`mappings/mapping` 统一声明每套预放覆盖六束、一对一设备及前八束可选二倍频的规则。绑定通过 `mappingBinding mappingRef` 引用，不再重复数量、选源范围或分组公式。逻辑实例通过显式 `runtimePattern="preamp_{index}"` 对应模拟环境的设备编号；解析器不从运行设备编号猜测组件类型。

现场或其他模拟环境可在运行配置根对象中明确覆盖实例绑定，例如：

```json
{
  "facilityInstances": {"multipass-01": "actual-device-A"},
  "variables": {
    "beamlines": [1, 7],
    "defaultSource": "seed",
    "sourceSelections": {"1": "shg"}
  }
}
```

设备必须存在且组件类型匹配，同一设备不能绑定为两个逻辑实例。缺少本动作实际依赖的实例时阻止下发，不能改用同类任意设备。`sourceSelections` 只允许本发所选束线；前八束可选二倍频，其余必须种子源，范围来自连接所引用的 `source-selection` 映射。运行 API 也接受配置根对象的 `sourceSelections`。

束线 01 准直解析为预放 01、同步 01 和本发选择的光源。束线 07 使用预放 02、同步 07。对共享预放发起动作时，展开本发所选且由该预放服务的全部束线依赖。未选光源、其他预放组的业务状态失效不会被混入这项能力检查；已有的全局通信完整性中止策略仍独立适用。

## 运行与失效

1. 展开设备动作时建立独立依赖记录，关联同一 command ID，解析本发绑定、实际路径和逐实例事实来源。
2. 协调器在 Execute 前检查。非 valid 时结束流程，记录 blocked，设备命令不下发。
3. 命令执行期间，投影事实变化立即触发检查；事件处理只记录失效，不调用 Flowable。协调器还周期补读快照并重新评估观测有效期。
4. 一旦观测到 continuous 依赖失效或 unknown，就为本次命令锁存。即使后续恢复，仍不能覆盖之前发生的失效。
5. 命令返回成功后再次核验依赖，Flowable 回调也再次检查，然后释放本次监控。设备命令结果与依赖证据分别保存。
6. 失效时停止流程推进，保留失效束线、上游逻辑实例、信号、事实、路径以及 source_id/state_revision。已下发但无终态的设备动作仍标为 unknown。

依赖记录保存在 Flowable 本地变量 `facilityDependencies`；结束记录保存到 `dependencyHistory`，对外通过运行 API 的 `dependencies` 和 `shotRun.evidence` 中的 `capability-dependency` 提供。工作台节点详情显示输入、保持范围和失效原因。循环重入使用新的 command ID。

后端重启后，已下发且具有持续依赖的命令因监控覆盖中断而按 unknown 终止，不用恢复时的健康快照证明停机期间持续有效。短暂失效锁存在内存中，协调器检查时持久化；重启后的保守处理覆盖这段持久化间隙。

## 实现范围

当前 GXLF 接入预放唤醒的同步输入、粗/精闭环的光源及同步输入，以及多程准直经预放递归依赖光源和同步的链路。业务说明中的服务健康、安全联锁、吹扫停止等其他条件尚未全部接入，不能把通过本切片解释为具备全部实验安全条件。

停止流程推进不等于物理停机。本次未实现联锁控制器广播、紧急泄放/停充或设备终止命令的确认闭环。软件观测也不能证明两个观测之间从未失效；对实时安全有要求的保护需要设备与联锁配合。

## 验证

执行 `mvn -Pnew-chain-e2e verify`。依赖单元测试覆盖选源、预放分组、缺失绑定、循环依赖拒绝、事实类型校验、失效后恢复仍锁存、过期/旧修订、只在开始检查、结束释放和重启后的监控间隙。

真实 Java JTango E2E 使用设备命令完成上电、功能、参数、出光、预放唤醒和同步重频配置；BPMN 只调用准直能力，验证自动依赖检查、下发前拦截、执行中光源丢失、替代选源以及命令结束后的依赖释放。光源丢失通过模拟器 `ReportEvent(emission_lost_confirmed)` 注入，设备状态随后经 Tango 事件进入设施投影，测试不直接修改投影事实或 Flowable 变量。
