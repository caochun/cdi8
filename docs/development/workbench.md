# 实验流程与组件状态机开发运行调试工作台

工作台入口为 `http://127.0.0.1:8080/`，首页直接显示工作台，不跳转。模型开发、运行准备和运行调试共享同一个 Vue 应用；原有运行页已作为原生组件并入“运行调试”，不再使用页面 iframe。携带 `?run=<运行ID>` 可直接查看指定记录。运行使用真实 Flowable 引擎、Tango 命令与属性事件，设施后端内嵌 Tango 接入，并连接 Java JTango 多设备服务；SCXML 的执行仍由组件运行时负责，图形渲染器不参与状态迁移。

## 启动与开发

```bash
# 项目根目录；Java 17+、Maven、Node.js 22.12+，无需 Python
make simulation
make simulation-status
make simulation-stop
make simulation-restart

# 已构建时直接启动，不再次打包
make simulation-start
# 隔离验证完整 Java 运行链路
make simulation-test
```

根 Maven POM 聚合共享契约、`tango-adapter`、`device-simulator`、`facility-server` 和 `workbench-server`，`mvn package` 执行测试并打包。`make backend-package` 仅构建两个后端及其共享契约，不依赖设备模拟器构建。workbench-server 自动在 `workbench-ui/` 执行 `npm ci`、TypeScript 校验与 Vite 构建，资源打入 JAR，浏览器不依赖 CDN。只验证两个 Java 后端可使用 `mvn -pl facility-server,workbench-server -am -Dexec.skip=true test`；仅开发前端可执行 `npm run dev --prefix workbench-ui`，API 代理到 8080。

启动器 `java scripts/Services.java start|stop|restart|status [facility|workbench|all]` 管理设施后端（8081）、工作台（8080）及 `data/runtime/` 中的运行副本和日志。默认范围为 all，facility 范围包括设施后端及其模拟设备子进程；workbench 范围只操作工作台。`make workbench-restart` 单独重启工作台，监督持续运行。工作台通过 `FACILITY_URL` 访问设施后端，设施后端直接通过内嵌适配库访问 Tango 设备。详见[运行边界](../architecture/facility-runtime.md)。

设施侧启动器创建/重建环境时启动新的 Java 设备子 JVM；全部新设备能通过 Tango 读取快照后才切换，失败保留旧环境。新旧代设备使用独立端口及 DServer 身份；设备观测按环境代次隔离。活动流程、物理义务或未决命令存在时禁止替换环境。停止设施后端 会停止设备子进程，设施后端意外退出时设备进程也会检测父进程并退出。

首次启动及 设施后端重启后环境为 `empty`，需在运行准备中“创建模拟设备”。设备进程退出时环境标为 `failed`，重建生成新的环境 ID。模型库、不可变版本和 Flowable 历史记录继续保留。

## 模型、草稿和版本

首次启动将 `examples/gxlf/processes/`、`examples/gxlf/components/` 和 `examples/gxlf/system/` 中的设施模型与十四类 SCXML 作为内置示例导入模型库；`examples/gxlf/design/` 中的发次主干是 `isExecutable="false"` 的设计文件，不会自动部署运行。新建模型模板位于 `examples/minimal/`，由前端直接导入。编辑器自动保存草稿，允许保存暂时不完整的 XML；图形更新有防抖，语法错误时保留上一张有效预览。点击节点可定位 XML，XML 中选择带 ID 的元素可突出图形节点。BPMN 缺少图形坐标或新增节点尚无坐标时，使用 bpmn-auto-layout 补齐对应层级的显示布局，不更改可执行 XML。

模型开发优先显示流程或组件的业务名称，文件名保留在标题下与模型列表中，并支持按名称、文件名搜索。“工作草稿”自动保存；源码和实时预览可在同一工作区标签间切换，切换时编辑器与预览状态保持。SCXML 实时预览默认以约 14px 的可读字号聚焦初始或活跃状态；节点按完整业务名称重新布局，较长名称会自动换行并适量增加节点宽高，不压缩文字。“查看全图”用于检查整体结构，另可用滚轮或缩放按钮调整视图，双击恢复清晰阅读。历史版本的源码只读；点击“返回草稿编辑”继续修改，切换模型或版本前的草稿改动仍会保存。

BPMN 的“用于运行准备”会将当前已保存版本带入准备页，保留已填写的组件与参数；此操作不创建设备、不启动流程。当前草稿尚无对应版本时，需要先“保存版本”。SCXML 保存版本后，在运行准备中选择并创建模拟设备，通过运行调试验证状态迁移。

“校验”不会部署或执行模型。“保存版本”必须通过执行校验；版本包含独立 XML 内容，后续编辑不修改历史版本。导出 XML 可将内容带回仓库审查，平台不会隐式覆盖仓库源文件。工作台生成的版本号独立于 SCXML 元数据中的 `model_id` / `model_version`。

SCXML 的解析和执行统一交给 Apache Commons SCXML。Java 校验会实际加载并初始化同一引擎，避免只验证元数据却无法启动设备。引擎支持层级状态、内部/外部迁移、事件前缀、守卫及迁移内容中的赋值；组件快照协议要求单一叶子状态，因此拒绝 `parallel`。可执行组件还需提供 `componentMetadata` JSON，定义状态标签、动作的单个 `request_event`、成功/失败事件及确认事件。动作准入直接写为 SCXML 请求迁移，可用无目标迁移表示受理但保持业务状态；不再支持旧 `requests` 允许状态表。就绪查询复用引擎迁移选择且不执行迁移内容，`cond` 中的赋值、自增、对象方法调用、对象构造和 lambda 会被模型校验拒绝，标准 `In()` 和条件比较继续支持。SQLite 重启恢复当前仅恢复叶子状态和项目组件上下文，不保证恢复任意 SCXML 数据模型、计时器或外部副作用；需要依赖这些能力的模型仍需单独验证。可通过“新建模型”获取完整最小示例，已保存的旧模型需要更新后重新校验。

## 运行准备

页面按“选择流程 → 准备组件 → 开始调试”组织，运行检查区显示设备是否就绪及阻塞原因；未完成准备时禁用“开始调试”。首次使用时选择入口流程，或用次要入口“使用示例方案”预填当前可执行的 GXLF 设备方案；设计目录中的不可执行主干不会被当作运行入口。

选择入口 BPMN 的已保存版本后，工作台执行同一流程内的任务和嵌入子过程。跨 `callActivity` 的发次记录归属与失败传播尚未实现，可执行模型校验会明确拒绝；设计模型仍可编辑和查看。

组件区选择各组 SCXML 版本、组件类型、实例数量与反馈方式。类型 `preamp` 的三个实例编号为 `preamp_01`、`preamp_02`、`preamp_03`；类型必须能在设施绑定中解析。所有实例和同步条件设备由同一个 Device Server 进程承载。

按钮含义：

- **使用示例方案**：用模型库中的 GXLF 模型预填表单，不创建设备或启动流程。
- **保存方案**：保存流程、组件和参数选择，保持设备当前状态。
- **创建模拟设备／重新创建模拟设备**：加载所选 SCXML，创建新一代环境及独立 SQLite 文件，全部实例恢复模型初始状态，同时保存方案。旧运行记录保留。
- **开始调试**：固定流程依赖版本，沿用当前设备状态启动流程，然后进入“运行调试”。

运行检查同时核对保存的环境标识、实际设备环境、设备连接、进行中的动作和流程。修改组件、实例数量、动作反馈方式或外部条件初始值后，提示需要重新创建设备；仅改流程、集合绑定或运行参数可以复用当前设备状态。保存修改后的方案不代表设备已经更新。存在运行流程时禁用重建设备和新运行；模拟服务重启或环境被替换后，也需要重新创建设备。

设备集合绑定、运行参数 JSON、外部条件初始值及同步条件设备反馈方式位于默认收起的“高级配置”。设备集合预览同时显示自动生成与显式覆盖后的结果。缺少流程使用的集合、绑定了不存在的设备或 JSON 格式无效时，提示具体问题并阻止启动。

默认绑定 `allComponents`、`contextTargets` 和每类组件的 `xxxTargets`；可用 JSON 显式覆盖集合：

```json
{"sourceTargets":["seed_01"],"preampTargets":["preamp_01","preamp_02"]}
```

`fac:actions/action` 的 collectionVar 引用设备集合，运行时展开为逐设备命令。能力在设施定义中解析为动作；目标状态在收到结果时验证。`allComponents` 由实际设备目录生成，不能覆盖。

启动时将入口作为一次发布部署，为 process key 生成独立后缀。流程实例记录所选模型版本、实例和变量；后续保存或部署其他版本不替换在途流程依赖。

## 设施放行条件

放行条件通过状态投影中的 guard 声明，在 BPMN serviceTask 的 extensionElements 中引用：

```xml
<bpmn:serviceTask id="gate" name="核验触发条件" flowable:expression="${shotRuns.stage(execution, 'triggering')}">
  <bpmn:extensionElements>
    <fac:requires guardRef="triggerAllowed" />
  </bpmn:extensionElements>
</bpmn:serviceTask>
```

根元素需声明 `xmlns:fac="https://cdi8.cn/facility/v1"`。guard 在进入任务时必须为 valid，否则结束本发并记录原因；unknown 不会自动放行。模拟条件设备上的布尔值不会自动成为外部安全事实。

需要等待条件成立时，在 `${facilityActions}` 的 serviceTask 上声明 `fac:completion factRef="allPowerChecked" timeout="PT2M"`，也可用 `guardRef="…"` 引用组合条件。任务先验收全部本发动作，再进入有截止时间的条件等待；没有动作的任务直接等待。unknown 和 violated 均保持等待，只有 valid 推进。条件初始就成立也会被主动检查，事件重发不重发设备命令。运行调试页会显示原因、期限及结束时等待快照。

当前完成条件事实仍以投影声明的设施实例范围计算。系统定义中声明的能力输入依赖，则按本发束线、选源和实际设备绑定检查，可持续到单次命令验收结束。`variables.sourceSelections` 按束线指定光源，例如 `{"1":"shg"}`；其他所选束线使用 `defaultSource`。模型预览显示输入依赖、输出条件和逐设备事实，运行节点详情显示对应输入及失效原因。详见[设施能力的输入依赖](../architecture/capability-dependencies.md)。系统工作状态义务由监督后端持续检查；真实联锁输入与现场异常处置仍需接入。完整模型与运行说明见[活动完成条件](../architecture/activity-completion.md)。

## 调试记录与边界

“运行调试”显示实际部署的 BPMN、子流程进度、每台设备所用版本的 SCXML，以及命令与状态联动。运行选择、刷新、停止和下一次运行配置统一在工作台工具栏中操作。可点击节点查看对应命令，点击设备图展开并发送动作或注入确认事件。

调试页明确标识“实时运行”“历史快照”与“流程预览”。“运行现场／运行快照”将主流程、当前子流程与右侧的等待事项、反馈按钮、当前活动放在同一视图中；设备状态机和同步条件位于其下。“执行记录”单独展示关联时间线，避免长记录挤占图形区域。等待面板给出事项数量，没有待处理事项时显示明确提示。流程预览只查看已部署结构；在运行准备中创建运行后再观察设备联动。

设备事件快照按流程持久化，历史运行只读，历史图使用初始快照及最后记录的设备状态；旧记录没有快照时明确显示未记录，不替换成当前设备状态。事件时间线汇合流程活动和设备快照，并保留命令 ID 便于关联。

本版提供连续执行、手动动作反馈、条件注入、停止及历史快照查看。尚不提供 Flowable 单步执行、断点或虚拟时钟。SCXML 编辑仅保证当前执行器支持子集的可执行性；完整 W3C SCXML 不在本版支持范围内。停止流程终止后续编排，已受理设备动作可能继续。

内置 GXLF 设计主干通过 BPMN 操作、设施状态投影和发次证据协作；它当前仍是不可执行设计。通用联调入口按所选模型与集合配置运行，支持十四类示例组件与自定义设备模型。

## 持久化与接口

- `data/workbench/models/`：模型草稿与不可变版本；`configuration.json`：运行方案；`traces/`：运行设备事件。
- `data/java-simulator/environments/`：各代模拟环境的模型快照和实例 SQLite。
- Flowable 执行与活动历史继续使用现有 H2 数据库。
- `data/supervision/`：监督端模型、物理工作状态义务、锁存事件和命令日志，独立于工作台；可用 `FACILITY_STORE` 修改。

Java 提供 `/api/workbench/models`、`/validate`、`/models/{id}/versions`、`/configuration`、`/environment`、`/runs`、`/runs/{id}/trace` 和设备反馈接口。工作台经设施接口提交不可变 XML 创建模拟设备；设施后端的 `--control.tango-bindings=bindings.json` 是外部设备绑定模式，不开放模拟环境重建。`SIMULATOR_DIRECTORY` 可隔离模拟环境数据，默认 `data/java-simulator/`。

## 设施动作运行

工作台按设施定义解析能力和实例，向监督端请求放行证据；监督端独立验证设备观测、投影事实并执行命令准入。监督失联时 guard、completion 和依赖检查不能放行。旧 GXLF 场景策略和综合系统状态运行入口已删除。BPMN 用 `shotRuns.open` 创建发次记录，用 `shotRuns.complete` 完成；设施动作自动保存逐设备结果。完整业务主干仍为不可执行设计，初始化和参数接收切片已接入 Java Tango 链路。

后台常驻的 Java 服务可从 `data/runtime/` 中的独立 JAR 副本启动。`workbench-server/target/` 是可重写的构建目录；使用独立运行副本可以让后续 Maven 打包与正在运行的版本互不影响。

## 实验运行与验证

“使用示例方案”选择 `gxlf_shot_run_smoke`，创建十四类组件后，依次执行上电检查、功能检查、参数接收。高级参数可配置 shotId、recipeRef、recipeVersion、beamlines、defaultSource 以及两个超时；正式发射不在此切片范围内。运行页显示设施事实、guard、发次阶段和逐设备证据；结束后查看的是当时设施快照。

可执行动作声明为 `flowable:delegateExpression="${facilityActions}"`，在 extensionElements 的 `fac:actions` 集合内写 `fac:action typeRef="seed" capabilityRef="power-on-check" collectionVar="seedTargets" expectedStateRef="powerOnChecked"`。记录表达式放在单独任务；旧组件命令和综合系统状态扩展已删除。

执行 `make simulation-test` 或 `mvn -Pworkbench-e2e verify` 可启动隔离的 Java 设备、内嵌 Tango 接入的设施后端和工作台测试。它使用随机端口、临时模型与监督日志目录和 H2 内存库，不操作日常 8080/8081 服务。

## 设施模型格式

系统定义、绑定和投影采用统一 XML 约定，内置 XSD 位于 `facility-contract/src/main/resources/schemas/`。单文件校验和模型组激活分别负责文档内及跨文档语义校验，详见[设施 XML 模型约定](../architecture/facility-xml-models.md)。未被用户修改的内置示例按既有规则在启动时更新；用户修改过的旧格式草稿和发布归档不会自动改写，需按新版格式编辑/导入后发布。旧格式在途运行无法由新解析器恢复。
