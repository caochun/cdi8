# BPMN 扩展、SCXML 元数据与跨模型校验

系统定义、绑定和投影使用[设施 XML 约定](facility-xml-models.md)。BPMN 和 SCXML 的标准元素继续使用原来的标准结构；本项目只规范自定义扩展，不给 `state`、`transition`、`sequenceFlow` 添加集合包装。不兼容旧的自定义扩展格式。

## BPMN 自定义扩展

```xml
<bpmn:serviceTask id="function_check" name="种子源功能检查"
                  flowable:delegateExpression="${facilityActions}">
  <bpmn:extensionElements>
    <run:stage value="initializing" />
    <fac:requires guardRef="simulationFunctionCheckAllowed" />
    <fac:actions>
      <fac:action typeRef="seed" capabilityRef="function-check"
                  collectionVar="seedTargets" expectedStateRef="qualified" />
    </fac:actions>
    <fac:completion factRef="allFunctionReady" timeout="PT2M" />
  </bpmn:extensionElements>
</bpmn:serviceTask>
```

`fac` 为 `https://cdi8.cn/facility/v1`，`run` 为 `https://cdi8.cn/shot-run/v1`。上例使用 GXLF 模型中的名称；`allFunctionReady` 检查全部参与组件，实际流程应给相应组件都安排功能检查动作。

| 元素 | 属性 | 含义与基数 |
|---|---|---|
| `fac:actions` | 无 | 每个任务最多一个；出现时至少包含一个 `fac:action`，顺序展开设备命令 |
| `fac:action` | `typeRef`、`capabilityRef`、`collectionVar`、`expectedStateRef` | 引用组件类型、该类型的能力、运行变量中的设备集合、该组件的目标叶子状态；可执行模型四项必填 |
| `fac:requires` | `guardRef` | 每个任务最多一个进入条件；多个条件在投影 guard 中组合 |
| `fac:completion` | `factRef` 或 `guardRef`，另有 `timeout` | 每个任务最多一个完成条件；引用二选一，时长为至少 1 毫秒的正 ISO-8601 duration |
| `run:stage` | `value` | 每个任务最多一个阶段标记；值必须属于已有阶段枚举 |
| `fac:capability` | `ownerRef`、`capabilityRef` | 设计标注，owner 为组件类型或控制节点；可执行流程拒绝 |
| `run:records` | `kind`，可选 `factRef`、`operation` | 证据设计标注，`operation` 为说明性业务操作名；可执行流程拒绝 |

这些扩展只允许位于 `serviceTask/extensionElements`；`fac:action` 必须位于 `fac:actions`。引用属性必须是单个标识。`collectionVar` 是运行变量名，不是假定存在于模型中的 ID。直接在任务下放多个 `fac:action`、重复条件、拼错字段、未知属性、未知子元素及非空文本都报错。动作和完成条件任务必须使用 `${facilityActions}`。普通表达式任务可以使用进入条件及阶段标记。

`FacilityBpmnGrammar` 先检查原始 XML，避免转换器忽略错误位置或未知扩展；`FacilityBpmnModel` 再检查 Flowable 模型中的扩展及引用。可执行模型不能靠设计标注获得执行语义。

## SCXML 的 componentMetadata

唯一的 `componentMetadata` 放在根 `scxml/datamodel/data`，以 JSON 文本或 CDATA 表达，不能放进子状态，不能通过 `src` 或 `expr` 引入。重复 JSON 字段、尾随 JSON 内容和重复 XML ID 都拒绝。

共享的 `model-contract/ComponentContract` 负责结构与静态引用，服务端和 Java 模拟器使用同一实现。模拟器还会加载 Apache Commons SCXML 引擎，校验实际可执行性。当前快照协议只支持一个活动叶子状态，不支持 `parallel`。

| 字段 | 校验 |
|---|---|
| `model_id`、`display_name` | 非空字符串 |
| `model_version` | 正整数，开发阶段的模型元数据；运行时没有版本选择分支 |
| `execution_model` | 可选，只接受 `hierarchical-single-active-state` |
| `outer_labels`、`inner_labels` | 对象；键分别引用外层状态、叶子状态，值为非空标签 |
| `context_defaults` | 组件上下文对象，允许业务自定义键 |
| `configuration_valid_states` | 可选，无重复的叶子状态数组 |
| `actions` | 非空动作对象；动作名、字段和各字段类型均校验 |
| `confirmations`、`fault_codes` | 可选；检查确认事件字段、来源状态及故障码关联 |
| `annotations` | 可选说明性对象；不参与执行 |

每个动作声明 `label`、`request_event`、`success_events`、`failure_events`、`success_key`、`failure_key`。两个反馈表以实际叶子状态为键，值是具体事件名或显式 `null`；`null` 表示此次反馈不发送状态迁移事件。可选的 `success_observation`、`failure_observation` 是字符串、null 或以叶子状态为键的对应表。未知动作字段一律拒绝。

请求和反馈事件必须在 SCXML 迁移中有匹配声明，支持祖先迁移和事件描述符匹配。该检查不推断运行时守卫和可达性：请求可能先改变状态，随后才收到反馈；实际能否执行、如何选迁移仍由 SCXML 引擎决定，不另建动作允许状态表。

确认事件声明 `label`、`source_outer`、`machine_event`、`observation`；可选 `source_states` 提供更具体的来源状态描述，并校验其属于声明的外层状态、存在匹配迁移。运行时仍按外层状态、观测值以及引擎对迁移的启用情况接收确认。`fault_codes` 的键必须引用已声明确认事件。

`context_policy`、`source_inventory` 和 `modeling_notes` 已移动到 `annotations` 中，明确为解释材料。新增执行行为必须实现并登记到严格契约，不能把说明文字当作运行规则。

## 发布、部署与恢复

1. SCXML 发布和独立试验经过相同的 Java 元数据校验及引擎加载。
2. 系统定义的 `stateModelHref="components/<模型编号>.scxml"` 解析到工作台模型库当前已发布内容；组件能力的 `actionRef` 必须在该 SCXML 的 `actions` 中存在。绑定和投影引用当前已发布设施模型。
3. 投影 `states` 必须引用实际叶子状态，`outerStates` 必须引用实际外层状态；`device-state` 观测的 expected 检查对应组件的叶子或外层状态。BPMN 的 `expectedStateRef` 只接受一个实际叶子状态。
4. 启动时重新检查当前已应用环境的设备集合、组件、发布快照标识和 XML 摘要。环境描述的 `modelDigest` 与 Tango 快照的 `model_digest` 必须匹配选中 XML。选中模型与定义所引用模型的 `model_id` 必须一致；同一组件类型在当前环境中使用同一份模型内容。
5. 使用实际选中的设备 SCXML 再验证能力、投影和 BPMN。模型组、流程、实例绑定及运行参数检查完成后，Flowable 使用候选模型完成部署，再激活设施模型并启动流程。失败的模型检查或部署不切换当前设施模型，不发送设备命令。
6. 设施三模型及各类型的实际 SCXML 随流程部署归档。恢复使用这组归档，避免后来编辑模型影响在途运行。归档是本次执行内容的证据，不引入运行时版本分支。

单个模型校验不能证明完整实验过程安全：外部观测、全局安全条件及现场处置边界仍以 `todo.md` 为准。本次校验解决模型声明不一致和扩展被静默忽略的问题。

## 验证

`ComponentModelTest` 覆盖全部十四类设备、非法元数据、状态/事件引用、祖先事件匹配、null 反馈和请求先迁移的情况。`FacilityContractTest` 覆盖原始 XML 和 Flowable 扩展、能力/投影引用及实际选中模型不兼容。`NewChainIT` 通过真实 HTTP、Flowable、JTango 验证不兼容设备模型在发出任何命令前被拒绝。

```bash
mvn -Dexec.skip=true test
mvn -Pnew-chain-e2e verify
```
