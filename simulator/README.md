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

## 核心文件

| 路径 | 职责 |
|---|---|
| gxlf_sim_system/tango/device.py | PyTango Device Server |
| gxlf_sim_system/tango/runtime.py | 持久化设备任务、幂等命令、状态仿真 |
| gxlf_sim_system/tango/gateway.py | HTTP 到 Tango 适配，Java 的设备访问入口 |
| gxlf_sim_system/subsystem_fsm.py | 单实例分系统状态机执行器 |
| gxlf_sim_system/models/excel-*-state-machine.yaml | 两份分系统状态规则 |
| gxlf_sim_system/simulation.py | 演示用反馈证据 |
| gxlf_sim_system/tests/ | 分系统状态机、设备持久化和 Tango 协议测试 |

实验流程唯一执行定义位于主项目 `src/main/resources/processes/laser-joint.bpmn20.xml`，由 Flowable 调度。
本模块只保留两份分系统状态 YAML；Python 流程执行器、流程 YAML、操作台和演示入口已移除。
`make operator` 启动 Java 操作台（8080）；原 8765 操作台及 JSONL 回放工具已退出，已有日志和设备数据库保留。

Java 对流程进行编排，DeviceRuntime 通过分系统状态机接受或拒绝动作、校验完成证据并保存结果。
这里不再维护与 BPMN 并列的实验流程定义。

[主项目说明](../README.md) · [模型验证计划](../docs/model-correctness-verification.md)
