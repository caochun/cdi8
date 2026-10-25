# 工作台模块与资源边界

项目定位是实验流程模型及流程涉及的组件状态机的开发、运行和调试工作台，统一支持模型编辑、版本管理、模拟环境配置和流程联调。GXLF 是内置示例，不定义平台可使用的全部设备类型或流程。

## 当前模块

- `frontend/`：所有浏览器代码和第三方绘图资源。Vue 构建产物进入根目录 `target/workbench-static/`；vendor 与状态图布局资源位于 `frontend/public/`，由 server 一并打包到静态资源根路径。`/` 直接提供工作台 HTML，无客户端或 HTTP 跳转。
- `server/`：Java Maven 模块，产物为 `server/target/workbench-server-0.1.0.jar`。根 POM 聚合模块并统一 Java / Flowable 版本；`mvn -pl server spring-boot:run` 从仓库根目录启动进程，继续使用原有 `data/`。
- `device-simulator/`：默认 Java bridge、模拟环境管理、独立试验及 Apache SCXML / JTango 多设备进程。
- `examples/`：示例模型唯一的版本化源文件。`minimal/` 直接供前端新建模型使用；`gxlf/bpmn/`、`gxlf/components/` 和 `gxlf/system/` 分别提供 Java 内置流程、十四类设备模型和系统状态声明。
- `contracts/`：跨运行时的命令、结果与事件格式。
- 当前仓库暂不保留自动化测试源码；开发验证通过构建、启动 Workbench 和手工运行流程完成。

后端目前按职责分包：

| 包 | 职责 |
|---|---|
| `api` | REST 及页面入口 |
| `model` | 模型存储、校验、草稿编辑和版本发布 |
| `application` | 对 API 保持稳定的工作台门面 |
| `environment` | 运行方案存取、环境替换及空闲约束 |
| `run` | 固定版本的运行部署、Flowable 等待、通用命令推进和场景规则接口 |
| `scenarios.gxlf` | GXLF 示例的流程入口选择、参与设备集合和场景特定检查 |
| `integration.flowable` | 自定义组件命令活动和 BPMN 解析适配 |
| `integration.tango` | 设备服务 HTTP 客户端及事件连接 |
| `trace` | 按运行保存设备事件 |

`WorkbenchService` 保持 API 门面，委托 `ModelService`、`EnvironmentService` 和 `RunDeploymentService`。环境替换与发布共享环境锁，保留运行时禁止切换环境的约束。当前分包未增加新的部署服务。

## 前端开发与运行视图

工作台以同一个 Vue 应用管理模型开发、运行准备和运行调试。`App.vue` 负责工作区、运行选择、停止和启动配置；`components/runtime/RuntimeView.vue` 原生呈现运行概览、流程与子流程图、设备状态机、等待诊断、反馈和关联事件，不再通过 iframe 嵌入独立运行页面。

`components/preparation/RunPreparation.vue` 管理运行方案表单，按入口版本的 XML 解析可达子流程依赖并固定版本选择；设备集合、JSON 解析和配置检查位于 `configuration.ts`。运行检查将表单与已应用的设备配置、实际环境和设备连接作比较；保存方案、重建设备和开始调试使用各自独立的操作。环境与运行操作仍由后端执行最终校验。

运行视图的 controller 在组件内部创建 BPMN viewer，限定 DOM 查询与样式作用域；离开运行调试时取消请求、轮询、布局任务及图形实例。切换记录时保留所选运行的部署 XML 和设备快照语义，历史运行只读。SCXML 继续使用隐藏的状态图布局渲染器隔离 SCHVIZ；该渲染器只计算布局，不承载运行调试页面或执行状态机。

## 操作流程与实验系统状态

操作模型和整体状态声明是两个独立模型。BPMN 描述设备操作、参与集合、等待/汇合、超时和失败响应；`.system.xml` 声明整个实验系统的高层状态、合法转换和跨设备谓词。流程在经验证的操作里报告转换，运行时验证目标状态的设备证据后更新系统状态。`sys:requires` 可要求后续操作开始前系统仍处于指定状态。

设备动作准入仍由 SCXML 请求迁移、`cond` 守卫和设备忙闲互斥执行。BPMN 的 `act:actionContract` 引用设备原生就绪结果；`act:entryCondition` 只能附加更严格的当前任务条件。系统状态谓词表达跨设备实验事实，不会替代设备本地联锁。详细格式与失效语义见 [实验过程约束](process-constraints.md)。

运行变量 `experimentSystemState` 保存当前系统状态、转换历史、完整性和设备证据。Tango 事件、快照刷新、状态转换与命令下发由 `systemstate.SystemStateRuntime` 协调；已观察的违例或证据未知会锁存并停止流程。系统声明随 BPMN deployment resource 固定，服务重启后按运行所用部署恢复。

旧 `https://cdi8.cn/control/qualification/v1` BPMN 扩展已移除，不做兼容解释；模型验证会提示迁移。动作契约独立使用 `https://cdi8.cn/control/action-contract/v1`。

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

Java 设备模块将 `examples/gxlf/components/` 打包到 `models/`；服务端将同一目录打包到 `component-models/`，系统声明打包到 `system-models/`。这些副本由构建生成，不作为第二份模型源码维护。浏览器绘图资源由 `frontend/public/vendor/schviz/` 维护和分发。

各代模拟环境使用独立的 Tango DServer 实例身份，避免旧服务退出时影响新一代设备的事件通道；逻辑设备 ID 和命令协议保持稳定。

用户草稿、版本、环境、实例数据库和日志不随目录重组自动迁移或重置。

## Java 模拟环境生命周期

默认链路为浏览器 → Java Workbench / Flowable → Java HTTP bridge → JTango → Java Device Server。根 POM 同时构建 `device-simulator` 与 `server`。`make workbench` 使用 Java 源码启动器管理两个常驻服务；环境创建由 bridge 管理设备子 JVM，无需将全部服务放入一个 JVM。

`EnvironmentManager` 接受所选版本的 XML、实例 ID、反馈方式、延迟与外部条件。每代生成唯一 ID，并在 `data/java-simulator/environments/<ID>/` 固定模型 XML、请求配置、设备配置、SQLite 与日志。新设备使用独立的随机端口与 DServer 身份；所有 Tango Snapshot 可读后，bridge 才替换绑定、订阅和事件流，然后关闭旧设备进程。失败保留旧环境；失败代日志也保留供排查。

每代 Session 独占事件订阅、快照缓存和客户端队列，旧订阅的迟到事件不能进入新流。设备进程退出后环境状态为 `failed`，bridge 不自动以初始设备替代在途状态。Workbench 继续禁止活动流程期间重建；停止运行后可显式重建。bridge 重启得到新的空环境，旧配置无法直接启动流程，必须重建。正常关闭会清理设备子进程；父进程意外消失时子进程自退出。

SCXML 校验、独立试验和真实设备使用同一 `ComponentModel` / Apache 引擎，独立试验不改变联调设备。Java 设备快照携带工作台 `model_revision`，运行页按实际不可变版本绘图，历史记录保留当时的快照与模型引用。

外部设备模式仍使用 `--devices bindings.json`，只绑定已运行的 Tango 设备，禁止通过创建接口伪造模型版本或“已应用”状态。

`make java-workbench-smoke` 从隔离临时目录启动 Java Workbench、Java bridge 和 Java Tango 设备进程，验证通用模型双实例、系统状态失效阻断、四组件初始化、十四类设备两阶段初始化，以及原四组件 GXLF 双光源演示流程。十四类全生命周期尚未全部接入操作 BPMN，缺证据的系统转换明确阻断。Flowable 阶段推进依赖实际 JTango 命令、快照属性和状态事件；直接调用对象的单元测试不替代该测试。

构建与设备接口详见 [Java SCXML / Tango 模拟设备](java-device-simulator.md)。
