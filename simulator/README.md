# 种子源分系统仿真

本模块仅包含种子源，不包含二倍频、集中控制流程或旧 YAML 解释器。运行时从 [SCXML v2 状态机模型](../docs/seed-source-state-machine.scxml)加载层级状态和转换。

[种子源 v2 模型](../docs/seed-source-state-machine-draft.md)采用四个外层主状态和必要的内层子状态。SCXML 是唯一状态转换来源；任务进度、配置资格、数据资格和故障集合由独立记录保存。模拟器已经执行 v2，接受任务不会新增“执行中”设备状态。

外层主状态和内层子状态表达设备资格；动作执行过程和结果属于独立任务记录：

| 外层主状态 | 内层子状态 |
|---|---|
| 未就绪 | 待检查、自检通过、功能检查未通过 |
| 就绪 | 参数待确认、参数已确认 |
| 出光 | 尚无本次采集结果、采集完成、采集失败 |
| 设备异常 | 异常待分类、故障锁定、通信异常、执行超时 |

**采集成功或失败都保持出光。** 参数下发失败保持就绪；自检成功仍未就绪，功能检查成功且就绪证据确认后才进入就绪。
设备故障是单独的确认事件，不能由命令失败自动推断。这里仿真的是状态与事件，不计算真实光功率或波形。

## 先运行一次演示

Python 3.9+。核心、CLI、HTTP 和单元测试只使用标准库。在仓库根目录：

```bash
PYTHONPATH=simulator python3 -m gxlf_sim_system demo
```

演示使用内存数据库，依次完成自检、功能检查、参数下发、出光，再模拟一次采集失败和一次重试成功。
最后两次结果分别为 `出光 / 采集失败`、`出光 / 采集成功`，不会影响其他实例的数据库。

也可以安装后使用 `seed-sim`：

```bash
python3 -m pip install -e ./simulator
seed-sim demo
```

## Taurus 原生桌面操作台

桌面版使用 **Taurus + PyQt6 + QGraphicsView**，没有浏览器或内嵌 WebView。
通过 Taurus Device/Attribute 模型连接真正的 Tango Device，订阅 `Snapshot` 事件，
所有按钮都发送 Tango 命令；状态图只展示现有 Python 模型，不执行另一份状态机。

建议使用独立 Python 环境安装可选依赖（本次验证为 Python 3.14、Taurus 5.4.1、PyQt6 6.11、PyTango 10.3.1）：

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -e './simulator[gui]'
.venv/bin/seed-panel --demo
# 等价的源码运行方式：
make seed-panel PYTHON=.venv/bin/python
```

`--demo` 会启动本地无数据库 Tango Device Server，再通过 Taurus 连接。
默认保存到当前目录 `seed-panel-data/seed_01.sqlite3`，关闭窗口会关闭本次启动的服务，保留状态和历史。
使用 `--database /tmp/seed-panel-review.sqlite3` 可选择独立的演示实例数据；不会自动清空已有任务。

连接已有设备：

```bash
.venv/bin/seed-panel --device 'tango://host:10000/gxlf/seed/01'
# 无 Tango DB 的设备使用服务器给出的完整 URL（含 #dbase=no）：
.venv/bin/seed-panel --device 'tango://127.0.0.1:12345/gxlf/seed/seed_01#dbase=no' --allow-simulation
```

外部设备连接默认关闭仿真反馈及确认事件注入；连接的确实是仿真 Device 时可使用 `--allow-simulation`。
真实设备需要实现相同模型与命令契约后才能复用此操作台，不能仅凭通用 Tango `State` 属性驱动这张业务状态图。
未知模型或不一致的业务/任务状态会禁用操作并显示原因。

界面操作：

1. 等待连接完成。左侧同时高亮外层主状态和当前内层子状态。
2. 右侧只启用当前状态允许的动作。点击动作后，等待任务反馈。
3. 仿真模式下点击“模拟本次任务成功”或“模拟本次任务失败”。采集失败时外层保持出光，内部节点显示采集失败。
4. 右侧下方“设备状态确认”通过 `ReportEvent` 注入已确认事实。它不执行真实关机、复位或故障修复动作。
5. 下方查看最近 100 条设备事件及任务编号。图中橙色连线对应最近一次转换，鼠标悬停可看事件名称。

状态图支持滚轮缩放、拖动平移、适应窗口及点击节点查看允许动作；点击图上的节点不会改变设备状态。
自动更新保持图元位置不变。断线时画面标明上次状态并禁用命令，后台定时核对连接；地址改变后需点击“连接”。
设备读写在独立 Qt 工作线程中执行，Taurus 回调经 Qt 信号转发；窗口关闭时取消监听并退出工作线程。

## 手动驱动单个实例

每次请求指定唯一 `command_id`；请求创建独立任务，设备状态保持不变，再注入结果。

```bash
PYTHONPATH=simulator python3 -m gxlf_sim_system --db /tmp/seed-demo.sqlite3 snapshot
PYTHONPATH=simulator python3 -m gxlf_sim_system --db /tmp/seed-demo.sqlite3 request power_on_self_test selftest-1
PYTHONPATH=simulator python3 -m gxlf_sim_system --db /tmp/seed-demo.sqlite3 simulate selftest-1 success
PYTHONPATH=simulator python3 -m gxlf_sim_system --db /tmp/seed-demo.sqlite3 request function_check check-1
PYTHONPATH=simulator python3 -m gxlf_sim_system --db /tmp/seed-demo.sqlite3 complete check-1 success --observed 就绪
PYTHONPATH=simulator python3 -m gxlf_sim_system --db /tmp/seed-demo.sqlite3 history
```

`complete` 显式校验反馈所需的业务状态证据；`simulate` 是仿真便捷入口，会为该动作合成所需确认。
功能检查成功要求确认就绪；出光成功要求确认出光；出光失败要求确认仍就绪。错误证据会被拒绝，不改变状态。

## 动作和事件

| 动作 ID | 可接受请求的内层子状态 | 成功目标 | 失败目标 |
|---|---|---|---|
| `power_on_self_test` | 待检查、功能检查未通过 | 自检通过 | 保持待检查或功能检查未通过，任务记录失败 |
| `function_check` | 自检通过、功能检查未通过 | 就绪 / 参数待确认 | 功能检查未通过 |
| `parameter_dispatch` | 参数待确认、参数已确认 | 参数已确认 | 保持当前子状态，配置资格记录失败 |
| `seed_source_emit` | 参数已确认 | 出光 / 尚无本次采集结果 | 保持就绪，任务记录失败 |
| `laser_parameter_collect` | 尚无本次采集结果、采集完成、采集失败 | 采集完成，保持出光 | 采集失败，保持出光 |
| `shutdown` | 参数待确认、参数已确认、尚无本次采集结果、采集完成、采集失败 | 未就绪 / 待检查 | 保持当前状态，任务记录失败 |

接受普通动作只创建任务，设备状态保持在当前子状态；反馈到达后才按 SCXML 事件转换。模型不包含自检中、参数下发中、出光启动中或采集中等设备状态。`parameters` 随命令保存，用于关联和幂等检查；目前不解释具体设备参数，也不模拟参数的物理效果。

复位、关机和故障恢复由已确认事件驱动状态转换：

| 确认事件 | 来源外层主状态 | 必须提供的 `observed_business_state` | 目标内层子状态 |
|---|---|---|---|
| `standby_confirmed` | 出光 | 就绪 | `configIdle`（参数待确认） |
| `shutdown_confirmed` | 就绪、出光 | 未就绪 | `unchecked`（待检查） |
| `abort_reset_confirmed` | 就绪、出光 | 未就绪 | `unchecked`（待检查） |
| `fault_confirmed` | 未就绪、就绪、出光 | 设备异常 | `faultDetected`（异常待分类） |
| `recovery_confirmed` | 设备异常 | 未就绪 | `unchecked`（待检查） |

## HTTP 调试入口

```bash
PYTHONPATH=simulator python3 -m gxlf_sim_system.tango.gateway --mode local --port 8766 --data /tmp/new-seed-devices
```

默认只创建 `seed_01`。多个独立实例使用 `--devices seed_01 seed_02 seed_03`，每个实例拥有独立数据库。
此参数创建的全部是种子源实例，不会根据名字切换到其他分系统类型。

| 接口 | 内容 |
|---|---|
| `GET /devices` | 实例列表 |
| `GET /devices/seed_01` | 当前状态快照 |
| `POST /devices/seed_01/commands` | `{"command_id":"a1","action":"power_on_self_test","parameters":{}}` |
| `POST /devices/seed_01/complete` | `{"command_id":"a1","outcome":"success"}`；需要时提供 `observed_business_state` |
| `POST /devices/seed_01/simulate` | `{"command_id":"a1","outcome":"success"}`；仅支持 success/failure |
| `GET /devices/seed_01/commands/a1` | 本次命令的持久化结果 |
| `POST /devices/seed_01/events` | `{"event_id":"e1","event":"fault_confirmed","observed_business_state":"设备异常"}` |
| `GET /devices/seed_01/history?limit=100` | 最近事件及来源、目标状态，按序号升序返回 |

命令结果中的 `snapshot` 是该结果产生时的快照；实时状态应重新读取实例接口。
快照的 `last_result` 是上一条终态任务结果，带任务编号；不能把它当作当前任务已经成功。

## Tango

```bash
python3 -m pip install -e './simulator[tango]'
python3 -m gxlf_sim_system.tango.gateway --mode tango --port 8766 --data /tmp/new-tango-seed
```

Tango 模式真正通过 `DeviceProxy` 调用 `SeedSourceDevice`。开发网关启动无数据库的测试 Device Server；
现场部署可通过 Tango DB 注册 Device，设置 `database_path` 后运行 `python3 -m gxlf_sim_system.tango.device <instance>`。

提供 `Snapshot` 属性、CHANGE_EVENT，以及 `Execute`、`Complete`、`Simulate`、`ReportEvent`、`GetResult`、`History` 命令。
所有变更都走相同的持久化运行时；Tango 是可选依赖，不影响标准库模式。

## 持久化与重复消息

- 状态、活动任务、结果及事件历史在一个 SQLite 事务中更新；进程重启后仍能继续原任务。
- 同一命令编号及相同动作、参数重复提交，返回原记录；不同内容复用编号会被拒绝。
- 同一结果重复提交不重复转换；相反结果不能覆盖已完成结果。
- 确认事件也要求唯一 `event_id`；重复事件不会影响后来开始的任务。
- 单实例同一时刻只执行一个任务；数据库写锁覆盖多个运行时连接。
- 旧模拟器数据库不自动迁移，打开时明确拒绝；请使用新的数据路径。

## 测试与项目边界

```bash
make simulator-test
# 安装可选依赖后，以同一 Python 运行真实 Tango 测试：
make simulator-test PYTHON=/path/to/venv/bin/python
# 安装 gui extra 后，运行原生桌面端到端测试（不弹出窗口）：
QT_QPA_PLATFORM=offscreen make simulator-test PYTHON=.venv/bin/python
```

本次验证：标准库模式在 Python 3.9.6 下通过 27 项测试（Tango、原生桌面各 1 项跳过）；
安装 gui extra 后，在 Python 3.14 + PyTango 10.3.1 + Taurus 5.4.1 + PyQt6 6.11 下 29 项全部通过。
包含真实 Tango Command、Snapshot 属性、CHANGE_EVENT，以及桌面按钮驱动的完整正常路径、采集失败与重试、外部故障事件、断线重连和仿真控制禁用。
可编辑安装后的 `seed-sim demo` 已在仓库外运行验证；`seed-panel --demo` 是独立桌面入口。

测试覆盖请求准入、各动作成功/失败及重试、采集失败保持出光、业务状态确认、异常中断和迟到反馈、幂等、并发、持久化、HTTP 及可选 Tango。
仓库内运行还会逐条对照 Mermaid 的事件边、节点名称和复合状态入口，防止实现偏离图。

**当前 Java 双分系统流程尚未迁移到这个模型。** 它仍使用旧的 `main_state/current_state` 契约、二倍频实例及 Cancel/关机命令；不能直接连接本模块就认为完成联合流程。
旧 Java 契约测试所需 YAML 已移到 `src/test/resources/legacy-device-models/`，仅作为回归参考，不是新仿真配置。
动作准入细则、中止抢占和物理过渡状态仍须继续评审；新仿真以当前图为准。

## 文件

- `machine.py`：业务状态、内部状态和事件转换。
- `runtime.py`：命令关联、幂等、SQLite 和历史。
- `cli.py`：演示及手动事件操作。
- `http.py`：loopback HTTP 服务。
- `tango/device.py`、`tango/gateway.py`：可选 Tango 封装与网关。
- `gui/`：可选 Taurus 原生桌面操作台、设备工作线程、状态图及操作控件。
- `tests/`：回归和图一致性测试。
