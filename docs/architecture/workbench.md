# 工作台模块与资源边界

项目定位是实验流程模型及流程涉及的组件状态机的开发、运行和调试工作台，统一支持模型编辑、版本管理、模拟环境配置和流程联调。GXLF 是内置示例，不定义平台可使用的全部设备类型或流程。

## 当前模块

- `frontend/`：所有浏览器代码和第三方绘图资源。Vue 构建产物进入根目录 `target/workbench-static/`；vendor 与状态图布局资源位于 `frontend/public/`，由 server 一并打包到静态资源根路径。`/` 直接提供工作台 HTML，无客户端或 HTTP 跳转；旧 `/workbench/`、`/runtime.html` 入口兼容同一应用。
- `server/`：Java Maven 模块，产物为 `server/target/workbench-server-0.1.0.jar`。根 POM 聚合模块并统一 Java / Flowable 版本；`mvn -pl server spring-boot:run` 从仓库根目录启动进程，继续使用原有 `data/`。
- `simulator/`：Python 对照实现，保留命令行、PyTango 设备和桥接接口。Java 设备模拟器和 Java HTTP bridge 可在联调时替代它；Java 后端不依赖其包内文件布局。
- `examples/`：示例模型唯一的版本化源文件。`minimal/` 直接供前端新建模型使用；`gxlf/bpmn/` 和 `gxlf/scxml/` 供 Java 内置模型导入与 Python 示例运行使用。
- `contracts/`：跨运行时的命令、结果与事件格式。协议格式不绑定 Python 类或 Java DTO。
- `tests/e2e/`：跨进程联调验证；各模块自己的单元与集成测试保留在模块内。

后端目前按职责分包：

| 包 | 职责 |
|---|---|
| `api` | REST 及页面入口 |
| `model` | 模型存储、校验、草稿编辑和版本发布 |
| `application` | 对 API 保持稳定的工作台门面 |
| `environment` | 运行方案存取、环境替换及空闲约束 |
| `run` | 固定版本的运行部署、Flowable 等待、通用命令推进和场景规则接口 |
| `scenarios.gxlf` | GXLF 的启动资格、阶段判据、持续资格和兼容表达式 |
| `integration.flowable` | 自定义组件命令活动和 BPMN 解析适配 |
| `integration.tango` | 设备服务 HTTP 客户端及事件连接 |
| `trace` | 按运行保存设备事件 |

`WorkbenchService` 保持 API 门面，委托 `ModelService`、`EnvironmentService` 和 `RunDeploymentService`。环境替换与发布共享环境锁，保留运行时禁止切换环境的约束。当前分包未增加新的部署服务。

## 前端开发与运行视图

工作台以同一个 Vue 应用管理模型开发、运行准备和运行调试。`App.vue` 负责工作区、运行选择、停止和启动配置；`components/runtime/RuntimeView.vue` 原生呈现运行概览、流程与子流程图、设备状态机、等待诊断、反馈和关联事件，不再通过 iframe 嵌入独立运行页面。

`components/preparation/RunPreparation.vue` 管理运行方案表单，按入口版本的 XML 解析可达子流程依赖并固定版本选择；设备集合、JSON 解析和配置检查位于 `configuration.ts`。运行检查将表单与已应用的设备配置、实际环境和设备连接作比较；保存方案、重建设备和开始调试使用各自独立的操作。环境与运行操作仍由后端执行最终校验。

运行视图的 controller 在组件内部创建 BPMN viewer，限定 DOM 查询与样式作用域；离开运行调试时取消请求、轮询、布局任务及图形实例。切换记录时保留所选运行的部署 XML 和设备快照语义，历史运行只读。SCXML 继续使用隐藏的状态图布局渲染器隔离 SCHVIZ；该渲染器只计算布局，不承载运行调试页面或执行状态机。

## 场景规则与通用执行

`run.RunPolicy` 描述内置场景的流程入口、默认参数、目标集合及参与设备展示；`RunPolicies` 按显式注册的流程 key 查找规则，并识别工作台发布的固定版本后缀。`run.ExecutionPolicy` 提供场景条件放行、持续资格和命令附加校验，通用运行层通过接口调用。

GXLF 的实现集中在 `scenarios/gxlf/`：

- `GxlfRunPolicy`：六个内置流程、光源选择、四类模型身份、阶段入口资格及默认集合。
- `GxlfCompletionChecks`：初始化、参数下发、准备、正式发射和后处理的最终证据。
- `GxlfExecutionPolicy`：GXLF 的安全许可、环境、供光、预放唤醒、同步资格及故障状态判定。
- `TangoTasks`：保留 `${tango.xxx(...)}` 的兼容门面，委托通用命令服务或 GXLF 判据，不再承担引擎实现。

通用组件活动使用 `run.ComponentCommandTasks`，只负责稳定命令 ID、受理登记、结果关联、期望状态和场景校验调用。固定组件类型、模型 ID 和阶段状态名不在通用运行或 API 中硬编码。

内置 GXLF 入口及其固定版本自动关联 GXLF 规则，变量 `scenarioId=gxlf` 也可显式选择这些资格检查。自定义模型默认只受自身声明的约束控制；相同的动作名、状态名或 `safety_permit` 条件名不会自动赋予 GXLF 语义。通用条件持续性由 `lab:maintain` 决定。

## 模型源文件与运行数据

```text
examples/ 中受 Git 管理的模型
    ↓ 构建时打包、首次启动导入
工作台草稿与不可变版本（data/workbench）
    ↓ 选择版本、发布、应用环境
运行部署及模拟实例持有的模型快照
    ↓
命令、设备事件及运行历史
```

GXLF 模型在 Java classpath 中继续使用 `processes/`、`component-models/` 路径，保持 Flowable 自动部署及现有 API 行为。该 classpath 布局是构建产物，不是模型的源码存放位置。

Python 在源码环境直接解析 `examples/gxlf/scxml/`。wheel 和 sdist 构建会将模型复制到安装包内部；这些副本由构建生成，不作为第二份模型源码维护。安装包必须能够脱离仓库加载四类模型。浏览器绘图资源仅由 `frontend/public/vendor/schviz/` 维护和分发，Python 模拟服务不依赖它们。

各代模拟环境使用独立的 Tango DServer 实例身份，避免旧服务退出时影响新一代设备的事件通道；逻辑设备 ID 和命令协议保持稳定。

用户草稿、版本、环境、实例数据库和日志不随目录重组自动迁移或重置。

## 后续迁移

1. 用 `device-simulator/` 的多设备 Java 进程和 Java HTTP bridge 替换默认演示环境，继续保持现有 HTTP/NDJSON 边界；组件设备和上下文条件设备已经可以在同一 JVM 中启动。
2. 继续对照 Python 实现验证动作反馈、幂等、实例隔离、故障恢复、断连与补读，并补齐生产部署和运维配置。
3. 在 Java 行为覆盖及部署验证完成后评估清理 Python 对照服务。前端独立运行页已迁入 Vue，旧静态页面及其脚本已移除。

该迁移保留真实 Tango 协议边界。引擎测试与 Tango 联调分别验证；直接调用 Java 对象不替代协议测试。暂不为每个概念建立独立 Maven 模块，也不新增没有实际使用者的公共框架。

Java 模拟设备原型的构建、许可证和验证范围见 [Java SCXML / Tango 模拟设备](java-device-simulator.md)。
