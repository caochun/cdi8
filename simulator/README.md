# SCXML 分系统仿真器

模拟器统一执行各分系统的 SCXML 状态机。SCXML 是完整模型和状态转换的唯一来源；Mermaid 只用于展示。标签、动作绑定、确认事件和上下文默认值都写在 SCXML 标准 `datamodel` 中，由同一个 `SCXMLMachine` 解析、同一个 `ComponentRuntime` 持久化任务和事件。

当前可选模型：

| 名称 | 模型 |
|---|---|
| `seed` | [种子源](../docs/seed-source-state-machine.scxml) |
| `shg` | [二倍频宽带激光注入组件](../docs/shg-injection-state-machine.scxml) |
| `preamp` | [再生与双程放大组件](../docs/preamp-state-machine.scxml) |
| `multipass` | [多程放大系统](../docs/multipass-state-machine.scxml) |

任务请求、反馈、超时和故障中断保存在任务记录中，不会添加“执行中”设备状态。设备状态只表示 SCXML 中定义的资格和工作阶段。每个 SQLite 文件只属于一个 SCXML 模型，打开其他模型或旧数据库会明确拒绝。

## 命令行

```bash
PYTHONPATH=simulator python3 -m gxlf_sim_system.cli --component seed demo
PYTHONPATH=simulator python3 -m gxlf_sim_system.cli --component preamp demo
PYTHONPATH=simulator python3 -m gxlf_sim_system.cli --component preamp --db /tmp/preamp.sqlite3 snapshot
```

手动驱动实例：

```bash
PYTHONPATH=simulator python3 -m gxlf_sim_system.cli --component seed --db /tmp/seed.sqlite3 request power_on_self_test selftest-1
PYTHONPATH=simulator python3 -m gxlf_sim_system.cli --component seed --db /tmp/seed.sqlite3 simulate selftest-1 success
PYTHONPATH=simulator python3 -m gxlf_sim_system.cli --component seed --db /tmp/seed.sqlite3 snapshot
```

安装后脚本名为 `component-sim` 和 `component-panel`。

## HTTP、Tango 和 GUI

本地 HTTP 网关可以创建多个同一 SCXML 模型的独立实例：

```bash
PYTHONPATH=simulator python3 -m gxlf_sim_system.tango.gateway \
  --component preamp --mode local --devices 01 02 --port 8766
```

可选 GUI 使用同一个 Tango `ComponentDevice` 和同一份 SCXML 模型。状态图由
SCHVIZ（SCION 的 SCXML 可视化组件）在 Qt WebEngine 中渲染，布局、缩放和层级
展开由 SCXML 图本身驱动，不再由 GUI 手工布置连线：

```bash
python3 -m pip install -e './simulator[gui]'
component-panel --component preamp --demo
```

GUI 将 SCHVIZ 和 SCION 的浏览器资源随 Python 包一起安装，因此运行时不需要网络。
界面状态图、按钮准入和设备快照都由所选 SCXML 派生，不再单独维护组件状态机代码。

## 验证

```bash
PYTHONPATH=simulator python3 -m unittest discover -s simulator/gxlf_sim_system/tests -v
```
