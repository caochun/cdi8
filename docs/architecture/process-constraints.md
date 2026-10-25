# 操作流程与实验系统状态

流程模型回答“要执行哪些操作、按什么顺序、如何处理等待和失败”；系统状态模型回答“整个实验系统当前处于什么状态、该状态必须由哪些设备事实支持”。两者分开建模，操作推进系统状态，设备观测持续验证系统状态。

两种模型的设计依据与职责边界见[操作模型与系统状态声明模型](operational-and-declarative-models.md)。

## 三层约束

| 层 | 建模位置 | 责任 |
|---|---|---|
| 设备本地约束 | 组件 SCXML | 单设备动作在什么状态和条件下可受理；忙闲互斥；设备故障联锁。任何 Tango 调用方都受约束。 |
| 操作流程 | BPMN | 操作次序、并行/汇合、参与设备、等待、超时、动作结果核验，以及何时报告系统状态转换。 |
| 实验系统状态 | 独立 `.system.xml` | 跨设备的高层状态、合法转换，以及每个状态必须持续成立的设备状态谓词。 |

流程位置不等于系统状态。BPMN 的汇合只说明令牌到达汇合点，不能证明设备已满足实验状态；状态转换报告会在汇合后读取 Tango 当前快照，并核对声明模型里的目标状态谓词。设备自身动作准入仍由 SCXML 决定，系统状态不会替代或放宽它。

## 声明格式

`examples/gxlf/system/gxlf-experiment.system.xml` 定义当前 GXLF 实验系统状态模型：

```xml
<system-state-model xmlns="https://cdi8.cn/system-state/v1"
                    id="gxlf-experiment"
                    initial="uninitialized"
                    evidenceMaxAgeMs="2000">
  <state id="uninitialized" label="未初始化" />
  <state id="powered" label="已上电">
    <require collection="allComponents" component="seed"
             states="powerOnChecked" outerStates="ready" />
    <require collection="allComponents" component="shg"
             states="powerOnOk" outerStates="ready" />
  </state>
  <transition id="powerChecksVerified"
              from="uninitialized" to="powered" />
</system-state-model>
```

`require` 对集合中指定组件的每台设备都成立；`states` 与 `outerStates` 是“或”关系。集合为空、集合里没有该组件、设备快照缺少来源 ID 或修订号，均不能作为有效证据。初始状态也可以声明设备谓词，以便在操作开始后立即持续监控；未声明谓词的初始状态只记录流程给定的起点。其他状态必须包含设备谓词。状态转换只能引用模型中已声明的状态。

十四类设备的全生命周期声明位于 `examples/gxlf/system/gxlf-experiment.system.xml`，新增以下语法：

```xml
<fact id="light" label="全部所选光源稳定">
  <require collection="sourceTargets" component="seed shg" states="stableEmission" />
</fact>
<state id="prepared" label="准备完成">
  <use fact="light" />
  <require collection="allComponents" component="pump"
           states="emissionPrepared" scope="entry" />
</state>
<transition id="ready" from="preparing" to="prepared"
            operation="全部准备支路汇合" blocked="尚缺双通道同步证据" />
```

`fact` 只包含 `require`，`use` 展开为这些条件的 AND。`component` 支持空格分隔的候选组件，集合里所有匹配实例都要满足；选择一个或两个光源均可。默认 `scope="invariant"` 是持续条件，`scope="entry"` 只在转换进入时核验。非初始状态必须至少有持续条件，不能只有进入条件。未定义事实引用、重复定义、未知 scope 或空事实均拒绝。

转换先以事件和快照验证源状态的持续条件，再以本次快照验证目标的全部条件；不会用目标条件回放进入前的中间状态。同次转换每台设备的源、目标检查复用一份快照。`blocked` 非空时拒绝推进并保留有效源状态；`operation` 只作说明。前端预览分别展示进入/持续条件及阻断原因。

BPMN 在 process 级引用声明，并在操作节点报告转换或要求当前状态：

```xml
<bpmn:extensionElements>
  <sys:systemModel ref="gxlf-experiment" />
</bpmn:extensionElements>
<bpmn:serviceTask id="report_powered"
  flowable:expression="${systemStates.report(execution, 'powerChecksVerified')}" />
<bpmn:serviceTask id="function_check"
  flowable:delegateExpression="${componentCommand}">
  <bpmn:extensionElements>
    <sys:requires state="powered" />
  </bpmn:extensionElements>
</bpmn:serviceTask>
```

组件初始化先并行完成四类设备的上电检查，汇合后报告 `powered`；系统运行时核对四类设备快照后才记录转换。功能检查节点要求 `powered`，运行时在下发前重新读取并核验系统声明状态。所有功能检查完成后，再报告 `initialized`。

## 运行语义与失效

运行实例保存当前系统状态、转换历史、完整性（`valid`、`violated` 或 `unknown`）、失效原因和设备证据。部署时把所引用系统模型 XML 固定到 Flowable deployment resource，运行重启后仍从该部署恢复相同声明。

工作流推进器处理设备事件，并按 `evidenceMaxAgeMs` 的一半间隔刷新 Tango 快照。已观察到的短暂不符合状态、状态版本倒退、设备实例更换、事件队列缺口、断连或权威读取失败，都会锁存为失效或未知并停止流程。之后设备恢复不会清除本次运行的锁存结果。状态转换报告和组件命令下发使用同一运行时监视器，避免与本地事件检查并行穿越。

这提供控制端的持续监控和证据门控，不构成跨 Tango 设备的原子快照或实时安全联锁。安全关键的快速联锁和已受理动作的停止仍必须由设备或独立保护系统执行；流程失败也不代表设备动作已停止，未确认命令仍通过 `unresolvedDeviceActions` 暴露。

## 设备动作和 BPMN

组件 SCXML 依据请求迁移及 `cond` 守卫决定动作是否可受理，并提供设备原生 `actionContract` 就绪结果。BPMN 的 `act:actionContract` 引用这一原生契约；可选 `act:entryCondition` 只能进一步收紧当前任务目标的状态和空闲条件。工作台先检查，设备也会在受理事务内复核。命令结果、期望状态和设备实例关联仍由通用组件命令运行时验证。

跨设备系统谓词不应重复写成多个任务上的隐式门控列表；应在系统状态声明中定义，由操作流程在正确的阶段要求或报告状态。动作是否允许则继续留在设备模型。这样可以区分“当前动作在设备上是否合法”与“实验系统是否已达到流程要求的整体状态”。

旧 `https://cdi8.cn/control/qualification/v1` 下的 `qualificationPolicy`、`qualification`、`accept`、`establish`、`require` 不再执行，也不提供兼容解析。模型验证遇到这些声明会要求迁移到 `system-state-model`；动作契约已独立位于 `https://cdi8.cn/control/action-contract/v1`。

## 验证

当前暂不保留自动化测试用例；后续重新建立测试时，应覆盖系统状态证据、设备动作准入和 Java/Tango 联动。
