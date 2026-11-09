# 设备与监督接口契约

设备协议、监督接口和发次记录是三个边界。工作台通过监督后端请求生产动作，监督端验证观测、事实和义务后，通过进程内 TangoAdapter 调用设备；设备再次执行本地准入。

```text
工作台 / Flowable → 监督 HTTP API → 内嵌 TangoAdapter → 设备 Execute
                    权威设施准入                 本地 SCXML 准入
```

本目录由原根目录 `contracts/` 合并而来，保存接口资料。本目录的 JSON Schema 是格式说明；运行时使用 Java 校验，没有通用 JSON Schema 请求校验器。参与构建的共享模型代码位于 `component-contract` 与 `facility-contract`。

| 文件 | 覆盖范围 |
|---|---|
| [command.schema.json](command.schema.json) | Tango `Execute` 请求（设施端准入后下发） |
| [command-result.schema.json](command-result.schema.json) | 设备命令受理、查询和终态结果 |
| [device-event.schema.json](device-event.schema.json) | 设备观测通知对象；设施 `GET /api/facility/events` 将它作为 NDJSON 发出 |
| [shot-run.schema.json](shot-run.schema.json) | 本发身份、阶段、所选资源和证据记录；包含目标设计字段，不表示预约等接口已实现 |

## 监督命令边界

生产动作使用 `POST /api/facility/devices/{device}/commands`，结果查询使用同路径的 `GET /{command_id}`。请求包含设备协议中的 `command_id`、`action`、`parameters`，可以附加 `admission` 选择上下文：

```json
{
  "command_id": "shot001-seed-power",
  "action": "power_on_check",
  "parameters": {},
  "admission": {
    "beamlines": [1],
    "selections": {"1": "seed"},
    "defaultSource": "seed",
    "instances": {}
  }
}
```

`admission` 描述本发束线、选源和实例绑定，监督端从自己的设施模型解析能力并重新核验，调用者不能提交“已通过”的准入证据。监督端拒绝观测通道不可用、活动义务失效或能力依赖不成立的命令。准入失败发生在下发之前，以 HTTP 错误返回；设备执行过本地检查后返回的 `status=rejected` 则是可查询的设备命令终态。

监督端先持久化命令意图及证据，再调用设备。相同编号及相同请求返回已有结果或查询未决结果，不重复 Execute；改变设备或请求内容不能复用编号。结果含依赖计划时附加 `admissionEvidence`，记录持续依赖及锁存证据。设备 `succeeded` 本身不能证明设施持续条件始终满足，流程必须同时验收这些证据。

两个边界的编号校验尚不完全一致：监督入口接受字母、数字、`_ . : -`，当前设备命令 Schema 与模拟器接受字母、数字、`_ -`，均为 1–128 字符。贯通调用应使用两者共有的字母、数字、下划线和连字符。

监督事件为 `/api/facility/events` 的通知流，既有设备通知也有事实/监督变化，范围超出 `device-event.schema.json`；断流后应重新查询权威事实和命令结果。接口详情见[监督运行边界](../architecture/facility-runtime.md)。

## 设备命令生命周期与本地准入

设备请求的 `parameters` 可省略，按空对象处理。同一 `command_id`、动作和参数返回既有结果；不同内容不能复用编号。设备先检查幂等，再检查当前准入，已拒绝的编号不会在条件改善后变成受理。

Execute 返回 `accepted` 仅表示受理。后续反馈产生 `succeeded`、`failed`、`interrupted` 等结果；最终结果通过 `GetResult` 查询并核对命令编号和动作。通信错误不能作为命令已拒绝或已完成的证据。

`componentMetadata.actions` 将业务动作映射到 `request_event`，例如 `function_check → function-check.requested`。允许状态和守卫写在 SCXML 请求迁移中。运行时要求无活动命令，并通过 Apache SCXML 的迁移选择算法确认请求启用；无目标迁移可以表示受理但保持业务状态。

快照中的 `supports_action_contract=true` 和 `action_contracts.<action>` 提供当前本地就绪信息，包括 `entry.idle`、`entry.event`、`ready` 和失败原因。查询复用引擎选择和守卫求值，不执行迁移内容；无法确认时就绪为 false。设备 Execute 在本地事务内再次复核，读取就绪状态不能预约设备。

可选的 `parameters.entry_condition` 是设备协议保留的附加限制，与 SCXML 准入取交集：必须 `idle=true`，并提供空格分隔的 `states` 或 `outer_states`。它只约束受理瞬间。当前 BPMN 使用 `fac:operation`、`fac:completion`、`fac:hold` 和设施能力；`fac:requires` 仅用于旧模型迁移，旧 `ctl:actionContract` / `ctl:entryCondition` 扩展不属于现行模型。

SCXML 元数据静态校验位于 [ComponentContract](../../component-contract/src/main/java/cn/cdi8/component/contract/ComponentContract.java)；可执行性由模拟器的同一 Apache 引擎校验。组件协议要求单一活动叶子状态，不支持 `parallel`。元数据及运行时能力字段属于项目协议，并非 Tango 标准。

## 快照、观测和事件

组件快照包含模型身份、当前内外层状态、活动命令、上下文及最近结果。`model_revision` 标识已加载的不可变模型版本，`model_digest` 为 XML 的 SHA-256；`source_id` 标识本次设备实例，`state_revision` 是状态变化序号。条件设备提供 conditions 和活动命令等字段，不要求组件状态。

监督端验证来源、修订号、模型及新鲜度；缺失、过期、倒退和无效观测不能成为 valid 事实。放行前经 Tango `DevSource.DEV` 补读。模拟条件设备的布尔值不会自动替代真实外部联锁或资源服务。

内嵌适配库产生 `snapshot` 和 `device_error` 通知，设施网关补充 `ready` 和 `connection_lost`。工作台通过设施通知流接收快照和状态变化；Tango CHANGE_EVENT 不可用时补读快照。事件用于唤醒和协调，最终命令结果以 GetResult 为准。

## 实现位置

- [监督命令准入](../../facility-server/src/main/java/cn/cdi8/facility/runtime/FacilityCommandAdmission.java)
- [监督端设备网关](../../facility-server/src/main/java/cn/cdi8/facility/integration/TangoGateway.java)
- [工作台监督客户端](../../workbench-server/src/main/java/cn/cdi8/workbench/integration/facility/FacilityClient.java)
- [内嵌 Tango 适配库](../../tango-adapter/src/main/java/cn/cdi8/tango/TangoAdapter.java)
- [设备运行时](../../device-simulator/src/main/java/cn/cdi8/simulator/runtime/ComponentRuntime.java)

## 组件和设备标识

设备目录返回 `id`、`deviceBindingKey`、`address` 和 `snapshot`；Snapshot 的 `componentLabel` 是显示名称。设施事实和能力依赖中的 `componentType` 是系统定义的类型标识；依赖涉及的逻辑实例使用 `componentInstanceId`。工作台运行目标同时携带 `id`、`deviceBindingKey`、`componentType` 和已绑定的 `componentInstanceId`。

这些字段互不替代。具体示例及旧字段读取规则见[命名约定](../architecture/modules.md#组件与设备命名)。
