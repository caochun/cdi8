# 模拟设备接口契约

此目录描述 Java 工作台、Java bridge 与 Tango 模拟设备之间的项目协议。JSON Schema 是可审查的格式说明；运行时在 Java 中执行相应检查，没有通过通用 JSON Schema 引擎校验请求。

| Schema | 对应接口 / 数据 |
|---|---|
| [command.schema.json](command.schema.json) | `POST /devices/{id}/commands` / Tango `Execute` 的请求 |
| [command-result.schema.json](command-result.schema.json) | 命令受理、查询与反馈结果 |
| [device-event.schema.json](device-event.schema.json) | `GET /events` 的 NDJSON 消息，每行一个 JSON 对象 |

## 命令生命周期

`command_id` 为 1–128 个字母、数字、下划线或连字符。同一编号与相同动作、参数的重复请求返回既有结果；不同内容不能复用编号。parameters 可省略，设备按空对象处理。

Execute 受理后返回 accepted。动作成功、失败和故障中断由后续反馈决定；受理不代表完成。结果携带原命令 ID 和动作名。总控收到设备事件后查询结果，并重新检查当前设备状态。

### 设备动作契约

动作元数据只将业务动作映射到一个具体请求事件，例如：

```json
"function_check": {"label": "功能检查", "request_event": "function-check.requested"}
```

允许受理的状态和条件直接写在 SCXML 中：

```xml
<state id="powerOnChecked">
  <transition event="function-check.requested" />
</state>
```

这是无目标迁移，受理请求时保持当前业务状态，后续成功/失败反馈再推进状态。需要在请求时改变状态的动作可声明带目标的迁移；多个子状态共同支持的请求可声明在复合父状态中。动作元数据不再另列允许状态。

Java 运行时统一要求没有活动命令，并调用 Apache SCXML 的迁移选择算法，根据当前状态、祖先状态、事件描述符及 `cond` 判断请求是否启用。没有启用迁移时，命令接口明确返回 `rejected`，不把引擎忽略事件视为受理。受理依据请求迁移实际执行，而非状态名称是否变化。这套规则适用于所有调用者，不依赖 BPMN 或工作台发送保护参数。

快照声明 `supports_action_contract=true`，并返回按动作名索引的当前契约，例如：

```json
{
  "supports_action_contract": true,
  "action_contracts": {
    "function_check": {
      "label": "功能检查",
      "entry": {"idle": true, "event": "function-check.requested"},
      "ready": false,
      "reason": "当前状态或守卫不接受动作：function_check，当前状态为 statusUnknown"
    }
  }
}
```

`entry.event` 来自动作元数据的 `request_event`，`entry.idle` 表达运行时互斥；不再返回允许状态列表。`ready` 采用与真实执行相同的 Apache 迁移选择和守卫求值，只查询，不执行迁移内容、状态退出/进入或动作回调。查询时临时提供该请求的 `_event`，结束后还原，求值失败也不会给真实执行器遗留 `error.execution` 事件。守卫求值无法确认时 `ready=false`，原因说明无法确认。

为保持守卫为无副作用的谓词，模型加载时拒绝 `cond` 中的赋值、自增、对象方法调用、对象构造和 lambda；数值/布尔比较、字段读取及标准 `In()` 状态判断继续支持。执行副作用应写在迁移可执行内容中，而非守卫中。

工作台对声明 `<ctl:actionContract />` 的任务，按该任务实际 `action` 权威读取设备就绪结果；支持能力缺失、契约缺失、就绪类型错误或 false 都阻止下发。设备仍在 Execute 的本地事务中复核，控制端的读取不能预约设备或替代本地准入。

自身准入不满足时设备持久化 `status=rejected` 与原因，不改变业务状态和活动命令，并允许 GetResult 查询该结果。未知动作、非法格式或同一命令编号内容冲突仍作为非法请求处理。

旧模型的 `actions.<action>.requests` 状态表不再参与运行；校验会提示改为 `request_event` 并在 SCXML 中补充请求迁移。四组件内置示例及最小模板已迁移，模型库中已保存的旧模型不会自动改写。成功/失败反馈事件映射仍保留，用于选择动作反馈的事件，不作为请求准入表。

### 实验附加进入条件

可选的 `parameters.entry_condition` 声明实验在设备规则之外额外要求的本机条件。它与设备自身准入取交集，只能收紧，不能放宽：

```json
{
  "command_id": "initialization-seed-power",
  "action": "power_on_check",
  "parameters": {
    "entry_condition": {"states": "statusUnknown", "outer_states": "", "idle": true}
  }
}
```

`states` 和 `outer_states` 为用空白分隔的允许状态集合，两者为“或”；此外必须要求设备空闲。Java 设备在与动作受理相同的同步锁、数据库事务内执行检查，然后再执行 SCXML 的动作受理检查。条件不满足时持久化 `status=rejected` 及原因，且不改变设备业务状态，后续 GetResult 可查询该终态；字段类型不正确或包含未支持字段时拒绝非法请求。该检查只针对受理瞬间，不代替执行期间的持续保护。

组件快照通过 `supports_entry_condition=true` 声明能力。工作台对模型中带有 `ctl:entryCondition` 的动作先权威读取并检查当前状态和空闲条件，再将相同条件通过 bridge 和 Tango Execute 传给设备；设备不支持时阻止下发，不忽略声明。

幂等检查先于设备准入和附加条件检查：相同编号、动作及参数的请求返回已有结果，即使设备已经离开最初允许的状态；被拒绝的命令也不能因设备后来满足条件而变成受理。工作台对引用设备动作契约或声明附加条件的命令，在发送前持久化 `dispatchAttempted`，并以 `deviceActionContract` 记录契约引用。如果受理回复丢失，恢复后只查询该编号，不重新发送动作；查询失败时保持受通信错误或 BPMN 超时约束的等待，只有设备明确返回 `rejected` 等命令终态才作为动作结果。HTTP/Tango 通信错误不等同于命令已拒绝。尚未发送的普通命令保留既有协议行为。

流程接口的 `unresolvedDeviceActions` 列出流程已结束时，已尝试发送但运行记录中没有终态结果的命令。这不是设备停止确认，也不表示设备此刻一定仍在执行；工作台运行页提示按规程核实，历史查询不使用新的设备读取覆盖当时的证据。

## 设备快照与事件

组件快照包含模型身份、当前内外层状态、活动命令、上下文与最近结果。工作台环境的快照还可含 model_revision，指向实际使用的不可变版本。条件设备只需要提供 conditions 和活动命令等字段，不强制拥有组件状态。

参与 BPMN 资格检查的组件快照还必须提供 `source_id` 和整数 `state_revision`。`source_id` 标识当前设备运行时实例，每次重新实例化时改变；`state_revision` 标识状态变化序号。Java 模拟器提供这些字段。工作台利用二者识别设备重启、状态回退和旧通知；实际放行前通过 Tango `DevSource.DEV` 直接读取设备状态。

工作台的 Tango 客户端在事件流中断或心跳超时时向内部监听器发布 `connection_lost`，用于锁存已建立资格的失效；该通知不是要求 bridge 发送的新协议消息。

事件流由 snapshot、device_error、ready 和 heartbeat 组成。重新连接时先补发当前设备快照，再发送 ready。事件流用于唤醒和状态补读，命令最终结果仍以 GetResult 查询为准。

## 实现位置

- [Java 客户端](../server/src/main/java/cn/cdi8/workbench/integration/tango/TangoGateway.java)
- [Java Tango 桥接](../device-simulator/src/main/java/cn/cdi8/simulator/bridge/JavaControlBridgeApplication.java)
- [Java 组件运行时](../device-simulator/src/main/java/cn/cdi8/simulator/runtime/ComponentRuntime.java)

`componentMetadata` 的 actions、confirmations、context_defaults 等属于项目组件模型协议。Java 组件运行时解释这些绑定；采用标准 SCXML 引擎不会自动实现命令幂等、设备反馈和历史持久化。`action_contracts`、`entry_condition` 及能力字段也是本项目协议，并非 Tango 标准命令或标准属性。
