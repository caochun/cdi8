# Python 分系统仿真模块

集中控制主应用位于仓库根目录。本模块负责分系统状态仿真、PyTango Device Server 和 HTTP/Tango 网关，
独立于 Java 流程引擎运行。Python 3.10+。

## 安装与运行

在仓库根目录安装：

```bash
python3 -m pip install -e './simulator[tango]'
make device-gateway          # 真实 Tango 开发模式，默认端口 8766
make simulator-test         # Python 回归测试
```

如果暂时不安装 PyTango：

```bash
python3 -m pip install -e ./simulator
make device-gateway-local    # 显式本地模式，不经过 Tango
```

也可以从本目录独立运行：

```bash
python3 -m pip install -e '.[tango]'
python3 -m gxlf_sim_system.tango.gateway --mode tango
python3 -m unittest discover -s gxlf_sim_system/tests -t . -v
```

根 Makefile 自动设置本模块 PYTHONPATH，因此源码树内的 make 命令不依赖 editable 安装。
模型读取基于模块文件位置，不依赖当前工作目录。默认设备数据库保存在
`simulator/gxlf_sim_system/output/devices/`，可用 `--data` 指定其他目录。
迁移保留了原数据库和日志；自定义 `--data` 绝对路径不受目录调整影响。

## 核心文件与对照实现

| 路径 | 职责 |
|---|---|
| gxlf_sim_system/tango/device.py | PyTango Device Server |
| gxlf_sim_system/tango/runtime.py | 持久化设备任务、幂等命令、状态仿真 |
| gxlf_sim_system/tango/gateway.py | HTTP 到 Tango 适配，Java 的设备访问入口 |
| gxlf_sim_system/subsystem_fsm.py | 单实例分系统状态机执行器 |
| gxlf_sim_system/models/excel-*-state-machine.yaml | 两份分系统状态规则 |
| gxlf_sim_system/simulation.py | 演示用反馈证据 |
| gxlf_sim_system/tests/ | 分系统、协议、历史对照流程的回归测试 |

以下文件仅为迁移前的 Python 对照实现，Java 主应用不调用它们：

- `joint_fanout_experiment.py` 和 `models/laser-joint-fanout-experiment.yaml`：旧流程编排。
- `joint_fanout_demo.py` / `__main__.py`：旧完整周期命令行示例。
- `operator.py`、`operator_server.py`、`web/operator.html`：旧操作台和 JSONL 回放。

从根目录运行 `make simulator-demo` 或 `make legacy-operator` 可使用这些对照工具。
`make operator` 启动的是 Java 应用（8080），`make legacy-operator` 才是 Python 操作台（8765）。

Java 使用 `src/main/resources/processes/laser-joint.bpmn20.xml`，不会执行 Python 流程 YAML。
业务模型变更时仍需评审两者一致性；对照模型不是第二套生产流程配置。

[主项目说明](../README.md) · [Python 对照说明](../docs/python-reference-guide.md) ·
[日志回放说明](../docs/operator-console.md)
