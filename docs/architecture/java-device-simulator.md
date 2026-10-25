# Java SCXML / Tango 模拟设备

`device-simulator/` 是工作台唯一的 Java 设备运行服务。模型校验、独立试验、动态环境创建和 SCXML 组件执行均在 Java 中完成，通过真实 JTango 与 Flowable 联调。Vue / TypeScript 继续负责浏览器界面。

## 组成

- `engine/ScxmlEngine`：Apache Commons SCXML 2.0 固定时间戳快照 `2.0-20260701.010636-103`，处理状态迁移、层级进入/退出、事件匹配、守卫和赋值。
- `runtime/ComponentModel`：读取项目 `componentMetadata`，并通过同一 Apache 引擎检查可执行性；组件协议要求单一活动叶子状态，不支持 `parallel`。
- `runtime/ComponentRuntime`：命令幂等、反馈关联、SQLite、组件上下文和快照；快照包括实际工作台版本 `model_revision`。
- `tango/ComponentDevice`：JTango 的 `Snapshot` 属性及 `Execute`、`Complete`、`Simulate`、`ReportEvent`、`GetResult`、`History` 命令；同一设备类承载 context 条件设备及 `SetConditions`。
- `SimulatorApplication`：一个 JVM 承载多个逻辑设备，使用 `-nodb`、独立 DServer 身份及端口。
- `bridge/JavaControlBridgeApplication`：HTTP/NDJSON → JTango DeviceProxy；每代环境独占绑定、订阅、快照及事件流。
- `bridge/EnvironmentManager`：按工作台提交的不可变 XML 和设备参数生成新环境，管理 Java 设备子进程及失败清理。
- `bridge/TrialManager`：独立模型试验，使用同一引擎及运行时，固定 XML 和 SQLite，不修改联调设备。

Apache Commons SCXML 依赖固定时间戳，不使用动态 `SNAPSHOT`。JTango Server 使用 10.0.1，许可证 LGPL-3.0。SQLite 恢复叶子状态和项目组件上下文，尚不保证任意 SCXML 数据模型、定时器或外部副作用的恢复。

## 默认构建和启动

```bash
# Java 17+、Maven、Node.js 22.12+；项目根目录
make workbench
make workbench-status
make workbench-stop
make workbench-restart
```

根 `mvn package` 默认构建 `device-simulator`、`server` 及前端。`java-simulator` profile 保留为空的兼容入口，原有 `-Pjava-simulator -pl device-simulator` 命令仍可使用。

也可分别启动：

```bash
mvn package
java -jar device-simulator/target/device-simulator-0.1.0.jar \
  --bridge --port 8766 --data-dir data/java-simulator
java -jar server/target/workbench-server-0.1.0.jar
```

默认 bridge 以空环境启动。页面选择组件版本、数量和反馈方式后，“创建模拟设备”会创建一个承载全部组件及 `context_01` 的 Java 子 JVM。SCXML、版本、延迟、自动反馈和自定义条件均按请求真实应用。新设备全部可读后才切换；失败保留旧环境。重建恢复初态并生成新的环境 ID 和数据库。Workbench 禁止有活动流程时重建。

bridge 关闭会清理设备子进程；bridge 意外退出时子进程检测父进程并退出。设备崩溃将环境标为 `failed`；bridge 重启为空环境，必须显式重建，不会用旧 ID 宣称就绪。

运行数据：

- `data/runtime/`：启动器的稳定 JAR 副本、PID、服务日志。
- `data/java-simulator/trials/<试验ID>/`：独立试验 XML 和 SQLite。
- `data/java-simulator/environments/<环境ID>/`：模型 XML、`request.json`、`devices.json`、环境描述、各设备 SQLite 和 `device-server.log`；失败记录也保留。

## 外部设备及单独启动

```bash
# 一个 JVM 按配置承载多个设备；devices.json 为数组
java -jar device-simulator/target/device-simulator-0.1.0.jar \
  --port 10000 --devices devices.json --ready-file java-ready.json

# bridge 绑定外部已有设备；bindings.json 为对象，此模式禁止环境重建
java -jar device-simulator/target/device-simulator-0.1.0.jar \
  --bridge --port 8766 --devices bindings.json
```

设备数组示例：

```json
[
  {"device":"gxlf/seed/seed_01","model":"seed-source-state-machine.scxml",
   "db":"data/standalone/seed.sqlite3","revision":"selected-version-id","autoComplete":false,"delayMs":500},
  {"device":"gxlf/context/context_01","component":"context",
   "contextDefaults":{"alignment_ready":false},"autoComplete":false}
]
```

普通组件的 `model` 可用本地路径或内置模型文件名。未配置 `autoComplete` 时等待明确反馈；自动完成仅用于模拟动作，不会自动确认外部许可。`--port 0` 自动选择端口；ready 文件在写完后发布，包含每台设备地址。

外部绑定示例：

```json
{"seed_01":{"component":"seed","address":"tango://127.0.0.1:10000/gxlf/seed/seed_01#dbase=no"}}
```

HTTP 路径保持既有契约：`/devices`、`/devices/{id}`、设备 `commands` / `complete` / `simulate` / `events` / `conditions` / `history`、`/events`，以及 `/lab/validate`、`/lab/trials`、`/lab/environment`。Tango CHANGE_EVENT 不可用时按秒补读快照，维持相同的 NDJSON 格式。

## 验证

```bash
# 默认 Java 单元与集成测试
mvn package
# 构建并启动隔离 Java 服务
make java-workbench-smoke
# 已打包时单独运行端到端测试
mvn -pl server -Dexec.skip=true package
```

端到端测试覆盖：校验与独立试验、失败/成功反馈、双设备实例及隔离、固定模型版本、重复反馈、条件等待、自动完成、Flowable 历史和设备追踪、版本变更后重建、非法配置保留旧环境、设备崩溃恢复、bridge 重启，以及四组件与条件设备参与的 GXLF 双光源完整生命周期。

端到端验证只使用 Java Workbench、Java bridge、Java Tango 设备进程和 Flowable。
