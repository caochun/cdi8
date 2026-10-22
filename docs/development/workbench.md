# 实验流程与组件状态机开发运行调试工作台

工作台入口为 `http://127.0.0.1:8080/`，首页直接显示工作台，不跳转。模型开发、运行准备和运行调试共享同一个 Vue 应用；原有运行页已作为原生组件并入“运行调试”，不再使用页面 iframe。携带 `?run=<运行ID>` 可直接查看指定记录。运行使用真实 Flowable 引擎、Tango 命令与属性事件，默认使用 Java bridge 和 Java JTango 多设备服务；SCXML 的执行仍由组件运行时负责，图形渲染器不参与状态迁移。

## 启动与开发

```bash
# 项目根目录；Java 17+、Maven、Node.js 22.12+，无需 Python
make workbench
make workbench-status
make workbench-stop
make workbench-restart

# 已构建时直接启动，不再次打包
make workbench-start
# 隔离验证完整 Java 运行链路
make java-workbench-smoke
```

根 Maven POM 默认聚合 `device-simulator` 和 `server`，`mvn package` 同时执行两个模块测试并打包。server 自动在 `frontend/` 执行 `npm ci`、TypeScript 校验与 Vite 构建，资源打入 JAR，浏览器不依赖 CDN。已有前端构建且只验证 Java 时，可使用 `mvn -Dexec.skip=true test`；仅开发前端可执行 `npm run dev --prefix frontend`，API 代理到 8080。

启动器 `java scripts/Workbench.java start|stop|restart|status` 管理后台进程及 `data/runtime/` 中的运行副本和日志。Java bridge 常驻，创建/重建环境时启动新的 Java 设备子 JVM；全部新设备能通过 Tango 读取快照后才切换，失败保留旧环境。新旧代设备使用独立端口及 DServer 身份；旧事件流关闭后后端自动重连，防止混入旧设备事件。停止 bridge 会停止设备子进程，bridge 意外退出时设备进程也会检测父进程并退出。

首次启动及 bridge 重启后环境为 `empty`，需在运行准备中“创建模拟设备”。设备进程退出时环境标为 `failed`，重建生成新的环境 ID。旧 Python 环境状态不自动导入；模型库、不可变版本和 Flowable 历史记录继续保留。

## 模型、草稿和版本

首次启动将 `examples/gxlf/` 中的六个 BPMN 与四个 SCXML 作为内置示例导入模型库。新建模型模板位于 `examples/minimal/`，由前端直接导入。编辑器自动保存草稿，允许保存暂时不完整的 XML；图形更新有防抖，语法错误时保留上一张有效预览。点击节点可定位 XML，XML 中选择带 ID 的元素可突出图形节点。BPMN 缺少图形坐标或新增节点尚无坐标时，使用 bpmn-auto-layout 补齐对应层级的显示布局，不更改可执行 XML。

模型开发优先显示流程或组件的业务名称，文件名保留在标题下与模型列表中，并支持按名称、文件名搜索。“工作草稿”自动保存；源码和实时预览可在同一工作区标签间切换，切换时编辑器与预览状态保持。SCXML 实时预览默认以约 14px 的可读字号聚焦初始或活跃状态；节点按完整业务名称重新布局，较长名称会自动换行并适量增加节点宽高，不压缩文字。“查看全图”用于检查整体结构，另可用滚轮或缩放按钮调整视图，双击恢复清晰阅读。历史版本的源码只读；点击“返回草稿编辑”继续修改，切换模型或版本前的草稿改动仍会保存。

BPMN 的“用于运行准备”会将当前已保存版本带入准备页，保留已填写的组件与参数；此操作不创建设备、不启动流程。当前草稿尚无对应版本时，需要先“保存版本”。SCXML 可直接使用校验通过的草稿或历史版本进行独立试验，无需发布。

“校验”不会部署或执行模型。“保存版本”必须通过执行校验；版本包含独立 XML 内容，后续编辑不修改历史版本。导出 XML 可将内容带回仓库审查，平台不会隐式覆盖仓库源文件。工作台生成的版本号独立于 SCXML 元数据中的 `model_id` / `model_version`。

SCXML 的解析和执行统一交给 Apache Commons SCXML。Java 校验会实际加载并初始化同一引擎，避免只验证元数据却无法启动设备。引擎支持层级状态、内部/外部迁移、事件前缀、守卫及赋值；组件快照协议要求单一叶子状态，因此拒绝 `parallel`。可执行组件还需提供 `componentMetadata` JSON，定义状态标签、动作请求、成功/失败事件及确认事件。SQLite 重启恢复当前仅恢复叶子状态和项目组件上下文，不保证恢复任意 SCXML 数据模型、计时器或外部副作用；需要依赖这些能力的模型仍需单独验证。可通过“新建模型”获取完整最小示例。

“创建试运行”将当前有效 XML 固定到一个独立实例。命令受理与动作成功是不同步骤，可手动反馈成功/失败，或在试验面板启用延迟成功反馈。模型编辑后，已有实例仍执行原 XML；“重新试验”创建新实例。试验不会修改联调环境中的设备。

## 运行准备

页面按“选择流程 → 准备组件 → 开始调试”组织，运行检查区显示设备是否就绪及阻塞原因；未完成准备时禁用“开始调试”。首次使用时选择入口流程，或用次要入口“使用示例方案”预填 GXLF 四组件方案。

选择入口 BPMN 的已保存版本后，工作台根据该版本的 `callActivity` 自动识别可达子流程并默认选择已保存版本。展开依赖列表可以调整每个版本；修改子流程版本后重新识别依赖。未找到或存在歧义的子流程会提示并阻止启动。已选版本保持固定，新保存版本不会自动替换它们。

组件区选择各组 SCXML 版本、组件类型、实例数量与反馈方式。类型 `demo` 的三个实例编号为 `demo_01`、`demo_02`、`demo_03`。所有实例和同步条件设备由同一个 Device Server 进程承载。

按钮含义：

- **使用示例方案**：用模型库中的 GXLF 模型预填表单，不创建设备或启动流程。
- **保存方案**：保存流程、组件和参数选择，保持设备当前状态。
- **创建模拟设备／重新创建模拟设备**：加载所选 SCXML，创建新一代环境及独立 SQLite 文件，全部实例恢复模型初始状态，同时保存方案。旧运行记录保留。
- **开始调试**：固定流程依赖版本，沿用当前设备状态启动流程，然后进入“运行调试”。

运行检查同时核对保存的环境标识、实际设备环境、设备连接、进行中的动作和流程。修改组件、实例数量、动作反馈方式或外部条件初始值后，提示需要重新创建设备；仅改流程、集合绑定或运行参数可以复用当前设备状态。保存修改后的方案不代表设备已经更新。存在运行流程时禁用重建设备和新运行；模拟服务重启或环境被替换后，也需要重新创建设备。

设备集合绑定、运行参数 JSON、外部条件初始值及同步条件设备反馈方式位于默认收起的“高级配置”。设备集合预览同时显示自动生成与显式覆盖后的结果。缺少流程使用的集合、绑定了不存在的设备或 JSON 格式无效时，提示具体问题并阻止启动。

默认绑定 `allComponents`、`contextTargets` 和每类组件的 `xxxTargets`；可用 JSON 显式覆盖集合：

```json
{"sourceTargets":["seed_01"],"demoTargets":["demo_01","demo_02"]}
```

集合仍是 BPMN 多实例的 `flowable:collection="${demoTargets}"` 输入，元素变量为 `target`。平台在运行前检查组件命令的动作和预期状态是否存在于选定模型中。

启动时把入口及其可达子流程作为一次发布部署，为 process key 生成独立后缀，并固定所有 `callActivity` 的引用。流程实例记录所选模型版本、实例和变量；后续保存或部署其他版本不替换在途流程依赖。

## 条件等待声明

条件等待直接写在 BPMN 的 `receiveTask` 中，不再依赖任务 ID 的 Java 映射：

```xml
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:lab="https://cdi8.cn/workbench" targetNamespace="example">
  <!-- process 内 -->
  <bpmn:receiveTask id="await_alignment" name="等待准直许可"
    lab:condition="alignment_ready" lab:collection="contextTargets"
    lab:maintain="true" />
</bpmn:definitions>
```

`lab:collection` 默认 `contextTargets`，集合中全部设备都明确报告条件为 true 时才放行。`lab:maintain="true"` 表示放行后持续保持该条件，失效时终止流程。不存在或无法读取的条件不会被视为满足。通过 BPMN 边界计时器配置等待超时。

配置页可定义额外布尔条件，例如 `{"alignment_ready":false}`。运行页显示条件来源、等待时间和最近超时期限，可显式注入条件；命令等待则显示关联 ID 并提供成功/失败反馈。自动完成动作不会自动确认外部条件。

## 调试记录与边界

“运行调试”显示实际部署的 BPMN、子流程进度、每台设备所用版本的 SCXML，以及命令与状态联动。运行选择、刷新、停止和下一次运行配置统一在工作台工具栏中操作。可点击节点查看对应命令，点击设备图展开并发送动作或注入确认事件。

调试页明确标识“实时运行”“历史快照”与“流程预览”。“运行现场／运行快照”将主流程、当前子流程与右侧的等待事项、反馈按钮、当前活动放在同一视图中；设备状态机和同步条件位于其下。“执行记录”单独展示关联时间线，避免长记录挤占图形区域。等待面板给出事项数量，没有待处理事项时显示明确提示。流程预览只查看已部署结构；在运行准备中创建运行后再观察设备联动。

设备事件快照按流程持久化，历史运行只读，历史图使用初始快照及最后记录的设备状态；旧记录没有快照时明确显示未记录，不替换成当前设备状态。事件时间线汇合流程活动和设备快照，并保留命令 ID 便于关联。

本版提供连续执行、手动动作反馈、条件注入、停止及历史快照查看。尚不提供 Flowable 单步执行、断点或虚拟时钟。SCXML 编辑仅保证当前执行器支持子集的可执行性；完整 W3C SCXML 不在本版支持范围内。停止流程终止后续编排，已受理设备动作可能继续。

内置四组件流程仍保留自身的业务资格检查和结束校验；通用联调入口按所选模型与集合配置运行，不要求使用固定四组件。

## 持久化与接口

- `data/workbench/models/`：模型草稿与不可变版本；`configuration.json`：运行方案；`traces/`：运行设备事件。
- `data/java-simulator/trials/`：独立设备试验的 XML、SQLite 和事件记录。
- `data/java-simulator/environments/`：各代模拟环境的模型快照和实例 SQLite。
- Flowable 执行与活动历史继续使用现有 H2 数据库。

Java 提供 `/api/workbench/models`、`/validate`、`/models/{id}/versions`、`/trials`、`/configuration`、`/environment`、`/runs`、`/runs/{id}/trace` 和设备反馈接口。Java bridge 默认提供 `/lab/*`，按页面提交的不可变 XML 版本创建设备；`--devices` 是外部设备只读绑定模式，不开放模拟环境重建。`--data-dir` 可隔离试验与环境数据，默认 `data/java-simulator/`。

## GXLF 场景规则

通用工作台按所选模型和设备集合执行；GXLF 的固定模型身份、光源选择及阶段资格集中在后端 `scenarios/gxlf/`。内置 GXLF 流程及工作台发布的对应版本自动关联这些规则；`scenarioId=gxlf` 可以显式选择同一组执行资格检查。

现有 GXLF BPMN 的 `${tango.xxx(...)}` 表达式保持兼容。通用组件命令活动由 `ComponentCommandTasks` 推进，通用流程结束处理可使用 `${componentTasks.succeeded(execution)}` 或 `${componentTasks.failed(execution)}`。自定义模型使用相同的动作名或条件名时，不自动应用 GXLF 专用约束；需要持续保持的条件应声明 `lab:maintain="true"`。

后台常驻的 Java 服务可从 `data/runtime/` 中的独立 JAR 副本启动。`server/target/` 是可重写的构建目录；使用独立运行副本可以让后续 Maven 打包与正在运行的版本互不影响。
