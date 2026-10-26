# 设施 XML 模型约定与校验

三个模型使用统一的集合、引用和条件结构：系统定义声明组成与能力契约；设施绑定将这些逻辑对象解析到运行环境；设施状态投影从当前观测计算事实。此次格式调整直接替换旧结构，不提供旧格式解析。命名空间中的 `/v1` 保持不变；`version` 只是模型文件的说明元数据，运行时采用当前激活的一组模型。

## 集合与命名

集合使用复数容器，集合项使用单数名称；单一语义节点不额外包装。主要路径如下：

| 模型 | 路径 |
|---|---|
| 系统定义 | `componentTypes/componentType/ports/port/outputs/output` |
| 系统定义 | `componentTypes/componentType/capabilities/capability/requirements/inputRequirement` |
| 系统定义 | `port/outputs/output/requirements/inputRequirement` |
| 系统定义 | `controlNodes/controlNode/ports/port`、`capabilities/capability` |
| 系统定义 | `externalEndpoints/externalEndpoint/ports/port` |
| 系统定义 | `instanceSets/instanceSet`、`instanceGroups/instanceGroup/members/member` |
| 系统定义 | `signals/signal`、`mappings/mapping/members/member` |
| 系统定义 | `topology/paths/path/endpoints/endpoint`、`connections/connection` |
| 设施绑定 | `instanceBindings/instanceBinding`、`externalBindings/externalBinding` |
| 设施绑定 | `beamlineBindings/beamlineBinding/mappingBindings/mappingBinding` |
| 状态投影 | `observations/observation`、`deviceFacts/deviceFact`、`facts/fact`、`guards/guard` |
| 三种模型 | `annotations/notes/note`、`annotations/issues/issue` |

`id` 是程序标识，`label` 是展示名称，`description` 是文字内容。ID 在所属集合内唯一，端口和能力在所属对象内唯一。不同组件可都有 `command-in` 和 `power-on-check`。

对象引用使用 `typeRef`、`instanceSetRef`、`signalRef`、`factRef`、`pathRef`、`inputPortRef` 等属性；文件引用使用 `stateModelHref`。源码示例的 `components/…scxml` 相对于 `examples/gxlf/`；导入工作台后以文件名对应的模型编号解析当前已发布 SCXML。XML 不触发文件下载或远程 schema 加载。

端口方向只有 `in / out / inout`。接口的 `category` 表示 `physical / software`，`interaction` 表示 `flow / event / request-response`，`protocol` 可补充实际的 `TANGO / HTTP` 等协议名称。接口分类和交互方式不再冒充协议；未确定协议可以省略，并用 `status` 说明。这些接口描述不会自动创建传输适配器。

## 数量、分组和选源只有一处定义

类型不声明实例数量；`instanceSet` 持有数量与逻辑实例命名规则，`resource` 持有资源数量。

```xml
<instanceSets>
  <instanceSet id="preamp" typeRef="preamp" quantity="10" pattern="preamp-{index}" />
</instanceSets>
<mappings>
  <mapping id="preamp-to-beamline" kind="grouped" resourceRef="beamline" groupSize="6">
    <members><member instanceSetRef="preamp" /></members>
  </mapping>
</mappings>
```

`{index}` 从 1 开始，至少两位十进制数字，生成 `preamp-01` 等逻辑编号。数量必须为 1–10000。`grouped` 要求实例数量乘以 `groupSize` 等于资源数量，资源索引 `i` 对应实例索引 `(i - 1) / groupSize + 1`；`one-to-one` 要求实例数量等于资源数量。

`selection` 的成员必须是单实例集合，可用 `first`、`last` 限定资源范围，省略时覆盖全部资源。必须有且只有一个覆盖全范围的 `default="true"` 成员。GXLF 的种子源是默认光源，二倍频只声明 `last="8"`；连接和绑定不再重复这段范围。

绑定只引用这些定义：

```xml
<instanceBindings resolution="runtime-configuration">
  <instanceBinding instanceSetRef="preamp" runtimeKey="preamp" runtimePattern="preamp_{index}" />
</instanceBindings>
<beamlineBindings resolution="runtime-configuration">
  <beamlineBinding id="beamlines" resourceRef="beamline">
    <mappingBindings>
      <mappingBinding mappingRef="preamp-to-beamline" />
      <mappingBinding mappingRef="beamline-components" />
      <mappingBinding mappingRef="source-selection" />
    </mappingBindings>
  </beamlineBinding>
</beamlineBindings>
```

这是结构片段；完整绑定必须覆盖系统定义的全部实例集合和所选束线资源的全部映射。运行设备目录可只包含原型环境中实际部署的部分实例；动作需要的具体实例缺失时仍阻断。`runtimeKey` 对应目录报告的组件键，再经 `instanceSetRef → typeRef` 解析类型，不根据设备名猜测类型。运行配置的 `facilityInstances` 可提供具体实例到实际设备编号的覆盖映射。

当前运行时仅支持一个束线资源集合；参与能力依赖的类型须唯一对应实例集合，每束每项输入须唯一选路。超出这些能力的模型会明确拒绝，不作隐式推断。

## 结构化端点与信号

连接通过独立的 `source`、`target` 声明对象及端口：

```xml
<connection id="preamp-to-multipass" relation="optical-feed" mappingRef="preamp-to-beamline">
  <source instanceSetRef="preamp" portRef="optical-out" />
  <target instanceSetRef="multipass" portRef="optical-in" />
</connection>
```

对象引用必须恰好选择一种：`instanceSetRef`、`instanceGroupRef`、`controlNodeRef`、`externalEndpointRef`。实例组的每个成员都必须声明所引用端口。`all-components` 是明确列出成员的实例组；`data-producers` 只包含拥有数据输出端口的组件。外部服务的 `ingest`、联锁控制器的 `stop-out` 等端口也必须先声明。

`signals/signal` 是可引用信号目录。输出 `signalRef` 必须匹配端口接口；`factRef` 引用投影的逐设备事实，并在模型组激活时验证类型一致。输入依赖使用 `requirements/inputRequirement`。能力依赖必须声明 `maintain="start|execution"`，输出进一步依赖不允许声明 `maintain`，而是继承调用者的保持范围。具体运行语义见[能力输入依赖](capability-dependencies.md)。

## 条件和三值求值

`fact` 和 `guard` 都必须包含一个 `condition`。单一判据可直接写入，多项判据用 `all` 或 `any`，可嵌套。

```xml
<fact id="preparationInputReady" label="准备输入有效">
  <condition>
    <all>
      <devicePredicate instanceSetRef="preamp" outerStates="wakeup" />
      <any>
        <factPredicate factRef="seedReady" />
        <factPredicate factRef="shgReady" />
      </any>
    </all>
  </condition>
</fact>
```

此例中的引用事实需另行声明；选中光源的实际能力依赖仍按本发选路解析，不能用“任一光源正常”代替所选光源正常。

- `devicePredicate` 检查绑定到指定实例集合的参与设备。必须至少有一台观测；同集合内所有参与设备须满足判据。`states` 与 `outerStates` 任一匹配即满足。
- `factPredicate` 引用同一投影中的事实，允许前向引用，禁止环。
- `observationPredicate` 引用已声明的观测，`expected` 表示预期值。`device-connectivity` 适配器只判断当前参与目录中的设备可观测，不接受 `expected`；`device-state` 要求 `expected`，匹配当前叶状态或外层状态；尚未接入的 `external` 观测始终为 unknown。
- `all`：任一 violated 则 violated，否则有 unknown 则 unknown，全部 valid 才 valid。
- `any`：任一 valid 则 valid，否则有 unknown 则 unknown，全部 violated 才 violated。

空条件和空组合均被拒绝。只有 valid 可以放行。设备观测有效期统一由 `evidenceMaxAgeMs` 控制；断线、过期、来源变化、修订回退由 Java 的固定观测完整性逻辑处理，不再保留无人解释的 `invalidators`、`invalidOn` 等配置。

## 执行契约与设计说明

`structuralConstraints` 中的 `instanceCount` 和 `mappingCoverage` 会实际校验；映射本身也始终检查引用、数量、范围和完整覆盖。能力要求及输出依赖由 Java 运行协调器执行。

安全联锁现场输入、T0/T+3/T+5 时间窗口、资源租约等尚未实现的运行规则只放在 `annotations/notes`，分别以 `safety-design`、`timing-design` 等 `kind` 标注。未决问题放在 `annotations/issues`，未映射设备动作的能力通过自身 `annotations` 说明。工作台预览把它们标为“设计说明（不参与执行）”。新增 `safetyRules`、`timingContracts` 等未支持的可执行章节会校验失败。

这次格式规范化没有增加现场安全接口、物理联锁广播、独占租约或专用时间契约执行器。

## 校验与回归

三个权威 XSD 位于 [`server/src/main/resources/schemas/`](../../server/src/main/resources/schemas/)。它们限制层次、章节顺序、基数、类型、枚举、属性及部分文档内键/引用，不接收任意扩展元素或属性。服务端通过禁止 DTD 和外部实体的公共 `ModelXml` 入口加载内置 schema。

Java 语义校验补充端口归属/方向、物理接口一致性、信号、映射覆盖、依赖环、条件引用及跨文档关系。系统模型校验 API 返回 `validationScope=referenced-models`：定义中的 `stateModelHref` 解析到模型库当前已发布 SCXML，核验能力 `actionRef`；绑定解析当前已发布定义；投影另外核验组件叶子/外层状态及模型引用。运行部署以本次选中的实际设备 SCXML 再做检查，归档所有参与校验的组件模型；重启恢复使用同一份归档。候选模型检查和 Flowable 部署完成后才激活，失败不切换当前模型。BPMN 与 SCXML 元数据规范见[自定义模型契约](custom-model-contracts.md)。

使用 `mvn -pl server -am -Dexec.skip=true test` 验证语法和运行单元测试；使用 `mvn -Pnew-chain-e2e verify` 验证真实 Java JTango、Flowable 和前端构建。不会创建 CI 工作流。

启动时会按现有模型库规则更新未被用户修改的内置示例，并新增发布快照。用户修改过的旧格式草稿、发布内容和在途部署归档不会被自动改写；这些模型需按新格式重新导入/编辑并发布，旧格式在途归档不能在新解析器中恢复。
