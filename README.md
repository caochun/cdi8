# CDI8 实验流程与组件状态机工作台

项目定位为实验流程模型及流程涉及的组件状态机的开发、运行和调试工作台，支持 **BPMN / SCXML 模型编辑、校验、版本管理、模拟环境配置和流程联调**。浏览器通过 Vue / Monaco 编辑模型，Java / Flowable 执行 BPMN，独立的设备模拟服务通过真实 Tango 命令和属性事件提供组件行为。

GXLF 十四类设备模型、设施定义/绑定/状态投影与可执行的初始化及参数接收流程作为内置业务示例。示例源文件、执行实现和用户运行数据分别维护。

```text
浏览器工作台
  │ REST
Java 工作台后端：模型版本、环境配置、Flowable 流程、运行历史
  │ HTTP 命令 / NDJSON 设备事件
Java Tango bridge（管理模拟环境生命周期）
  │ JTango Command / CHANGE_EVENT
Java Device Server：多个 SCXML 组件实例及条件设备 + 独立 SQLite
```

## 目录职责

| 目录 | 职责 |
|---|---|
| [frontend/](frontend/) | Vue / TypeScript 工作台、编辑器、图形预览及公共浏览器资源 |
| [model-contract/](model-contract/) | 服务端与设备共用的 SCXML 元数据结构和静态引用校验 |
| [server/](server/) | Spring Boot 后端和 Flowable；按模型、运行、集成和追踪分包 |
| [examples/minimal/](examples/minimal/) | 新建模型使用的最小 BPMN / SCXML，前端直接导入这些 XML |
| [examples/gxlf/](examples/gxlf/) | GXLF 十四类 SCXML、设施模型、可执行初始化切片和完整发次设计 |
| [contracts/](contracts/) | 命令、结果、设备事件的接口约定和 JSON Schema |
| [device-simulator/](device-simulator/) | Java 17 + Apache Commons SCXML + JTango 的通用组件模拟器 |
| `server/`、`device-simulator/` | Java Workbench、Java Tango bridge 和设备服务生产代码 |
| [docs/architecture/](docs/architecture/) | 模块边界、资源归属及 Java 模拟环境生命周期 |
| [docs/development/](docs/development/) | 开发工作台操作和模型约定 |
| [docs/domain/gxlf/](docs/domain/gxlf/) | GXLF 状态定义与业务流程说明 |
| `data/` | 本地模型库、配置、数据库和运行事件，不提交 Git |

Java 构建从 `examples/` 打包模型，浏览器资源从 `frontend/` 打包；默认运行链路只使用 Java。

## 启动

需要 Java 17+、Maven、Node.js 22.12+。以下命令在仓库根目录执行。

```bash
# 构建两个 Java 模块和前端，并在后台启动 Java bridge 与工作台
make workbench

# 查看状态、停止；重新打包并重启
make workbench-status
make workbench-stop
make workbench-restart
```

访问 **http://127.0.0.1:8080/**。首次启动 bridge 没有设备，在“运行准备”选择模型版本后点击“创建模拟设备”；“使用示例方案”会选择 `gxlf_shot_run_smoke`，预填十四类组件与本发参数。Java bridge 创建一个设备子 JVM，承载全部组件实例和同步条件设备。

JAR 运行副本、PID 和日志保存在 `data/runtime/`，构建目录可以安全重新打包。已有工作台模型、版本、配置及 Flowable 数据继续使用 `data/`；历史运行仍可查看。

也可分开启动：

```bash
mvn package
java -jar device-simulator/target/device-simulator-0.1.0.jar --bridge
java -jar server/target/workbench-server-0.1.0.jar
```

后端通过 `TANGO_BRIDGE_URL` 配置 bridge 地址，默认 `http://127.0.0.1:8766`。真实设备仍可用 Java bridge 的 `--devices bindings.json` 接入，该模式不开放模拟环境重建。

1. **模型开发**：编辑或导入模型，校验并保存不可变版本；SCXML 可以创建独立试运行。
2. **运行准备**：选择流程版本，自动识别子流程依赖，配置组件模型、数量和反馈方式；检查设备是否就绪，必要时创建或重新创建模拟设备，再开始调试。设备集合、参数与初始条件放在高级配置中。
3. **运行调试**：启动流程，查看活动节点、等待条件、命令结果、设备状态及关联事件。

自动反馈只完成演示动作，外部许可和环境条件仍需明确确认。“使用示例方案”只预填表单，“保存方案”保存选择。“创建／重新创建模拟设备”会创建新一代实例并恢复初始状态；运行中的流程会阻止重建。“开始调试”沿用设备当前状态。停止流程终止后续编排，已受理的设备动作仍可能继续。

运行调试原生集成在工作台内，包含流程与子流程进度、设备状态机、等待诊断、动作反馈和事件时间线，不再嵌入独立运行页。携带 `?run=<运行ID>` 可直接查看指定运行记录。详细操作见[工作台开发说明](docs/development/workbench.md)。

## 开发与验证

```bash
# 构建前端并打包；根 POM 默认聚合 device-simulator 和 server 模块
mvn clean package

# 已有前端构建时只打包 Java 模块
mvn -Dexec.skip=true package

# 新链路端到端测试：随机端口、临时数据库、真实 Java Tango 设备
mvn -Pnew-chain-e2e verify

# 启动 Java 后端，工作目录保持为仓库根目录
make operator

# 只开发前端；API 代理至 8080，运行页使用后端的已构建布局资源
npm run dev --prefix frontend
```

也可以用下面的目标构建并执行 Java 新链路端到端测试：

```bash
make java-workbench-smoke
```

`NewChainIT` 通过工作台 HTTP API 创建模拟环境，验证十四类设备初始化、参数接收、多实例动作证据，以及失败、目标状态不符、取消、超时、失联、guard 拦截和循环重入。完整正式发射、安全联锁及资源服务接入仍见 [todo.md](todo.md)。

## 运行数据和当前范围

- 工作台数据库默认 `data/control-v2`；模型、版本、运行方案与运行快照保存在 `data/workbench/`。
- Java 独立试验位于 `data/java-simulator/trials/`；每代模拟环境的模型、配置、SQLite 和设备日志位于 `data/java-simulator/environments/<环境ID>/`。
- 模型编辑不覆盖 `examples/`；导出 XML 后可经代码审查更新示例源文件。
- SCXML 校验、独立试验及模拟设备统一使用 Java / Apache Commons SCXML；组件协议要求单一活动叶子状态，不支持 `parallel`。
- 一个 Java 设备 JVM 按选定的 SCXML 元数据承载多个逻辑设备，Java bridge 通过真实 JTango 命令、属性和事件访问它们；事件不可用时补读快照。
- 唯一执行链路是 `system-definition + facility-binding + facility-state-projection + shot-run + BPMN fac:action + Java SCXML/Tango`。旧综合系统状态运行时、GXLF 硬编码策略和旧 `POST /api/runs` 启动入口已删除；启动统一使用 `POST /api/workbench/runs`。
- `gxlf_shot_run_smoke` 完成仅代表初始化与本发参数接收切片完成，不表示正式发射。外部安全和真实服务心跳仍为 unknown；示例使用明确命名的模拟初始化 guard。

模块依赖及迁移边界见[架构说明](docs/architecture/workbench.md)，GXLF 验证范围见[示例说明](examples/gxlf/README.md)。

BPMN 自定义扩展、SCXML 元数据及跨模型引用约定见[自定义模型契约](docs/architecture/custom-model-contracts.md)。
