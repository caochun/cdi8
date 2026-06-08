# CDI8 / GXLF 集中管控仿真与三维可视化

这个仓库的主线目标是用模型驱动的方式验证 GXLF 集中管控流程：流程模型定义发射 DAG、接口契约和设备状态机，后端仿真引擎解释执行模型，前端三维场景只响应事件流并展示系统状态。

## 项目结构

```text
.
├── gxlf_sim_system/          # 核心系统：流程模型、流程引擎、设备仿真、事件桥、测试
│   ├── models/               # 发射流程、接口契约、服务状态机 YAML
│   └── tests/                # 静态模型验证、引擎语义测试、模型驱动场景测试
├── apps/
│   └── icf-viz/              # Next.js + Three.js 事件驱动三维可视化
├── scripts/
│   └── run-event-viz.sh      # 一键启动事件桥和前端
├── Makefile                  # 常用开发命令
└── pyproject.toml            # Python 包与 gxlf-sim CLI 配置
```

`docs/` 和 `archive/` 是本地资料与历史归档区，已在 `.gitignore` 中忽略，不作为当前代码基线的一部分。

## 快速启动

安装前端依赖：

```bash
make viz-install
```

一键启动事件桥和三维可视化：

```bash
make dev
```

默认地址：

```text
三维可视化: http://localhost:3001
事件流:     http://127.0.0.1:8765/events
控制接口:   http://127.0.0.1:8765/commands
```

事件桥启动后默认处于 `idle`，不会因为页面连接自动执行流程。打开页面后，用左下角引擎控制面板发送 Start/Pause/Resume/Stop/Reset；这些控制命令通过事件总线进入后端，后端状态再通过 `/events` 回到 UI。

也可以直接调用控制接口：

```bash
curl -X POST http://127.0.0.1:8765/commands \
  -H 'Content-Type: application/json' \
  -d '{"command":"start"}'
```

停止本地前后端进程：

```bash
make stop
```

## 核心模型

核心模型位于 `gxlf_sim_system/models/`：

- `gxlf-firing-flow.yaml`: 发射流程 DAG、节点命令、依赖、运行条件、超时和失败策略声明。
- `interface-contracts.yaml`: 总控与设备系统之间的命令、返回值和回调契约。
- `service-state-machines.yaml`: 设备系统、实例、健康状态、业务状态、任务状态和命令状态迁移。

当前执行语义采用真实系统风格：

1. 流程引擎向目标设备实例下发命令。
2. 设备立即返回 `accepted` 或拒绝。
3. 设备稍后通过事件/回调发布 `executing`、`succeeded`、`failed` 等状态。
4. 流程引擎监听回调与运行条件，满足 `completion_criteria` 后推进 DAG。

`runtime_guards` 会由引擎在节点执行前评估；不满足时节点进入等待状态，直到外部事件修复条件、流程被停止，或策略要求失败。

## 常用命令

后端模型验证与仿真：

```bash
make sim-validate
make sim-run
make sim-bridge
```

前端开发：

```bash
make viz-dev
make viz-event
```

测试：

```bash
make test
make test-static
make test-engine
make test-bridge
make test-scenarios
```

直接使用 Python CLI：

```bash
python3 -m gxlf_sim_system validate
python3 -m gxlf_sim_system run
python3 -m gxlf_sim_system bridge --host 127.0.0.1 --port 8765
```

安装为命令行工具：

```bash
python3 -m pip install -e .
gxlf-sim validate
gxlf-sim run
gxlf-sim bridge
```

## 测试体系

`gxlf_sim_system/tests/` 覆盖三层风险：

- 静态模型验证：检查流程节点、目标系统、命令、状态机和模型交叉引用。
- 引擎语义单元测试：检查 accepted/callback、runtime guards、timeout、stop/reset 等关键行为。
- 模型驱动场景测试：用 YAML 场景描述 golden path、故障注入、guard 等端到端行为。

前端控制面板还提供故障注入能力，用于人工验证安全异常、设备离线、回调超时、命令失败等流程响应。

## 进一步阅读

- 核心系统细节：`gxlf_sim_system/README.md`
- 可视化应用细节：`apps/icf-viz/README.md`
- 本地业务资料：`docs/`，该目录不纳入 git
