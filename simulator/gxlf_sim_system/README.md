# 分系统仿真包

本包只负责分系统行为仿真及 Tango 接口。流程执行与操作员界面由根目录 Spring Boot + Flowable 应用提供。

- `subsystem_fsm.py`：单实例状态转移与完成证据校验。
- `simulation.py`：演示用成功证据。
- `tango/runtime.py`：幂等命令、状态与结果持久化。
- `tango/device.py`：PyTango Device Server。
- `tango/gateway.py`：Java 到 Tango 的适配入口。
- `models/excel-seed-source-state-machine.yaml`、`models/excel-shg-injector-state-machine.yaml`：两份分系统状态规则。
- `tests/`：状态机、持久化与真实 Tango 协议验证。

运行方式见 [模块说明](../README.md)。旧流程执行器、操作台与流程 YAML 已删除，历史可在 Git 中查看。
