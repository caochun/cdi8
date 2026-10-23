# 工作台模块与资源边界

项目定位是实验流程模型及流程涉及的组件状态机的开发、运行和调试工作台，统一支持模型编辑、版本管理、模拟环境配置和流程联调。GXLF 是内置示例，不定义平台可使用的全部设备类型或流程。

## 当前模块

- `frontend/`：所有浏览器代码和第三方绘图资源。Vue 构建产物进入根目录 `target/workbench-static/`；vendor 与状态图布局资源位于 `frontend/public/`，由 server 一并打包到静态资源根路径。`/` 直接提供工作台 HTML，无客户端或 HTTP 跳转。
- `server/`：Java Maven 模块，产物为 `server/target/workbench-server-0.1.0.jar`。根 POM 聚合模块并统一 Java / Flowable 版本；`mvn -pl server spring-boot:run` 从仓库根目录启动进程，继续使用原有 `data/`。
- `device-simulator/`：默认 Java bridge、模拟环境管理、独立试验及 Apache SCXML / JTango 多设备进程。
- `simulator/`：独立的 Python 协议对照源码，不参与默认 Java 构建、启动和端到端验证。
- `examples/`：示例模型唯一的版本化源文件。`minimal/` 直接供前端新建模型使用；`gxlf/bpmn/` 和 `gxlf/scxml/` 供 Java 内置模型导入与 Python 示例运行使用。
- `contracts/`：跨运行时的命令、结果与事件格式。协议格式不绑定 Python 类或 Java DTO。
- `server/src/test/java/`：Java 单元、集成及跨进程端到端验证；设备模块测试位于 `device-simulator/src/test/java/`。

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

## BPMN 资格与放行约束

`qualification.QualificationModel` 和 `QualificationRuntime` 解释命名空间 `https://cdi8.cn/control/qualification/v1` 下的 `ctl` 扩展。资格是本次流程的放行凭据，绑定流程实例、子流程作用域和设备实例；设备的 SCXML 业务状态保持独立。

| 声明 | 执行语义 |
|---|---|
| `qualificationPolicy` | 定义证据有效时间、未知时的处理方式和中断消息；作用域通过 `qualifications.open/close` 执行监听器管理 |
| `qualification` / `accept` | 固定参与实例集合，并按组件定义可接受的叶状态或外层状态；`states` 与 `outerStates` 为“或”关系 |
| `establish on="verifiedSuccess"` | 命令结果关联、成功状态和当前设备状态均通过验证后，为该实例建立资格 |
| `require at="dispatch" quantifier="all"` | 在组件命令实际下发前，补读权威状态并要求目标集合中的每个实例都持有有效资格 |
| `require at="completion" quantifier="all"` | 通过 `qualifications.check` 开始监听器，在完成确认节点再次复核全部资格 |

GXLF 初始化模型在上电检查成功后建立 `powerQualified`，功能检查下发前要求所有 `allComponents` 实例均保持该资格；功能检查成功后建立 `functionQualified`，最终 `finish` 复核两类资格。上电资格允许设备正常进入 `ready`，避免将功能检查成功误判为上电资格丢失。`allComponents` 不包含条件设备。

建立的资格在初始化作用域结束前持续受监测。运行推进器默认每 200 ms 处理事件，并按证据有效期补读 Tango 状态；下发和完成检查强制补读。当前模型的 `evidenceMaxAgeMs` 为 2000 ms。连接中断、权威读取失败、状态不再满足谓词、设备实例更换或证据不连续都会锁存失败，通过 `InitializationQualificationLost` 消息边界事件中断初始化。设备随后恢复不会恢复本次运行资格。

资格记录保存在 Flowable 变量 `qualificationRegistry`；退出作用域后将该作用域记录保存为 `qualificationAudit`。控制端恢复时重新读取设备状态，要求设备 `source_id` 未变且状态版本与保存的证据连续。图形视图直接读取 BPMN 扩展显示资格声明。

事件处理和补读受网络及调度延迟影响，跨设备快照也不是原子快照。此机制阻止控制端在已发现资格失效后继续下发命令；设备仍须在受理命令时检查自身条件，已受理动作的停止依赖设备联锁或停止协议。

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

## Java 模拟环境生命周期

默认链路为浏览器 → Java Workbench / Flowable → Java HTTP bridge → JTango → Java Device Server。根 POM 同时构建 `device-simulator` 与 `server`。`make workbench` 使用 Java 源码启动器管理两个常驻服务；环境创建由 bridge 管理设备子 JVM，无需将全部服务放入一个 JVM。

`EnvironmentManager` 接受所选版本的 XML、实例 ID、反馈方式、延迟与外部条件。每代生成唯一 ID，并在 `data/java-simulator/environments/<ID>/` 固定模型 XML、请求配置、设备配置、SQLite 与日志。新设备使用独立的随机端口与 DServer 身份；所有 Tango Snapshot 可读后，bridge 才替换绑定、订阅和事件流，然后关闭旧设备进程。失败保留旧环境；失败代日志也保留供排查。

每代 Session 独占事件订阅、快照缓存和客户端队列，旧订阅的迟到事件不能进入新流。设备进程退出后环境状态为 `failed`，bridge 不自动以初始设备替代在途状态。Workbench 继续禁止活动流程期间重建；停止运行后可显式重建。bridge 重启得到新的空环境，旧配置无法直接启动流程，必须重建。正常关闭会清理设备子进程；父进程意外消失时子进程自退出。

SCXML 校验、独立试验和真实设备使用同一 `ComponentModel` / Apache 引擎，独立试验不改变联调设备。Java 设备快照携带工作台 `model_revision`，运行页按实际不可变版本绘图，历史记录保留当时的快照与模型引用。

外部设备模式仍使用 `--devices bindings.json`，只绑定已运行的 Tango 设备，禁止通过创建接口伪造模型版本或“已应用”状态。

`make java-workbench-smoke` 从隔离临时目录启动 Java Workbench、Java bridge 和 Java Tango 设备进程，验证通用模型双实例、资格失效阻断、四组件初始化，以及完整 GXLF 双光源流程。Flowable 阶段推进依赖实际 JTango 命令、快照属性和状态事件；直接调用对象的单元测试不替代该测试。

构建与设备接口详见 [Java SCXML / Tango 模拟设备](java-device-simulator.md)。
