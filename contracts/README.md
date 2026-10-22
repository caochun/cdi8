# 模拟设备接口契约

此目录描述当前 Python 服务与工作台共同使用的协议，供 Java 模拟设备迁移时复用。JSON Schema 是可审查的格式说明；本次目录重组没有将新的 Schema 校验接入现有请求路径。

| Schema | 对应接口 / 数据 |
|---|---|
| [command.schema.json](command.schema.json) | `POST /devices/{id}/commands` / Tango `Execute` 的请求 |
| [command-result.schema.json](command-result.schema.json) | 命令受理、查询与反馈结果 |
| [device-event.schema.json](device-event.schema.json) | `GET /events` 的 NDJSON 消息，每行一个 JSON 对象 |

## 命令生命周期

`command_id` 为 1–128 个字母、数字、下划线或连字符。同一编号与相同动作、参数的重复请求返回既有结果；不同内容不能复用编号。parameters 可省略，设备按空对象处理。

Execute 受理后返回 accepted。动作成功、失败和故障中断由后续反馈决定；受理不代表完成。结果携带原命令 ID 和动作名。总控收到设备事件后查询结果，并重新检查当前设备状态。

## 设备快照与事件

组件快照包含模型身份、当前内外层状态、活动命令、上下文与最近结果。工作台环境的快照还可含 model_revision，指向实际使用的不可变版本。条件设备只需要提供 conditions 和活动命令等字段，不强制拥有组件状态。

参与 BPMN 资格检查的组件快照还必须提供 `source_id` 和整数 `state_revision`。`source_id` 标识当前设备运行时实例，每次重新实例化时改变；`state_revision` 标识状态版本。Java 和 Python 模拟器均提供这些字段。工作台利用二者识别设备重启、状态回退和旧通知；实际放行前通过 Tango `DevSource.DEV` 直接读取设备状态。

工作台的 Tango 客户端在事件流中断或心跳超时时向内部监听器发布 `connection_lost`，用于锁存已建立资格的失效；该通知不是要求 bridge 发送的新协议消息。

事件流由 snapshot、device_error、ready 和 heartbeat 组成。重新连接时先补发当前设备快照，再发送 ready。事件流用于唤醒和状态补读，命令最终结果仍以 GetResult 查询为准。

## 实现位置

- [Java 客户端](../server/src/main/java/cn/cdi8/workbench/integration/tango/TangoGateway.java)
- [Python Tango 桥接](../simulator/gxlf_component_simulator/tango/control_bridge.py)
- [当前组件运行时](../simulator/gxlf_component_simulator/component_runtime.py)

`componentMetadata` 的 actions、confirmations、context_defaults 等属于项目组件模型协议。它们的执行解释当前仍在 Python 适配层；采用标准 SCXML 引擎不会自动实现命令幂等、设备反馈和历史持久化。
