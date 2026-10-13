# GXLF 生命周期事件总线集成说明

## 目标

`gxlf_sim_system` 中的仿真设备在命令接收、状态迁移、任务状态变化、拒绝执行时发布统一生命周期事件。三维场景不直接读取流程 YAML，也不轮询设备状态，而是订阅这些事件并驱动对应三维对象变化。

## 事件来源

当前仿真侧通过 `LifecycleLogger` 生成事件。该类已经抽象为 sink 管道：

- `MemoryLifecycleSink`：用于测试和运行结果统计。
- `JsonlLifecycleSink`：用于写入 `output/lifecycle.jsonl`。
- `TangoLifecycleEventSink`：预留给真实 PyTango 事件发布实现。

后续可以继续增加：

- `WebSocketLifecycleSink`
- `SseLifecycleSink`
- `KafkaLifecycleSink`
- `RedisStreamLifecycleSink`

## 标准事件字段

事件字段与 `LifecycleEvent.to_payload()` 保持一致：

```json
{
  "seq": 123,
  "timestamp": "2026-06-05T09:28:51.849068+00:00",
  "event_type": "state_transition",
  "flow_instance_id": "FLOW-SIM-001",
  "shot_id": "SHOT-SIM-001",
  "stage_id": "发射准备",
  "node_id": "N18",
  "node_name": "进入打靶状态",
  "command": "EnterShotState",
  "task_id": "SHOT-SIM-001.N18.gxlf.multipass_amp.bg01.1",
  "system_name": "多程放大组件",
  "service_type": "multipass_amp",
  "service_id": "gxlf.multipass_amp.bg01",
  "tango_fqdn": "gxlf/multipass_amp/bg01",
  "instance_code": "bg01",
  "beam_group_no": 1,
  "health_state_before": "running",
  "health_state_after": "busy",
  "business_state_before": "beam_aligned",
  "business_state_after": "shot_state",
  "task_state_before": "created",
  "task_state_after": "accepted",
  "result_status": "success"
}
```

## Tango 发布建议

Tango 侧建议拆成两层：

1. 属性事件：适合稳定状态字段，例如 `health_state`、`business_state`、`task_state`，使用 change event。
2. 用户事件：适合完整生命周期事件，例如 `command_received`、`state_transition`、`task_state_changed`、`command_rejected`。

Web 前端不建议直接连接 Tango。推荐增加一个事件桥：

```text
Tango device event
  -> gxlf-event-bridge
  -> WebSocket/SSE
  -> apps/icf-viz Zustand store
  -> 三维对象响应
```

## 三维场景映射

`apps/icf-viz/src/state/gxlfSystemMap.ts` 定义了流程模型系统到三维子系统的映射。原则：

- `gxlf_sim_system/models/*.yaml` 中的系统名称是权威名称。
- `apps/icf-viz` 可保留英文 enum，但必须通过映射表接受权威系统名。
- 屏蔽门控制、人员计数不再只折叠进 `SAFETY`，而是作为独立可响应对象：
  - `SHIELDING_DOOR`
  - `PERSONNEL_COUNTER`

## 前端事件应用

`apps/icf-viz` 中 `useExperimentStore.applyLifecycleEvent(event)` 是事件入口。它负责：

- 根据 `system_name` 找到三维子系统。
- 根据 `event_type`、`task_state_after`、`business_state_after` 计算展示状态。
- 根据关键命令更新三维视觉参数，例如警灯、屏蔽门、充电、光束、同步触发。

后续接入 WebSocket/SSE 时，只需要在前端订阅消息后调用：

```ts
useExperimentStore.getState().applyLifecycleEvent(event);
```

## 本地 SSE bridge 与控制命令

启动仿真事件桥：

```bash
make sim-bridge
```

启动三维场景：

```bash
cd apps/icf-viz
NEXT_PUBLIC_GXLF_EVENTS_URL=http://127.0.0.1:8765/events npm run dev
```

打开前端后，页面会通过 SSE 订阅 `/events`，但 bridge 不会因为浏览器连接而自动启动流程。流程启动、暂停、继续、停止和复位都通过 `/commands` 发出；前端左下角的 Engine 面板就是这个控制面的 UI。

```bash
curl -X POST http://127.0.0.1:8765/commands \
  -H 'Content-Type: application/json' \
  -d '{"command":"start"}'
```

启动时可以指定仿真倍速。倍速由 bridge 作用到本轮仿真的 `target_duration_seconds`，不会让前端自行快进事件流：

```bash
curl -X POST http://127.0.0.1:8765/commands \
  -H 'Content-Type: application/json' \
  -d '{"command":"start","speed_multiplier":5}'
```

支持的命令：

- `start`：在没有运行中流程时启动一轮新的后台仿真。
- `pause`：暂停流程引擎继续调度后续 DAG 节点。
- `resume`：恢复调度。
- `stop`：请求流程软停止，当前已下发到设备仿真层的任务会先完成回调，然后引擎在后续调度边界返回 `aborted`。
- `reset`：在没有运行中流程时复位前端/bridge 运行态为 `idle`。
- `set_flag` / `set_interlock` / `set_guard`：更新运行时 guard 上下文，驱动等待中的流程节点重新评估。
- `set_service_health`：设置某个分系统实例集合的仿真健康状态，用于验证 `service_state` guard。
- `inject_fault` / `clear_faults`：注入或清除设备仿真故障，用于验证拒收、callback failed、callback timeout 等异常路径。

需要注意：`stop` 和 `reset` 目前不是设备急停、安全联锁或补偿流程；它们只控制流程编排层。设备安全停机、回退和补偿命令应建模为独立流程节点或安全联锁服务命令。

bridge 会将生命周期事件缓存在内存中；每个浏览器连接都会从同一份事件缓存开始重放，然后继续接收实时事件。浏览器刷新或多个客户端同时连接不会触发流程重新执行。

SSE 事件类型：

- `gxlf.engine.status`：引擎状态，例如 `idle`、`running`、`paused`、`stopping`、`completed`、`aborted`、`failed`。
- `gxlf.engine.command`：bridge 接收到的控制命令；`start` 会带上 `speed_multiplier` 等启动参数。
- `gxlf.engine.event`：流程引擎内部事件，例如 `node_started`、`node_completed`、`node_guard_blocked`、`node_guard_passed`。
- `gxlf.guard.update`：外部运行时 guard 上下文变化。
- `gxlf.fault.update`：测试故障或服务健康注入变化。
- `gxlf.lifecycle.live`：实时设备生命周期事件。
- `gxlf.lifecycle.replay`：历史重放的设备生命周期事件，前端不应把它当作新的倒计时/发射触发。
- `gxlf.bridge.complete`：一轮流程结束摘要。
- `gxlf.bridge.error`：bridge 或流程执行异常。

生命周期 JSON 的真实业务类型仍保留在 `event_type` 字段中，例如 `command_received`、`task_state_changed`、`state_transition`。

## runtime_guards 解释执行

`depends` 表达 DAG 静态依赖：上游节点完成后，下游节点才有资格进入调度。`runtime_guards` 表达动态运行条件：节点已经满足 DAG 依赖后，流程引擎在真正下发命令前实时检查这些条件。

当前支持的 guard 类型：

- `context_flag`：检查流程上下文标志，例如 `recipe_loaded == true`、`central_control_healthy == true`。
- `interlock_state`：检查联锁上下文，例如 `safety == normal`。
- `service_state`：检查目标分系统实例状态，例如所有 `光纤种子源组件` 实例 `health_state == running`。
- `node_state`：检查某个流程节点状态，例如 `Nxx == completed`；`started` 表示节点已经进入过执行链路，因此 `running`、`completed`、`skipped` 都满足。

模型里已有的简写会被兼容解释：

```yaml
runtime_guards:
  - { check: 流程配方加载成功 }
  - { check: 总控服务状态正常 }
  - { service: 光纤种子源组件, check: running }
  - { interlock: safety, check: normal }
```

如果 guard 不满足，流程引擎不会向设备仿真层下发命令，也不会把节点判失败；节点状态进入 `waiting_guard`。bridge 会发布：

- `gxlf.engine.status`，其中 `status=waiting_guard`，并带上 `guard_block` 和 `guard_context`。
- `gxlf.engine.event`，其中 `event_type=node_guard_blocked`，并带上 `failed_guards` 明细。

外部系统通过 `/commands` 更新上下文后，引擎会重新评估等待节点。guard 通过时，bridge 发布 `node_guard_passed`，节点再进入正常命令下发流程。

示例：先让安全联锁异常，再恢复为正常。

```bash
curl -X POST http://127.0.0.1:8765/commands \
  -H 'Content-Type: application/json' \
  -d '{"command":"set_interlock","key":"safety","value":"abnormal"}'

curl -X POST http://127.0.0.1:8765/commands \
  -H 'Content-Type: application/json' \
  -d '{"command":"start"}'

curl -X POST http://127.0.0.1:8765/commands \
  -H 'Content-Type: application/json' \
  -d '{"command":"set_interlock","key":"safety","value":"normal"}'
```

示例：控制 N01 的流程配方 guard。

```bash
curl -X POST http://127.0.0.1:8765/commands \
  -H 'Content-Type: application/json' \
  -d '{"command":"set_flag","key":"recipe_loaded","value":false}'

curl -X POST http://127.0.0.1:8765/commands \
  -H 'Content-Type: application/json' \
  -d '{"command":"set_flag","key":"recipe_loaded","value":true}'
```

## 故障注入

故障注入用于验证流程引擎和设备仿真层的异常路径。前端右下角 Faults 面板只发送 bridge 命令；真正的故障行为发生在设备仿真层。

设置服务健康状态：

```bash
curl -X POST http://127.0.0.1:8765/commands \
  -H 'Content-Type: application/json' \
  -d '{"command":"set_service_health","system_name":"光纤种子源组件","health_state":"offline"}'

curl -X POST http://127.0.0.1:8765/commands \
  -H 'Content-Type: application/json' \
  -d '{"command":"set_service_health","system_name":"光纤种子源组件","health_state":"running"}'
```

注入设备行为故障：

```bash
curl -X POST http://127.0.0.1:8765/commands \
  -H 'Content-Type: application/json' \
  -d '{"command":"inject_fault","fault":{"behavior":"reject","node_id":"N04","system_name":"光纤种子源组件"}}'

curl -X POST http://127.0.0.1:8765/commands \
  -H 'Content-Type: application/json' \
  -d '{"command":"inject_fault","fault":{"behavior":"callback_failed","node_id":"N04","system_name":"光纤种子源组件"}}'

curl -X POST http://127.0.0.1:8765/commands \
  -H 'Content-Type: application/json' \
  -d '{"command":"inject_fault","fault":{"behavior":"callback_timeout","node_id":"N04","system_name":"光纤种子源组件"}}'
```

当前支持的 `behavior`。API 枚举保留英文，UI 上显示中文含义：

- `reject`：命令拒收。设备命令接收阶段返回 `accepted=false`。
- `callback_failed`：回调失败。设备先返回 accepted，随后发布 failed callback。
- `callback_timeout`：回调超时。设备先返回 accepted，但不发布 terminal callback；仿真会缩短该测试 timeout，便于手工验证。

清除故障：

```bash
curl -X POST http://127.0.0.1:8765/commands \
  -H 'Content-Type: application/json' \
  -d '{"command":"clear_faults"}'
```

也可以直接用 `curl` 检查事件流：

```bash
curl -N http://127.0.0.1:8765/events
```

查看 bridge 当前状态：

```bash
curl http://127.0.0.1:8765/health
```

如果需要限制执行节点数，应在启动 bridge 时指定，而不是作为 `/events` 连接参数：

```bash
python3 -m gxlf_sim_system bridge --max-nodes 1
```

## 仿真时间模型

流程节奏应由系统/设备仿真器产生，而不是由消息总线简单慢放。当前实现中：

- 流程引擎从节点 `timing.expected_duration` 或 `timeout` 推导 `sim_expected_duration_ms`。
- `bridge` 默认使用 `--target-duration 60`，根据流程关键路径自动计算 `time_scale`，将完整流程压缩到约 1 分钟。
- `TangoSimAdapter` 只同步返回命令接收结果；设备任务随后通过内部 callback bus 发布 `accepted -> executing -> succeeded` 等状态，流程引擎监听这些 callback 后推进 DAG。
- 异步命令在 `executing -> succeeded` 之间按 `time_scale` sleep；fan-out 目标以设备任务并发方式推进，因此同一节点的 60 路/10 路目标表现为并行执行，而不是逐个任务串行等待。
- `bridge --delay` 只保留为每个 SSE 客户端发送事件后的额外小间隔，默认是 `0`；它不会改变仿真本身节奏。
- 如果 bridge 已经完成一次仿真，后续连接会先重放历史事件；要开始新一轮仿真，可以通过 Engine 面板或 `/commands` 再次发送 `start`。

常用参数：

```bash
--time-scale 0       # 不等待，适合快速测试
--target-duration 60 # 完整流程关键路径压缩到约 60s，bridge 默认值
--time-scale 0.02    # 手动指定缩放，会覆盖 target-duration 自动计算
--max-delay 5        # 单个实例最多等待 5s，避免异常模型时长过慢
```
