# Java SCXML / Tango 模拟设备

`device-simulator/` 是 Java SCXML 组件模拟器。它按模型元数据加载 seed、shg、preamp 和 multipass 四类组件，并用真实 JTango 暴露设备，供工作台和 Flowable 通过同一 Tango 协议联调。一个 Java 进程可以承载多个逻辑设备；Python 多组件环境仍可作为协议对照实现。

## 组成

- `engine/ScxmlEngine`：Apache Commons SCXML 2.0 的固定时间戳快照 `2.0-20260701.010636-103`。状态、事件、层级进入/退出、事件匹配、守卫和赋值由 Apache 引擎处理。
- `runtime/ComponentModel`：读取 SCXML 的 `componentMetadata`，提供项目动作和确认协议的适配，不重新实现状态转换。
- `runtime/ComponentRuntime`：保持命令 ID 幂等、反馈关联、SQLite 记录、上下文和快照协议；动作和状态转换来自 SCXML 元数据。
- `tango/ComponentDevice`：JTango 设备接口，提供组件的 `Snapshot`、`Execute`、`Complete`、`Simulate`、`ReportEvent`、`GetResult` 和 `History`；同一设备类也承载 `component=context` 的条件设备，并提供 `SetConditions`。
- `SimulatorApplication`：无 Tango DB 模式启动一个或多个设备，输出可用于 PyTango/JTango `DeviceProxy` 的地址。
- `bridge/JavaControlBridgeApplication`：连接一组真实 Tango 设备，提供与 Python `control_bridge` 相同的 HTTP/NDJSON 边界。工作台继续访问 HTTP，不需要知道桥接实现语言。

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

# 一个 JVM 承载四个 Java 设备；devices.json 是数组
java -jar device-simulator/target/device-simulator-0.1.0.jar \
  --port 10000 --devices devices.json --ready-file java-ready.json

# 普通组件项至少包含 device、model；model 可用 examples/gxlf/scxml
# 中的文件名，db 可选。上下文条件设备不需要 model，写 component=context：
# {"device":"gxlf/context/01","component":"context","contextDefaults":{"safety_permit":false}}
# 完成联调后，用 ready.json 的 address 生成 bridge.json：
# {"seed_01":{"component":"seed","address":"tango://..."}, ...}
java -jar device-simulator/target/device-simulator-0.1.0.jar \
  --bridge --port 8766 --devices bridge.json
```

Java 设备默认等待硬件反馈；`--auto-complete` 只用于演示。设备使用 `-nodb`，不会修改本机 Tango 数据库。
Java bridge 的 HTTP 路径保持原有契约：`/devices`、`/devices/{id}`、`/devices/{id}/commands`、`/devices/{id}/complete`、`/devices/{id}/simulate`、`/devices/{id}/events`、`/devices/{id}/conditions`、`/devices/{id}/history`、`/devices/{id}/commands/{commandId}`、`/events` 和 `/lab/environment`。上下文设备支持 `SetConditions`、`preamp_repeat` 和完成反馈；普通 SCXML 组件仍使用自身声明的动作。若 Tango 部署没有可用的 ZMQ event 服务，bridge 会自动使用短周期快照补读，仍保持同一 NDJSON 事件格式。

## 验证

```bash
# 仅验证 Java 引擎、运行时、SQLite 幂等和重启恢复
.venv/bin/python tests/e2e/java_seed_smoke.py --java \
  /opt/homebrew/opt/openjdk@17/bin/java --skip-workflow

# 继续把 Java seed 和 Python 的其余组件接入 Flowable
.venv/bin/python tests/e2e/java_seed_smoke.py --java \
  /opt/homebrew/opt/openjdk@17/bin/java
```

验证内容包括：模型加载、层级事件、事件前缀、守卫与赋值、命令受理、中文 JSON、重复请求、重复反馈、故障中断、恢复、进程重启后的 SQLite 恢复、`Snapshot CHANGE_EVENT` 订阅，以及完整发射任务。

Python 实现仍可作为行为基准。两套运行时共享 `examples/gxlf/scxml/` 下的四个状态机和 `contracts/` 定义，各自使用独立数据库；Java 侧已对四个模型执行核心动作链测试。
