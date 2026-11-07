# CDI8 设施运行系统与实验工作台

项目包含独立的设施运行后端，以及实验流程与组件状态机工作台。设施后端实现 **设备观测验证 → 设施事实 → 系统监督与动作准入**；工作台支持 **BPMN / SCXML 模型编辑、校验、版本管理、模拟环境配置和流程联调**。两个 Java 后端分别运行，监督不依赖浏览器或 Flowable 的生命周期。

GXLF 十四类设备模型、设施定义/绑定/状态投影与可执行的初始化及参数接收流程作为内置业务示例。示例源文件、执行实现和用户运行数据分别维护。

```text
浏览器工作台
  │ REST
Java 工作台后端：模型版本、环境配置、Flowable 流程、运行历史
  │ HTTP 设施接口 / NDJSON 通知（默认 8081）
Java 设施后端：观测验证、事实投影、工作状态义务、动作准入与证据持久化
  │ 内嵌 TangoAdapter
  │ JTango Command / CHANGE_EVENT
Java Device Server：多个 SCXML 组件实例及条件设备 + 独立 SQLite
```

## 目录职责

| 目录 | 职责 |
|---|---|
| [workbench-ui/](workbench-ui) | Vue / TypeScript 工作台、编辑器、图形预览及公共浏览器资源 |
| [component-contract/](component-contract) | 服务端与设备共用的 SCXML 元数据结构和静态引用校验 |
| [facility-contract/](facility-contract) | 两个后端共用的设施 XML 模型、解析器、XSD 及静态引用校验 |
| [facility-server/](facility-server) | 设施运行后端，拥有设备观测、事实、监督义务和命令准入 |
| [workbench-server/](workbench-server) | 工作台 Spring Boot 后端和 Flowable，通过监督接口访问运行系统 |
| [examples/minimal/](examples/minimal) | 新建模型使用的最小 BPMN / SCXML，前端直接导入这些 XML |
| [examples/gxlf/](examples/gxlf) | GXLF 十四类 SCXML、设施模型、可执行初始化切片和完整发次设计 |
| [docs/interfaces/](docs/interfaces) | 命令、结果、事件和发次格式参考；不参与运行时校验 |
| [tango-adapter/](tango-adapter) | 设施后端内嵌的 JTango 接入库，负责连接、命令、快照和事件，无 SCXML 依赖 |
| [device-simulator/](device-simulator) | Java 17 + Apache Commons SCXML + JTango 的通用组件模拟器 |
| [docs/architecture/](docs/architecture) | 模块边界、资源归属及 Java 模拟环境生命周期 |
| [docs/development/](docs/development) | 开发工作台操作和模型约定 |
| [docs/domain/gxlf/](docs/domain/gxlf) | GXLF 状态定义与业务流程说明 |
| [archive/](archive/README.md) | 历史方案、调研、展示原型和旧实施清单，不参与运行 |
| `data/` | 本地模型库、配置、数据库和运行事件，不提交 Git |

Java 构建从 `examples/` 打包模型，浏览器资源从 `workbench-ui/` 打包；默认运行链路只使用 Java。

模块、Java 包和启动命令的对应关系见[代码组织](docs/architecture/modules.md)。

## 启动

需要 Java 17+、Maven、Node.js 22.12+。以下命令在仓库根目录执行。

```bash
# 构建全部模块与前端，后台启动设施后端和工作台
make simulation

# 查看状态、停止；重新打包并重启
make simulation-status
make simulation-stop
make simulation-restart
```

访问 **http://127.0.0.1:8080/**。首次启动时模拟环境为空，在“运行准备”选择模型版本后点击“创建模拟设备”；“使用示例方案”会选择 `gxlf_shot_run_smoke`，预填十四类组件与本发参数。设施侧的 SimulatorLauncher 创建一个设备子 JVM，承载全部组件实例和同步条件设备。

JAR 运行副本、PID 和日志保存在 `data/runtime/`，构建目录可以安全重新打包。已有工作台模型、版本、配置及 Flowable 数据继续使用 `data/`；历史运行仍可查看。

也可分开启动：

```bash
mvn package
java -jar facility-server/target/facility-server-0.1.0.jar --control.simulation.enabled=true
java -jar workbench-server/target/workbench-server-0.1.0.jar
```

以上两个 JAR 各在一个终端运行。工作台通过 `FACILITY_URL` 连接设施后端，默认 `http://127.0.0.1:8081`；设施后端直接通过 JTango 访问设备。创建模拟环境后总计三个常驻 JVM：工作台、设施后端、模拟设备。真实设备使用设施后端的 `--control.tango-bindings=bindings.json` 接入，并关闭模拟环境创建。

两个后端可以单独构建及启动，适用于先验证观测、事实和监督接口：

```bash
make backend-package
# 两个终端分别执行
java -jar facility-server/target/facility-server-0.1.0.jar
java -jar workbench-server/target/workbench-server-0.1.0.jar

# 已构建完整模拟链路时，只启动监督侧（按需管理模拟设备子进程）
make facility-start
# 单独重启工作台，监督侧持续运行
make workbench-restart
```

监督健康接口为 `http://127.0.0.1:8081/api/facility/health`；`/facts?refresh=true` 查看观测与事实，`/state?refresh=true` 额外查看监督约束。未绑定设备时服务仍可启动，设施状态保持 unknown，受保护动作不会放行。部署边界与恢复语义见[两个 Java 后端的运行边界](docs/architecture/facility-runtime.md)。

1. **模型开发**：编辑或导入模型，校验并保存不可变版本，SCXML 保存的版本可用于创建模拟设备。
2. **运行准备**：选择流程版本，自动识别子流程依赖，配置组件模型、数量和反馈方式；检查设备是否就绪，必要时创建或重新创建模拟设备，再开始调试。设备集合、参数与初始条件放在高级配置中。
3. **运行调试**：启动流程，查看活动节点、等待条件、命令结果、设备状态及关联事件。

自动反馈只完成演示动作，外部许可和环境条件仍需明确确认。“使用示例方案”只预填表单，“保存方案”保存选择。“创建／重新创建模拟设备”会创建新一代实例并恢复初始状态；活动流程、未决命令或尚无安全退出证据的物理工作状态都会阻止重建。“开始调试”沿用设备当前状态。停止流程终止后续编排，已受理的设备动作仍可能继续。

运行调试原生集成在工作台内，包含流程与子流程进度、设备状态机、等待诊断、动作反馈和事件时间线，不再嵌入独立运行页。携带 `?run=<运行ID>` 可直接查看指定运行记录。详细操作见[工作台开发说明](docs/development/workbench.md)。

## 开发与验证

```bash
# 构建全部 Java 模块及前端
mvn clean package

# 仅验证两个后端及共享契约，不构建设备模拟器
mvn -pl facility-server,workbench-server -am -Dexec.skip=true test

# 已有前端构建时只打包 Java 模块
mvn -Dexec.skip=true package

# 工作台端到端测试：随机端口、临时数据库、真实 Java Tango 设备
mvn -Pworkbench-e2e verify

# 启动 Java 后端，工作目录保持为仓库根目录
make workbench

# 只开发前端；API 代理至 8080，运行页使用后端的已构建布局资源
npm run dev --prefix workbench-ui
```

也可以用下面的目标构建并执行 Java 工作台端到端测试：

```bash
make simulation-test
```

`WorkbenchIT` 通过工作台 HTTP API 创建模拟环境，验证十四类设备初始化、参数接收、多实例动作证据，以及失败、目标状态不符、取消、超时、失联、guard 拦截和循环重入。完整正式发射、安全联锁及资源服务接入仍见 [当前范围与验证](docs/development/validation.md)。

## 运行数据和当前范围

- 工作台数据库默认 `data/control-v2`；模型、版本、运行方案与运行快照保存在 `data/workbench/`。
- 监督模型、工作状态义务、锁存事件和命令日志保存在 `data/supervision/`，可用 `FACILITY_STORE` 修改。重启工作台不清除此证据；监督重启也不会将持续监控中断当作条件始终成立。
- 每代模拟环境的模型、配置、SQLite 和设备日志位于 `data/java-simulator/environments/<环境ID>/`。
- 模型编辑不覆盖 `examples/`；导出 XML 后可经代码审查更新示例源文件。
- SCXML 校验及模拟设备统一使用 Java / Apache Commons SCXML；组件协议要求单一活动叶子状态，不支持 `parallel`。
- 一个 Java 设备 JVM 按选定的 SCXML 元数据承载多个逻辑设备，设施后端通过内嵌适配库的 JTango 命令、属性和事件访问它们；事件不可用时补读快照。
- 唯一执行链路是 `system-definition + facility-binding + facility-state-projection + shot-run + BPMN fac:action + Java SCXML/Tango`。旧综合系统状态运行时、GXLF 硬编码策略和旧 `POST /api/runs` 启动入口已删除；启动统一使用 `POST /api/workbench/runs`。
- `gxlf_shot_run_smoke` 完成仅代表初始化与本发参数接收切片完成，不表示正式发射。外部安全和真实服务心跳仍为 unknown；示例使用明确命名的模拟初始化 guard。

模块依赖及迁移边界见[架构说明](docs/architecture/workbench.md)，GXLF 验证范围见[示例说明](examples/gxlf/README.md)。

BPMN 自定义扩展、SCXML 元数据及跨模型引用约定见[自定义模型契约](docs/architecture/custom-model-contracts.md)。

系统定义、状态投影/监督与 BPMN 的职责，以及按业务说明修订的完整发次设计见[监督运行边界](docs/architecture/facility-runtime.md)。真实联锁未知时，受保护动作会被阻止；完整发次仍是不可执行设计，初始化切片可用于 Java 联调。

当前核心实现与验证顺序见[核心链路](docs/development/validation.md)，文档入口见[文档导航](docs/README.md)。独立 SCXML 试验入口已移除；通过保存版本、创建模拟设备并注入反馈验证运行链路。历史资料见[归档说明](archive/README.md)。
