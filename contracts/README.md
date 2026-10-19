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

事件流由 snapshot、device_error、ready 和 heartbeat 组成。重新连接时先补发当前设备快照，再发送 ready。事件流用于唤醒和状态补读，命令最终结果仍以 GetResult 查询为准。

## 实现位置

- [Java 客户端](../server/src/main/java/cn/cdi8/workbench/integration/tango/TangoGateway.java)
- [Python Tango 桥接](../simulator/gxlf_component_simulator/tango/control_bridge.py)
- [当前组件运行时](../simulator/gxlf_component_simulator/component_runtime.py)

`componentMetadata` 的 actions、confirmations、context_defaults 等属于项目组件模型协议。它们的执行解释当前仍在 Python 适配层；采用标准 SCXML 引擎不会自动实现命令幂等、设备反馈和历史持久化。
