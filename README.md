# GXLF 集中管控与组件仿真

总控使用 **Java 17+ / Spring Boot / Flowable** 执行 BPMN，浏览器显示流程图、当前节点、命令结果和历史。设备通信由独立的 Python / PyTango 适配服务负责：通过 Tango Command 发送命令，订阅 `Snapshot` 的 `CHANGE_EVENT`，再将事件转发给总控。

```text
浏览器：BPMN 图与运行状态
  │ REST（页面定时刷新）
Spring Boot + Flowable + H2：流程执行、等待、超时、历史
  │ HTTP 命令 / 长连接 NDJSON 事件订阅
Python Tango 适配服务：协议转换、订阅、重连及状态补读
  │ PyTango DeviceProxy：Command / CHANGE_EVENT
ComponentDevice：SCXML 组件状态机 + SQLite 命令记录
```

`src` 已替换为这套实现。旧控制器、YAML 契约解释层、自定义 outbox/inbox 和旧流程不再参与运行；历史实现可从 Git 查看。四类组件状态机及其 GUI 保留。

## 目录职责

- `src/`：Java Spring Boot / Flowable 总控、运行时 BPMN 和浏览器页面。
- `simulator/`：Python SCXML 组件运行时、四类组件模型、Tango 适配、GUI 和测试。
- `docs/component-states/`：从状态清单拆分出的各控制对象状态定义。
- `docs/process-control/`：当前集中管控流程的业务说明；运行 BPMN 位于 `src/main/resources/processes/`。
- `scripts/`：联调和端到端验证脚本。
- `archive/` 与 `docs/archive/`：不参与构建和运行的历史原型、旧需求和旧图稿。

## 启动

终端一，在项目根目录启动真实 Tango 演示设备及适配服务：

```bash
python3 -m pip install -e './simulator[tango]'
PYTHONPATH=simulator python3 -m gxlf_component_simulator.tango.control_bridge --demo
# 已有 .venv 时也可：make tango-bridge PYTHON=.venv/bin/python
```

终端二：

```bash
mvn clean test package
java -jar target/control-server-0.1.0.jar
```

访问 **http://127.0.0.1:8080**。界面默认展示验证版“发射任务（四类组件验证范围）”，它依次调用“四类组件初始化”、`参数下发`、`发射准备`、`正式发射` 和 `发射后处理`。前期准备、正式发射和发射后处理流程可选择种子源、二倍频或两者作为出光组件；也可以在流程下拉框中单独运行子流程。双击流程图中的子流程，或使用层级下拉框查看命令等待节点；运行记录、当前阶段和设备状态会在页面中同步更新。

默认每类组件各一个，另有一个**演示同步与环境条件 Tango 设备**。同步、许可、环境条件不是四类 SCXML 模型的伪状态，所有外部条件初始为 false，不会自动通过。演示模式中的所有组件实例由同一个 Tango Device Server 进程承载，可分别配置实例数量：

```bash
PYTHONPATH=simulator python3 -m gxlf_component_simulator.tango.control_bridge --demo \
  --seed-count 1 --shg-count 8 --preamp-count 10 --multipass-count 60
```

真实 Tango 环境不使用这些数量参数，设备实例由 Tango 设备目录或 `devices.json` 中的实际条目决定。

总控只发命令和等待反馈。默认演示设备也等待显式反馈；为了自动跑通 BPMN 流程，可以在 `--demo` 下启用延迟自动完成：

```bash
PYTHONPATH=simulator python3 -m gxlf_component_simulator.tango.control_bridge --demo \
  --auto-complete --completion-delay-ms 500
```

演示设备会持久化状态；初始化成功后再次启动初始化会被拒绝，要求设备先回到“未就绪”。需要重新开始一轮干净演示时，在启动桥接服务时增加 `--reset-data`（只清理当前演示实例的 SQLite 文件）：

```bash
PYTHONPATH=simulator python3 -m gxlf_component_simulator.tango.control_bridge --demo \
  --auto-complete --completion-delay-ms 500 --reset-data
```

设备仍先返回 `accepted`，延迟后通过同一条 `Simulate`/`Snapshot CHANGE_EVENT` 路径报告成功；`--devices devices.json` 的真实 Tango 模式不会启用该行为。需要手动控制时，可用组件 GUI 连接适配服务启动时打印的 Tango 地址，或在适配服务的演示接口操作：

```bash
# 查询设备状态，找到 active_command_id
curl http://127.0.0.1:8766/devices
# 显式注入该命令的硬件完成反馈（仅演示）
curl -X POST http://127.0.0.1:8766/devices/seed_01/simulate \
  -H 'Content-Type: application/json' \
  -d '{"command_id":"替换为实际命令ID","outcome":"success"}'
# 显式提供外部许可、环境条件；网页界面也可以在 Tango 设备卡片中直接切换
curl -X POST http://127.0.0.1:8766/devices/context_01/conditions \
  -H 'Content-Type: application/json' \
  -d '{"safety_permit":true,"collimation_environment_ready":true}'
```

## 流程与实现

| 文件 | 职责 |
|---|---|
| [四类组件初始化 BPMN](src/main/resources/processes/component_initialization.bpmn20.xml) | 只覆盖种子源、二倍频、再生与双程放大、多程放大；标准并行网关协调四类组件分支，每类组件内部按实例并行执行上电检查和功能检查 |
| [参数下发 BPMN](src/main/resources/processes/parameter_dispatch.bpmn20.xml) | 组件初始化完成后，向四类组件实例并行下发参数，并核验参数初始化结果 |
| [发射准备 BPMN](src/main/resources/processes/optical_frontend_preparation.bpmn20.xml) | 从参数下发完成状态开始：安全许可 → 出光与同步/唤醒并行 → 闭环与准直并行 → 发射准备全部完成 |
| [正式发射 BPMN](src/main/resources/processes/formal_emission.bpmn20.xml) | 发射充电前置并行 → 发射充电 → 5 秒倒计时触发准备 → 正式触发完成；外部系统以显式条件证据接入 |
| [发射后处理 BPMN](src/main/resources/processes/post_emission_processing.bpmn20.xml) | 正式发射完成后并行执行数据采集、待机、预电离、收靶、吹扫，全部汇合后打开屏蔽门 |
| [验证版发射任务主流程](src/main/resources/processes/emission_task_lifecycle.bpmn20.xml) | 初始化 → 参数下发 → 发射准备 → 正式发射 → 发射后处理 |
| [ComponentCommandActivityBehavior.java](src/main/java/cn/cdi8/control/ComponentCommandActivityBehavior.java) | 非阻塞登记、等待、结果校验和活动恢复 |
| [ComponentCommandBpmnParseHandler.java](src/main/java/cn/cdi8/control/ComponentCommandBpmnParseHandler.java) | 将带 `componentCommand` 标记的 service task 解析为自定义活动 |
| [TangoTasks.java](src/main/java/cn/cdi8/control/TangoTasks.java) | 登记命令、核验任务结果及当前组件状态 |
| [WorkflowService.java](src/main/java/cn/cdi8/control/WorkflowService.java) | 事件关联、等待唤醒、资格监测、恢复查询、运行历史 |
| [TangoGateway.java](src/main/java/cn/cdi8/control/TangoGateway.java) | 适配服务客户端及事件长连接 |
| [control_bridge.py](simulator/gxlf_component_simulator/tango/control_bridge.py) | PyTango 命令调用、属性事件订阅、快照重放 |
| [preparation_context.py](simulator/gxlf_component_simulator/tango/preparation_context.py) | 显式可控的演示同步/环境条件设备 |
| [组件模型](simulator/gxlf_component_simulator/component_model.py) | 直接从 SCXML 读取动作及状态定义 |

重复的“登记命令 → 等待 Tango 结果 → 校验结果和组件状态”由 `component-command` 自定义任务统一处理。BPMN 通过 `flowable:field` 声明按组件类型解析的动作和目标状态，直接在该任务上声明 `multiInstance` 设备实例集合；动作超时边界事件也直接挂在任务上，因此任一实例超时都会结束该动作组。初始化流程使用标准 BPMN 并行网关和四类组件分支，显示**引擎实际部署的 BPMN**；节点着色来自 Flowable 执行与活动历史，支持并行、多实例和子流程。

完整业务设计见 [前期准备流程说明](docs/process-control/种子源至多程放大前期准备流程.md)。BPMN 统一维护在 `src/main/resources/processes`，流程图通过总控页面查看。

## 命令与事件语义

1. Flowable 先保存命令 ID 和接收等待节点，事务提交后才发送 Tango 命令。设备 ID、动作、结果均保存在引擎变量/历史中。
2. `Execute` 返回 `accepted` 只表示受理。命令 ID 在重试和恢复时保持不变，组件运行时按 ID 去重。
3. 适配服务订阅 `Snapshot` 属性变化。收到事件后，总控按设备与命令 ID 调用 `GetResult`，核验 `succeeded` 以及当前目标状态，才唤醒后续节点。
4. 漏事件及重启时每 5 秒补查等待中的任务；适配服务也每 5 秒补读设备快照。重连先重放快照，再开放事件通道。事件断开期间暂停放行，BPMN 超时仍有效。
5. 任一命令失败、设备故障、已建立的安全/供光/同步/唤醒/准直环境资格失效或等待超时，将结束本次流程并中断其他分支。

`accepted/succeeded/failed/interrupted` 是现有 `ComponentRuntime` 的任务协议，**不是 Tango 标准，也不是 SCXML 设备状态**。SCXML 决定组件资格；任务结果决定某条命令是否完成。

**停止流程只终止后续编排。** 当前组件没有取消在途命令的 Tango 接口，已受理动作仍可能继续。总控不自动关光或复位；下一次启动会拒绝仍有活动任务的设备。

## 接入与配置

连接已有 Tango 服务时，创建 `devices.json`：

```json
{
  "seed_01": {"component":"seed", "address":"gxlf/seed/01"},
  "shg_01": {"component":"shg", "address":"gxlf/shg/01"},
  "preamp_01": {"component":"preamp", "address":"gxlf/preamp/01"},
  "multipass_01": {"component":"multipass", "address":"gxlf/multipass/01"},
  "context_01": {"component":"context", "address":"gxlf/context/01"}
}
```

```bash
PYTHONPATH=simulator python3 -m gxlf_component_simulator.tango.control_bridge --devices devices.json
```

设备需实现与当前组件运行时兼容的 `Execute`、`GetResult`、`Snapshot` JSON 及 CHANGE_EVENT；`context` 还需提供 `preamp_repeat` 动作和 `conditions` 三个条件。真实同步/环境系统需由适配层转换成此接口。`Simulate/Complete/SetConditions` 是演示反馈入口，接入现场时应按实际接口替换。

| 环境变量 | 默认值 |
|---|---|
| `TANGO_BRIDGE_URL` | `http://127.0.0.1:8766` |
| `CONTROL_DB_URL` | `jdbc:h2:file:./data/control-v2` |
| `CONTROL_DB_PASSWORD` | 空 |
| `COMMAND_TIMEOUT` / `WORKFLOW_TIMEOUT` | `PT15M` / `PT45M` |

演示组件 SQLite 在 `simulator/gxlf_component_simulator/data/`，文件名包含组件、实例和模型版本；演示条件设备不持久化，重启后条件重新确认。总控数据库使用新路径，与旧控制器数据隔离。部署为单个总控实例，一次只运行一个流程。

主要接口：`GET /api/processes`、`GET /api/processes/{key}/xml`、`GET /api/devices`、`POST /api/devices/{id}/conditions`、`GET /api/connection`、`GET /api/runs`、`GET /api/runs/{id}`、`POST /api/runs`、`POST /api/runs/{id}/stop`。

```bash
curl -X POST http://127.0.0.1:8080/api/runs \
  -H 'Content-Type: application/json' \
  -d '{"processKey":"optical_frontend_preparation","sources":["seed"]}'
```

直接运行 `optical_frontend_preparation`、`formal_emission` 或 `post_emission_processing` 前，目标组件必须已经完成相应前置阶段；验证完整发射任务时使用 `emission_task_lifecycle`。

## 验证与当前范围

```bash
mvn test
make simulator-test PYTHON=.venv/bin/python
# 用全新演示数据启动两个服务后，显式模拟反馈跑完整流程：
python3 scripts/flowable_smoke.py
# 两类光源都出光：添加 --sources seed shg
```

Java 测试使用真实 Flowable 引擎与测试设备客户端，覆盖并行、失败、命令关联、丢事件恢复、外部资格失效、停止和定时器。Python 测试覆盖 SCXML 运行时及真实 Tango 命令、CHANGE_EVENT 和订阅重放。联调脚本通过真实 Tango 注入成功反馈并核对最终状态。

本版实现初始化、参数下发、四组件范围内的发射准备、正式发射和发射后处理流程，按设备集合执行，动作参数暂为空对象；正式发射和发射后处理中的诊断、泵浦、测量、开关、同步、靶瞄、吹扫及屏蔽门动作由演示条件设备提供显式证据，真实分系统模型和完整配方参数、束线/能量拓扑、发次证据仍需接入。初始化要求全部组件处于未就绪；上电检查仅确认设备均已上电，通过后仍须完成功能检查。种子源 v4、其他三类 v2 使用带版本的持久化文件，旧模型文件保留并拒绝复用。正式发射完成表示触发链路完成，发射任务只有在后处理完成后才结束。
