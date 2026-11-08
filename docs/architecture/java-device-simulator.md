# Java SCXML / Tango 模拟设备

`device-simulator/` 提供独立的 Java SCXML/Tango 设备 JVM；`tango-adapter/` 是设施后端内嵌的 JTango 库。设施后端直接获取设备观测，工作台经设施 HTTP 接口进行 Flowable 联调。

## 组成

- `engine/ScxmlEngine`：使用 BaSyS 发行的 Apache Commons SCXML 2（`de.dfki.cos.basys.common:scxml:0.5.1`），处理状态迁移、层级进入/退出、事件匹配、守卫和赋值。
- `runtime/ComponentModel`：使用共享 `component-contract/ComponentContract` 严格校验项目 `componentMetadata` 及状态/事件引用，并通过同一 Apache 引擎检查可执行性；组件协议要求单一活动叶子状态，不支持 `parallel`。
- `runtime/ComponentRuntime`：命令幂等、反馈关联、SQLite、组件上下文和快照；快照包括实际工作台发布快照标识 `model_revision` 及 XML 摘要 `model_digest`（运行时不选择版本分支）。
- `tango/ComponentDevice`：JTango 的 `Snapshot` 属性及 `Execute`、`Complete`、`Simulate`、`ReportEvent`、`GetResult`、`History` 命令；同一设备类承载 context 条件设备及 `SetConditions`。
- `SimulatorApplication`：一个 JVM 承载多个逻辑设备，使用 `-nodb`、独立 DServer 身份及端口。
- `tango-adapter/TangoAdapter`：进程内 JTango DeviceProxy；每代连接独占绑定、订阅及快照。
- `environment/EnvironmentConfiguration`：在设备 JVM 内按不可变 XML 和设备参数生成配置。
- `facility-server/simulation/SimulatorLauncher`：管理模拟设备子进程、启动就绪与失败清理。

设备端 `Snapshot` 属性和 Tango CHANGE_EVENT 使用同一个观测信封。组件设备至少提供：
`snapshot_kind=component`、`source_id`、非负 `state_revision`、`model`、`model_version`、
`model_digest`、组件状态、任务状态、活动命令/动作、动作契约、上下文和最近一次结果；
条件源设备使用 `snapshot_kind=condition-source`，并提供 `conditions`、`model`、`componentLabel`、
`engine`、任务状态、活动命令/动作和最近一次结果。活动命令与动作必须成对出现，活动任务的
`task_state` 必须为 `executing`。适配器验证结构和字段间一致性，设施运行时再验证来源代次、
修订号连续性和证据时效；两者都不解析 BPMN 或监督语义。

`state_revision` 是设备状态的单调修订号，不是 Tango 事件序号。设备状态改变时必须递增，
CHANGE_EVENT 丢失时设施后端通过属性补读恢复最新快照。重复的同值事件在适配器缓存层折叠，
而补读到的新快照仍会立即发布给设施运行时。

SCXML 引擎使用 [Maven Central 上的固定发行包](https://repo.maven.apache.org/maven2/de/dfki/cos/basys/common/scxml/0.5.1/)，无需 Apache 快照仓库。`0.5.1` 是 DFKI/BaSyS 的发行版本号，包内仍为 `org.apache.commons.scxml2`；它不是 Apache 官方的 SCXML 2 正式发行版。JEXL 固定为 3.6.4，并显式允许 SCXML 内置 `In()` 函数，保留其余默认权限限制及无副作用守卫校验。JTango Server 使用 10.0.1，许可证 LGPL-3.0。SQLite 恢复叶子状态和项目组件上下文，尚不保证任意 SCXML 数据模型、定时器或外部副作用的恢复。

## 默认构建和启动

```bash
# Java 17+、Maven、Node.js 22.12+；项目根目录
make simulation
make simulation-status
make simulation-stop
make simulation-restart
```

根 `mvn package` 构建共享 `component-contract`、`facility-contract`、`tango-adapter`、`device-simulator`、`facility-server`、`workbench-server` 及前端。单独构建设备模块使用 `mvn -pl device-simulator -am package`。

也可分别启动：

```bash
mvn package
java -jar facility-server/target/facility-server-0.1.0.jar --control.simulation.enabled=true
java -jar workbench-server/target/workbench-server-0.1.0.jar
```

设施后端启用仿真后按 `control.simulation.profile` 建立独立 facility；默认 `classpath:simulation/gxlf-facility.json` 包含十四类组件和 `context_01`。工作台可以通过设施的 `POST /api/facility/environment` 提交另一套不可变设备配置，但不拥有设备进程。SCXML、版本、延迟、自动反馈和自定义条件均按请求真实应用。新设备全部可读后才切换；失败保留旧环境。重建恢复初态并生成新的环境 ID 和数据库。监督端检查物理义务、未决命令和设备活动命令。

设施后端关闭会清理设备子进程；设施后端意外退出时子进程检测父进程并退出。设备崩溃将环境标为 `failed`；设施后端重启时按 profile 建立新的环境，不会用旧 ID 宣称就绪。

运行数据：

- `data/runtime/`：启动器的稳定 JAR 副本、PID、服务日志。
- `data/java-simulator/environments/<环境ID>/`：模型 XML、`request.json`、`devices.json`、环境描述、各设备 SQLite 和 `device-server.log`；失败记录也保留。

## 外部设备及单独启动

```bash
# 一个 JVM 按配置承载多个设备；devices.json 为数组
java -jar device-simulator/target/device-simulator-0.1.0.jar \
  --port 10000 --devices devices.json --ready-file java-ready.json

# 设施后端绑定外部已有设备；bindings.json 为对象，无需模拟器
java -jar facility-server/target/facility-server-0.1.0.jar \
  --control.tango-bindings=bindings.json --control.simulation.enabled=false
```

设备数组示例：

```json
[
  {"device":"gxlf/seed/seed_01","model":"seed-source-state-machine.scxml",
   "db":"data/standalone/seed.sqlite3","revision":"selected-version-id","autoComplete":false,"delayMs":500},
  {"device":"gxlf/context/context_01","kind":"context",
   "contextDefaults":{"alignment_ready":false},"autoComplete":false}
]
```

普通组件的 `model` 可用本地路径或内置模型文件名。未配置 `autoComplete` 时等待明确反馈；自动完成仅用于模拟动作，不会自动确认外部许可。`--port 0` 自动选择端口；ready 文件在写完后发布，包含每台设备地址。

外部绑定示例：

```json
{"seed_01":{"deviceBindingKey":"seed","address":"tango://127.0.0.1:10000/gxlf/seed/seed_01#dbase=no"}}
```

设施环境和设备观测使用设施后端的稳定接口：`GET/POST /api/facility/environment`、`GET /api/facility/devices`、`GET /api/facility/devices/{id}`、`POST /api/facility/validate`。`POST /api/facility/adapter` 只保留实验性的 conditions、simulate 和 events 控制。设备通知经 `/api/facility/events` 发给工作台。Tango CHANGE_EVENT 不可用时按秒补读快照。

外部设备绑定模式禁止创建或重建环境。SCXML 可执行性校验通过模拟器 JAR 的短时命令完成，仍需本地存在该 JAR；设备接入和监督本身不需要模拟器。可通过 `SIMULATOR_JAR` 和 `SIMULATOR_DIRECTORY` 配置模拟器产物及数据目录。旧 `--bridge` 和独立适配器可执行 JAR 已移除。

## 验证

```bash
# 默认 Java 模块及前端构建
mvn package
# 构建并启动隔离 Java 服务
make simulation-test
# 已打包时单独运行端到端测试
mvn -Pworkbench-e2e -Dexec.skip=true verify
```

`WorkbenchIT` 使用 Java Workbench、内嵌 Tango 接入的设施后端、Java Tango 设备进程和 Flowable，覆盖十四类设备初始化、参数接收、多实例证据、命令失败、目标状态不符、取消、超时、设备崩溃、guard 拦截及循环重入。完整发射、资源租约和外部安全事实仍需继续补充测试。
