# BPMN 活动完成条件

## 模型分工

BPMN 声明某个活动要执行的动作、进入条件和完成条件。设施状态投影依据当前设备及外部观测计算事实、组合 guard。运行协调器关联本发命令与当前等待活动，决定何时调用 Flowable `trigger()`。投影运行时不持有流程执行 ID，也不直接调用 Flowable。

| 条件 | 表达 | 当前运行语义 |
|---|---|---|
| 进入条件 | `fac:requires guardRef="…"` | 进入 serviceTask 前检查；不为 valid 则失败 |
| 完成条件 | `fac:completion factRef="…"` 或 `guardRef="…"` | 活动的全部设备命令验收成功后检查；不成立则等待，超时失败 |
| 能力输入保持条件 | 系统定义中的 `requirements/inputRequirement maintain="execution"` | 持续到该设备命令验收结束，失效或 unknown 结束流程；不延长到后续完成条件等待，详见[能力依赖](capability-dependencies.md) |
| 阶段持续保持条件 | 子流程 `fac:hold` | 跨任务、分支和等待监控，失效走边界错误路径；详见[阶段保持](stage-hold.md) |

完成条件与本发命令是合取关系。开始本发之前已经成立的设施事实，不能替代本次命令的成功结果。单独的条件等待任务可以复用已经成立且当前仍有效的事实。

## 声明格式

以下活动要求本次绑定的上电检查命令全部成功，并且设施事实 `allPowerChecked` 当前为 valid：

```xml
<bpmn:serviceTask id="power_check" name="全部设备上电检查"
                  flowable:delegateExpression="${facilityActions}">
  <bpmn:extensionElements>
    <run:stage value="initializing" />
    <fac:completion factRef="allPowerChecked" timeout="PT2M" />
    <fac:actions>
      <fac:action typeRef="seed" capabilityRef="power-on-check"
                collectionVar="seedTargets" expectedStateRef="powerOnChecked" />
      <!-- 其他参与组件的 fac:action -->
    </fac:actions>
  </bpmn:extensionElements>
</bpmn:serviceTask>
```

命名空间分别是 `https://cdi8.cn/facility/v1`、`https://cdi8.cn/shot-run/v1`。一个任务最多声明一个 `fac:completion`；`factRef` 与 `guardRef` 必须二选一，且引用已经定义的名称。多个事实的组合规则写在投影的 guard 中。`timeout` 必填，使用至少 1 毫秒的正 ISO-8601 时长。

也可以声明纯等待任务，不发送设备动作：

```xml
<bpmn:serviceTask id="wait_trigger_conditions" name="等待触发条件"
                  flowable:delegateExpression="${facilityActions}">
  <bpmn:extensionElements>
    <fac:completion guardRef="triggerAllowed" timeout="PT5M" />
  </bpmn:extensionElements>
</bpmn:serviceTask>
```

这仅表示等待设施条件；是否需要本发充电、时序和其他证据，由前面的动作活动及其完成检查表达。当前外部安全观测和服务心跳尚未接入，因此示例中的 `triggerAllowed` 不会自动放行。

没有 `fac:completion` 的动作任务仍以全部命令通过目标状态验收为完成标准。带完成条件的 serviceTask 必须使用 `${facilityActions}`；其他任务类型、普通表达式任务、重复声明、未知属性及无效引用会在模型校验时被拒绝。

## 运行过程

1. 进入活动时检查 `fac:requires`，写入 `run:stage`，展开设备动作队列。
2. 按实例发送命令并等待终态；校验本次 command ID、action、source ID、无活动命令和目标状态。每个成功结果先持久化，再推进队列。
3. 队列全部成功后，登记 `facilityCompletion` 等待记录，包括独立 ID、活动 ID、事实/guard 引用、开始时间及绝对截止时间。纯等待任务在进入时登记。
4. 协调器主动检查一次条件。投影的事实/guard 变化发布 `FacilityStateRuntime.Changed`，只将协调器标为需要重新检查；定时补查覆盖通知遗漏和已经成立的条件。
5. 每次检查补读设备快照，再评估指定条件。`valid` 才可推进；`violated` 或 `unknown` 保持等待。全局设施 integrity 为 unknown 不会屏蔽一个独立、有效的完成条件。
6. 满足时保存此次条件核验的事实快照和观测来源，再由协调器调用 `runtime.trigger(executionId)`。Flowable 回调会再检查最新条件和截止时间，避免使用已经失效的通知。成立才离开活动；否则保持原等待并失效此次放行证据。
7. 超时、取消和运行故障结束等待，保存等待原因、最后一次条件评估及本发失败信息。成功的设备命令证据继续保留，等待本身不会重新发送动作。

当前 `trigger()` 有两个内部用途：完成一个设备命令后推进当前活动的设备队列；完成设施条件等待后离开活动。设备队列排空时，带 `fac:completion` 的活动会停留到第二种触发发生。

完成条件的 timeout 从全部命令验收成功、进入条件等待时开始；动作超时仍由 `commandTimeout` 控制，整发由 `workflowTimeout` 控制。等待截止时间持久化，不因事件、补查或循环检查而延长。流程重新进入同一活动，会创建新的命令及等待 ID。

## 证据与调试

设备命令结果继续以 `command-result` 写入 `shotRun.evidence`。完成条件核验使用 `facility-fact` 或 `guard`，`details.purpose` 为 `activity-completion`，包含条件引用、活动、等待 ID、时间、设施投影快照和设备 source_id/state_revision 观测。它记录核验时刻的条件，不表示条件此后始终成立。

运行接口的 `waits` 返回当前完成条件、valid/violated/unknown、原因和截止时间。异常结束后保留结束时的等待记录。运行调试页显示中文条件名称、等待原因和剩余时间；设施事实不能通过等待面板直接改为 true。

GXLF 初始化切片三个完成条件分别是 `allPowerChecked`、`allFunctionReady`、`allParametersReceived`。包含两个预放实例的端到端场景完成 43 个设备动作，另保存 3 条完成条件证据。

## 当前边界

- 设施事实仍按绑定到环境的组件实例集合计算。`fac:completion` 不会把全设施事实自动缩小到某个 `collectionVar` 或束线选择；按本发资源范围展开仍属于后续工作。
- 设备失联、来源变化、修订号回退仍遵循已有的中止策略；完成条件的 unknown 等待语义不会覆盖这些故障策略。
- 进入/完成时的检查不构成硬件联锁或持续安全保证。单次能力输入监控和显式子流程阶段保持已实现；现场联锁处置、外部观测接入仍未实现。
- 等待记录与绝对期限由 Flowable 持久化，后台可重新发现并补查；后端进程重启恢复仍需独立的完整故障注入测试。
- 本轮不增加 CI 工作流。验证使用 `mvn -Pnew-chain-e2e verify`。
