# 工作台模块与资源边界

项目定位是实验流程模型及流程涉及的组件状态机的开发、运行和调试工作台，统一支持模型编辑、版本管理、模拟环境配置和流程联调。GXLF 是内置示例，不定义平台可使用的全部设备类型或流程。

## 当前模块

- `frontend/`：所有浏览器代码和第三方绘图资源。Vue 构建产物进入根目录 `target/workbench-static/`；vendor 与状态图布局资源位于 `frontend/public/`，由 server 一并打包到静态资源根路径。`/` 直接提供工作台 HTML，无客户端或 HTTP 跳转。
- `server/`：Java Maven 模块，产物为 `server/target/workbench-server-0.1.0.jar`。根 POM 聚合模块并统一 Java / Flowable 版本；先执行 `mvn -pl model-contract -am install` 安装共享模型契约，再使用 `mvn -pl server spring-boot:run` 从仓库根目录启动进程，继续使用原有 `data/`。
- `model-contract/`：服务端与设备共用的 SCXML 元数据及静态引用校验。
- `device-simulator/`：默认 Java bridge、模拟环境管理、独立试验及 Apache SCXML / JTango 多设备进程。
- `examples/`：示例模型唯一的版本化源文件。`minimal/` 直接供前端新建模型使用；`gxlf/design/` 保存不可执行的发次主干设计，`gxlf/components/` 和 `gxlf/system/` 分别提供十四类设备模型与 GXLF 设施模型。
- `contracts/`：跨运行时的命令、结果与事件格式。
- `server/src/test/java/cn/cdi8/workbench/NewChainIT.java` 从 HTTP API 执行 Flowable、真实 JTango 和 Java SCXML 联调；`make java-workbench-smoke` 构建并执行隔离测试。

后端目前按职责分包：

| 包 | 职责 |
|---|---|
| `api` | REST 及页面入口 |
| `model` | 模型存储、校验、草稿编辑和版本发布 |
| `application` | 对 API 保持稳定的工作台门面 |
| `environment` | 运行方案存取、环境替换及空闲约束 |
| `run` | 运行部署、发次记录、设备命令推进、超时和终态 |
| `facility` | 系统定义/绑定/状态投影解析、设施 guard 和动作队列 |
| `integration.flowable` | 自定义组件命令活动和 BPMN 解析适配 |
| `integration.tango` | 设备服务 HTTP 客户端及事件连接 |
| `trace` | 按运行保存设备事件 |

`WorkbenchService` 保持 API 门面，委托 `ModelService`、`EnvironmentService` 和 `RunDeploymentService`。环境替换与发布共享环境锁，保留运行时禁止切换环境的约束。当前分包未增加新的部署服务。

## 前端开发与运行视图

工作台以同一个 Vue 应用管理模型开发、运行准备和运行调试。`App.vue` 负责工作区、运行选择、停止和启动配置；`components/runtime/RuntimeView.vue` 原生呈现运行概览、流程与子流程图、设备状态机、等待诊断、反馈和关联事件，不再通过 iframe 嵌入独立运行页面。

`components/preparation/RunPreparation.vue` 管理运行方案表单，按入口版本的 XML 解析可达子流程依赖并固定版本选择；设备集合、JSON 解析和配置检查位于 `configuration.ts`。运行检查将表单与已应用的设备配置、实际环境和设备连接作比较；保存方案、重建设备和开始调试使用各自独立的操作。环境与运行操作仍由后端执行最终校验。

运行视图的 controller 在组件内部创建 BPMN viewer，限定 DOM 查询与样式作用域；离开运行调试时取消请求、轮询、布局任务及图形实例。切换记录时保留所选运行的部署 XML 和设备快照语义，历史运行只读。SCXML 继续使用隐藏的状态图布局渲染器隔离 SCHVIZ；该渲染器只计算布局，不承载运行调试页面或执行状态机。

## 操作流程与实验设施状态

当前模型分成四个职责面：`system-definition` 描述设施结构、能力、输入依赖、输出成立条件和结构约束，`facility-binding` 描述逻辑实例到运行设备的绑定，`facility-state-projection` 从观测计算实时事实和 guard，BPMN 与 `shot-run` 描述一次发次的操作和证据。设备 SCXML 仍负责单设备状态、动作准入和本地故障。详细边界见 [操作模型与系统模型](operational-and-declarative-models.md) 和 [实验过程约束](process-constraints.md)。

可执行 BPMN 使用 `fac:requires`、`fac:action` 和 `run:stage`。guard 在 serviceTask 进入时检查，包括普通表达式任务；动作按绑定集合串行执行，成功必须同时满足命令终态和目标状态。动作任务统一声明 `flowable:delegateExpression="${facilityActions}"`，不能同时使用记录表达式。每设备结果自动写入 shot-run；`run:records` 仍仅用于不可执行设计。

活动还可声明 `fac:completion`，在命令队列全部成功后等待设施事实或 guard。`FacilityCompletionRuntime` 管理持久化等待，投影变化通知和定时补查由 `WorkflowService` 协调，满足条件后触发 Flowable；超时/取消保留等待诊断。详见[活动完成条件](activity-completion.md)。

`FacilityDependencyRuntime` 从系统定义展开当前能力的输入依赖，按束线映射、预放分组与本发选源解析上游实例。投影提供逐设备事实；事件触发失效锁存，协调器在下发前、执行期间与验收时核验，失败时保留依赖证据与未决设备动作。模型预览展示依赖声明，节点详情展示当前检查结果。详见[设施能力的输入依赖](capability-dependencies.md)。

`examples/gxlf/processes/gxlf_shot_run_smoke.bpmn20.xml` 是默认初始化和参数接收切片；完整主干仍在 design/ 中设计。设施模型在部署前读取已保存的当前版本，运行时与部署归档使用同一 XML；后端恢复在途运行时从该部署恢复设施模型。

旧综合系统状态运行时、旧动作契约、GXLF 场景策略与旧 `POST /api/runs` 启动入口已删除。旧扩展校验失败，不再保留兼容执行。前端读取 `facilityState` 和 `shotRun`，历史运行显示结束时的设施快照。

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

GXLF 模型在 Java classpath 中继续使用 `processes/`、`component-models/` 路径，由 ModelStore 导入模型库；关闭 Flowable 启动时的自动部署，只有选择模型并启动发次时才部署。该 classpath 布局是构建产物，不是模型的源码存放位置。

Java 设备模块将 `examples/gxlf/components/` 打包到 `models/`；服务端将同一目录打包到 `component-models/`，系统声明打包到 `system-models/`。这些副本由构建生成，不作为第二份模型源码维护。浏览器绘图资源由 `frontend/public/vendor/schviz/` 维护和分发。

各代模拟环境使用独立的 Tango DServer 实例身份，避免旧服务退出时影响新一代设备的事件通道；逻辑设备 ID 和命令协议保持稳定。

用户草稿、版本、环境、实例数据库和日志不随目录重组自动迁移或重置。

## Java 模拟环境生命周期

默认链路为浏览器 → Java Workbench / Flowable → Java HTTP bridge → JTango → Java Device Server。根 POM 同时构建 `device-simulator` 与 `server`。`make workbench` 使用 Java 源码启动器管理两个常驻服务；环境创建由 bridge 管理设备子 JVM，无需将全部服务放入一个 JVM。

`EnvironmentManager` 接受所选版本的 XML、实例 ID、反馈方式、延迟与外部条件。每代生成唯一 ID，并在 `data/java-simulator/environments/<ID>/` 固定模型 XML、请求配置、设备配置、SQLite 与日志。新设备使用独立的随机端口与 DServer 身份；所有 Tango Snapshot 可读后，bridge 才替换绑定、订阅和事件流，然后关闭旧设备进程。失败保留旧环境；失败代日志也保留供排查。

每代 Session 独占事件订阅、快照缓存和客户端队列，旧订阅的迟到事件不能进入新流。设备进程退出后环境状态为 `failed`，bridge 不自动以初始设备替代在途状态。Workbench 继续禁止活动流程期间重建；停止运行后可显式重建。bridge 重启得到新的空环境，旧配置无法直接启动流程，必须重建。正常关闭会清理设备子进程；父进程意外消失时子进程自退出。

SCXML 校验、独立试验和真实设备使用同一 `ComponentModel` / Apache 引擎，独立试验不改变联调设备。Java 设备快照携带工作台 `model_revision`，运行页按实际不可变版本绘图，历史记录保留当时的快照与模型引用。

外部设备模式仍使用 `--devices bindings.json`，只绑定已运行的 Tango 设备，禁止通过创建接口伪造模型版本或“已应用”状态。

`make java-workbench-smoke` 执行隔离端到端验证。测试包含十四类设备、两个预放实例（合计 43 个动作）、正常终态、失败证据、目标状态不符、取消/超时/断连的未决动作、guard 拦截和循环重入。正式发射及外部预约、服务心跳和安全接口尚未接入，缺少观测的安全 guard 保持 unknown。

构建与设备接口详见 [Java SCXML / Tango 模拟设备](java-device-simulator.md)。

设施模型的集合层次、引用命名、统一映射、条件组合及 XSD/语义校验见[设施 XML 模型约定](facility-xml-models.md)。安全和时序设计说明不等于运行时已执行规则。
