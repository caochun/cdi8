# 已移除的 Python 流程对照实现

旧 Python 流程执行器、流程 YAML、操作台和演示入口已退出当前版本。
本文件保留迁移说明，不再作为运行指南。

当前入口：

- [Spring Boot + Flowable 主项目](../README.md)：实验流程编排和操作台（8080）。
- [分系统仿真模块](../simulator/README.md)：分系统状态模型、PyTango Device Server、网关。
- [可执行 BPMN](../src/main/resources/processes/laser-joint.bpmn20.xml)：唯一运行时实验流程定义。

历史实现可在 Git 提交 `2a0b17e` 查看，例如：

```bash
git show 2a0b17e:simulator/gxlf_sim_system/joint_fanout_experiment.py
git show 2a0b17e:simulator/gxlf_sim_system/models/laser-joint-fanout-experiment.yaml
```

现有 JSONL 文件和设备 SQLite 数据库仍保留在本地 output 目录。Java 暂不提供旧 JSONL 回放，
需要查阅旧格式时见 [历史操作台日志说明](operator-console.md)。
