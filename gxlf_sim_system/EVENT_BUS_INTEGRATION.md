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
  "task_id": "SHOT-SIM-001.N18.bg01.1",
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
  -> icf-viz Zustand store
  -> 三维对象响应
```

## 三维场景映射

`icf-viz/src/state/gxlfSystemMap.ts` 定义了流程模型系统到三维子系统的映射。原则：

- `gxlf_sim_system/models/*.yaml` 中的系统名称是权威名称。
- `icf-viz` 可保留英文 enum，但必须通过映射表接受权威系统名。
- 屏蔽门控制、人员计数不再只折叠进 `SAFETY`，而是作为独立可响应对象：
  - `SHIELDING_DOOR`
  - `PERSONNEL_COUNTER`

## 前端事件应用

`icf-viz` 中 `useExperimentStore.applyLifecycleEvent(event)` 是事件入口。它负责：

- 根据 `system_name` 找到三维子系统。
- 根据 `event_type`、`task_state_after`、`business_state_after` 计算展示状态。
- 根据关键命令更新三维视觉参数，例如警灯、屏蔽门、充电、光束、同步触发。

后续接入 WebSocket/SSE 时，只需要在前端订阅消息后调用：

```ts
useExperimentStore.getState().applyLifecycleEvent(event);
```

## 本地 SSE bridge 启动

启动仿真事件桥：

```bash
python3 -m gxlf_sim_system bridge --host 127.0.0.1 --port 8765
```

启动三维场景：

```bash
cd icf-viz
NEXT_PUBLIC_GXLF_EVENTS_URL=http://127.0.0.1:8765/events npm run dev
```

打开 `http://localhost:3000` 后，前端会通过 SSE 订阅 `/events`。每次建立连接，bridge 会运行一次仿真，并将生命周期事件按标准 SSE `message` 事件推送给浏览器；真实生命周期类型保留在 JSON 的 `event_type` 字段中。

也可以直接用 `curl` 检查事件流：

```bash
curl -N http://127.0.0.1:8765/events?max_nodes=1
```

## 仿真时间模型

流程节奏应由系统/设备仿真器产生，而不是由消息总线简单慢放。当前实现中：

- 流程引擎从节点 `timing.expected_duration` 或 `timeout` 推导 `sim_expected_duration_ms`。
- `bridge` 默认使用 `--target-duration 60`，根据流程关键路径自动计算 `time_scale`，将完整流程压缩到约 1 分钟。
- `TangoSimAdapter` 在异步命令 `accepted -> executing -> succeeded` 之间按 `time_scale` sleep；fan-out 节点会按目标实例数分摊节点级持续时间，使 60 路/10 路目标近似并行推进。
- `bridge --delay` 只保留为事件发送后的额外小间隔，默认是 `0`。

常用参数：

```bash
--time-scale 0       # 不等待，适合快速测试
--target-duration 60 # 完整流程关键路径压缩到约 60s，bridge 默认值
--time-scale 0.02    # 手动指定缩放，会覆盖 target-duration 自动计算
--max-delay 5        # 单个实例最多等待 5s，避免异常模型时长过慢
```
