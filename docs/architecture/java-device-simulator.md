# Java SCXML / Tango 模拟设备

`device-simulator/` 是 Java 种子源模拟器的验证原型。它不替换当前 Python 多组件环境；它用真实 JTango 暴露一个 `gxlf/seed/seed_01` 设备，供工作台和 Flowable 通过同一 Tango 协议联调。

## 组成

- `engine/ScxmlEngine`：Apache Commons SCXML 2.0 的固定时间戳快照 `2.0-20260701.010636-103`。状态、事件、层级进入/退出、事件匹配、守卫和赋值由 Apache 引擎处理。
- `runtime/ComponentModel`：读取 SCXML 的 `componentMetadata`，提供项目动作和确认协议的适配，不重新实现状态转换。
- `runtime/SeedRuntime`：保持命令 ID 幂等、反馈关联、SQLite 记录、上下文和快照协议。
- `tango/SeedDevice`：JTango 设备接口，提供 `Snapshot`、`Execute`、`Complete`、`Simulate`、`ReportEvent`、`GetResult` 和 `History`。
- `SimulatorApplication`：无 Tango DB 模式启动一个设备，输出可用于 PyTango `DeviceProxy` 的地址。

Apache Commons SCXML 版本目前来自 Apache snapshot 仓库，必须固定时间戳，不使用动态 `SNAPSHOT` 依赖。JTango Server 使用 Maven Central 的 10.0.1；其许可证是 LGPL-3.0。接入生产前应将 Apache 依赖切换到正式发布版本，或在项目制品库中冻结和审查当前快照。

## 构建和启动

```bash
# Java 17+
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  mvn -Pjava-simulator -pl device-simulator test package

java -jar device-simulator/target/device-simulator-0.1.0.jar \
  --port 10000 \
  --device gxlf/seed/seed_01 \
  --db data/java-device-simulator/seed_01.sqlite3 \
  --auto-complete --completion-delay-ms 500
```

Java 设备默认等待硬件反馈；`--auto-complete` 只用于演示。设备使用 `-nodb`，不会修改本机 Tango 数据库。

## 验证

```bash
# 仅验证 Java 引擎、运行时、SQLite 幂等和重启恢复
.venv/bin/python tests/e2e/java_seed_smoke.py --java \
  /opt/homebrew/opt/openjdk@17/bin/java --skip-workflow

# 继续把 Java 种子源和 Python 的其余组件接入 Flowable
.venv/bin/python tests/e2e/java_seed_smoke.py --java \
  /opt/homebrew/opt/openjdk@17/bin/java
```

验证内容包括：模型加载、层级事件、事件前缀、守卫与赋值、命令受理、中文 JSON、重复请求、重复反馈、故障中断、恢复、进程重启后的 SQLite 恢复、`Snapshot CHANGE_EVENT` 订阅，以及完整发射任务。

Python 实现仍是其余三类组件的行为基准。两套运行时共享 `examples/gxlf/scxml/seed-source-state-machine.scxml` 和 `contracts/` 定义，但各自使用独立数据库。只有通过协议级对照和实际设备替换验证后，才迁移其他组件。
