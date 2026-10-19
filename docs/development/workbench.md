# 流程与状态机开发联调工作台

工作台入口为 `http://127.0.0.1:8080/`。模型开发、联调配置和运行调试共享同一个项目。运行使用真实 Flowable 引擎、PyTango 命令与属性事件；SCXML 的执行仍由组件运行时负责，图形渲染器不参与状态迁移。

## 启动与开发

```bash
# 终端一，项目根目录：已有 PyTango 环境可直接使用 .venv/bin/python
PYTHONPATH=simulator .venv/bin/python -m gxlf_component_simulator.tango.control_bridge \
  --demo --auto-complete --completion-delay-ms 500

# 终端二；Node.js 22.12+、Java 17+、Maven
mvn test package
java -jar target/control-server-0.1.0.jar
```

Maven 自动在 `frontend/` 执行 `npm ci`、TypeScript 校验与 Vite 构建，生成文件放在 `target/workbench-static/` 并打进 JAR。浏览器不依赖 CDN。仅调整前端时可执行 `npm run dev --prefix frontend`，Vite 将 API 请求代理到 8080。已有前端构建且只验证 Java 时，可使用 `mvn -Dexec.skip=true test`。

## 模型、草稿和版本

首次启动将仓库中的六个 BPMN 与四个 SCXML 作为内置示例导入模型库。编辑器自动保存草稿，允许保存暂时不完整的 XML；图形更新有防抖，语法错误时保留上一张有效预览。点击节点可定位 XML，XML 中选择带 ID 的元素可突出图形节点。BPMN 缺少图形坐标或新增节点尚无坐标时，使用 bpmn-auto-layout 补齐对应层级的显示布局，不更改可执行 XML。

“校验”不会部署或执行模型。“保存版本”必须通过执行校验；版本包含独立 XML 内容，后续编辑不修改历史版本。导出 XML 可将内容带回仓库审查，平台不会隐式覆盖仓库源文件。工作台生成的版本号独立于 SCXML 元数据中的 `model_id` / `model_version`。

SCXML 目前支持顺序层级状态、复合状态初始子状态、单目标精确事件迁移及内部/外部迁移。执行校验会拒绝 `parallel`、守卫 `cond`、可执行内容、多目标、通配事件等当前执行器不支持的内容。可执行组件还需提供 `componentMetadata` JSON，定义状态标签、动作请求、成功/失败事件及确认事件。可通过“新建模型”获取完整最小示例。

“创建试运行”将当前有效 XML 固定到一个独立实例。命令受理与动作成功是不同步骤，可手动反馈成功/失败，或在试验面板启用延迟成功反馈。模型编辑后，已有实例仍执行原 XML；“重新试验”创建新实例。试验不会修改联调环境中的设备。

## 联调配置

配置固定入口 BPMN、被调用子流程的版本、各组 SCXML 版本、组件类型、实例数量与反馈方式。类型 `demo` 的三个实例编号为 `demo_01`、`demo_02`、`demo_03`。所有实例和同步条件设备由同一个 Device Server 进程承载。

“应用模拟环境”创建新一代环境及独立 SQLite 文件，旧状态不迁移到新模型。存在运行流程时拒绝切换环境。设备配置修改后必须重新应用；仅修改流程或流程变量可复用当前设备状态。模拟服务重启后需要重新应用已保存配置。

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

运行页显示实际部署的 BPMN、每台设备所用版本的 SCXML，以及命令与状态联动。可点击节点查看对应命令，点击设备图展开并发送动作或注入确认事件。

设备事件快照按流程持久化，历史图使用初始快照及最后记录的设备状态；旧记录没有快照时明确显示未记录，不替换成当前设备状态。事件时间线汇合流程活动和设备快照，并保留命令 ID 便于关联。

本版提供连续执行、手动动作反馈、条件注入、停止及历史快照查看。尚不提供 Flowable 单步执行、断点或虚拟时钟。SCXML 编辑仅保证当前执行器支持子集的可执行性；完整 W3C SCXML 不在本版支持范围内。停止流程终止后续编排，已受理设备动作可能继续。

内置四组件流程仍保留自身的业务资格检查和结束校验；通用联调入口按所选模型与集合配置运行，不要求使用固定四组件。

## 持久化与接口

- `data/workbench/models/`：模型草稿与不可变版本；`configuration.json`：联调配置；`traces/`：运行设备事件。
- `simulator/gxlf_component_simulator/data/workbench-trials/`：独立设备试验的 XML、SQLite 和事件记录。
- `simulator/gxlf_component_simulator/data/workbench-environments/`：各代模拟环境的模型快照和实例 SQLite。
- Flowable 执行与活动历史继续使用现有 H2 数据库。

Java 提供 `/api/workbench/models`、`/validate`、`/models/{id}/versions`、`/trials`、`/configuration`、`/environment`、`/runs`、`/runs/{id}/trace` 和设备反馈接口。模拟服务在 `--demo` 模式提供对应 `/lab/*` 接口；真实 `--devices` 模式不开放模拟环境重建。
