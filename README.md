# CDI8 / GXLF 集中管控模拟与可视化

这个仓库围绕 `gxlf_sim_system/` 这个核心系统组织。核心系统包含流程模型、流程引擎、系统仿真和事件桥；文档与事件驱动三维可视化应用围绕它展开。

## 目录结构

```text
.
├── gxlf_sim_system/             # 核心：流程模型、流程引擎、系统仿真、事件桥
├── apps/
│   └── icf-viz/                 # Next.js + Three.js 三维可视化原型
├── docs/
│   ├── process-control/         # 集中管控发射流程业务说明和流程图
│   ├── scenarios/               # 场景叙述与业务上下文
│   ├── diagrams/                # 独立图表和 HTML 图
│   └── archive/                 # 历史需求材料和拆分资料
├── archive/
│   └── legacy/20260608/         # 旧版原型和废弃工程，仅供回溯
├── Makefile                     # 常用开发命令
└── pyproject.toml               # 核心系统 Python 安装元数据
```

## 常用命令

一键启动事件桥和三维可视化：

```bash
make dev
```

打开：

```text
http://localhost:3001
```

事件桥启动后默认处于 `idle`，不会因为页面连接自动执行流程。在页面左下角 Engine 面板点击 Start，或通过事件总线控制接口启动：

```bash
curl -X POST http://127.0.0.1:8765/commands \
  -H 'Content-Type: application/json' \
  -d '{"command":"start"}'
```

其他开发命令：

```bash
make sim-validate
make sim-run
make sim-bridge
make viz-install
make viz-dev
```

也可以直接运行核心系统：

```bash
python3 -m gxlf_sim_system validate
python3 -m gxlf_sim_system run
```

需要安装为命令行工具时：

```bash
python3 -m pip install -e .
gxlf-sim validate
```

## 主线边界

- 业务规则和流程依据优先看 `docs/process-control/`。
- 流程 DAG、接口契约、服务状态机模型在 `gxlf_sim_system/models/`。
- 三维可视化事件接入说明在 `docs/simulation/EVENT_BUS_INTEGRATION.md`。
- `archive/legacy/20260608/` 中内容不再作为当前主线维护。
