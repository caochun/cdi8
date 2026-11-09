# 核心链路与验证范围

当前维护范围包括设备仿真、观测验证、事实投影、监督义务、命令准入及证据恢复，以及调用该运行系统的工作台和 BPMN。模型静态校验、环境创建、动作反馈、条件与故障注入是验证这条链路的必要支撑。

## 核心实现

| 层次 | 主要实现 | 职责 |
|---|---|---|
| 设备及快照 | [ComponentRuntime](../../device-simulator/src/main/java/cn/cdi8/simulator/runtime/ComponentRuntime.java)、[ComponentDevice](../../device-simulator/src/main/java/cn/cdi8/simulator/tango/ComponentDevice.java) | 执行 SCXML、命令幂等、动作反馈、SQLite 和带来源/修订号的快照 |
| 设备适配 | [TangoAdapter](../../tango-adapter/src/main/java/cn/cdi8/tango/TangoAdapter.java)、[SimulatorLauncher](../../facility-server/src/main/java/cn/cdi8/facility/simulation/SimulatorLauncher.java) | 进程内 JTango 访问、模拟设备子进程生命周期 |
| 观测验证 | [TangoGateway](../../facility-server/src/main/java/cn/cdi8/facility/integration/TangoGateway.java)、[FacilityStateRuntime](../../facility-server/src/main/java/cn/cdi8/facility/runtime/FacilityStateRuntime.java) | 补读与事件协调、来源/修订号/新鲜度验证 |
| 设施事实 | [FacilityFactProjection](../../facility-server/src/main/java/cn/cdi8/facility/runtime/FacilityFactProjection.java)、[状态投影 XML](../../examples/gxlf/system/gxlf-facility-state-projection.xml) | 逐设备事实、聚合事实和组合条件；缺失或无效证据保持 unknown |
| 系统监督 | [FacilitySupervision](../../facility-server/src/main/java/cn/cdi8/facility/runtime/FacilitySupervision.java)、FacilityStateRuntime | 按物理事实激活/释放义务、持续检查、失效锁存 |
| 命令与恢复 | [FacilityCommandAdmission](../../facility-server/src/main/java/cn/cdi8/facility/runtime/FacilityCommandAdmission.java)、[FacilityDependencyRuntime](../../facility-server/src/main/java/cn/cdi8/facility/runtime/FacilityDependencyRuntime.java)、[FacilityRuntimeStore](../../facility-server/src/main/java/cn/cdi8/facility/runtime/FacilityRuntimeStore.java) | 权威准入、先记意图再下发、持续依赖、结果协调和持久化 |
| 模型契约 | [ComponentContract](../../component-contract/src/main/java/cn/cdi8/component/contract/ComponentContract.java)、[facility-contract](../../facility-contract) | SCXML 元数据、设施 XML/XSD 和跨模型静态引用校验 |
| 流程与工作台 | [workbench-server](../../workbench-server)、[workbench-ui](../../workbench-ui) | 模型版本、环境配置、Flowable、BPMN 条件/保持、发次与历史证据 |

事实计算、监督规则、调度、设备连接、权威运行状态和持久化均由设施后端拥有；共享契约只提供模型定义和校验。包与模块的对应关系见[代码组织](../architecture/modules.md)。

## 验证顺序

在仓库根目录执行：

```bash
# 两个后端、共享契约及前端的构建和默认测试
mvn -B -ntp -pl facility-server,workbench-server -am package

# 独立验证 Tango 适配器，无需 SCXML 引擎
mvn -B -ntp -pl tango-adapter -am package

# 单独构建和测试模拟器（SCXML 2 固定发行包从 Maven Central 获取）
mvn -B -ntp -pl device-simulator -am package

# 设施独立快照边界：真实模拟器 JVM、JTango 和 facility-server，不启动 Workbench
mvn -B -ntp -Pfacility-e2e -pl facility-server -am -Dexec.skip=true verify

# 全链路：隔离端口、临时目录、真实 JTango、监督和 Flowable
mvn -B -ntp -Pworkbench-e2e verify
```

默认后端测试验证无效/缺失观测、事实三值、义务激活与安全退出、失效锁存、依赖与准入、日志失败及重启恢复、工作台远端边界和 BPMN 模型引用。`WorkbenchIT` 由 `workbench-e2e` profile 启用，需要可运行的模拟器 JAR；默认 `package` 通过不代表端到端已通过。

`FacilityObservationIT` 由 `facility-e2e` profile 启用，先构建设备模拟器 JAR，再启动独立的 facility-server。它验证环境和设备目录、逐设备快照、事件流启动信封、单设备命令后的执行/完成快照、事实接口边界以及环境重建后的新设备代次；测试不启动工作台或 Flowable。

设施 XML 校验测试集中在 `facility-contract`，两个后端不再重复维护。日常按变更范围选择模块：文档检查链接，局部修改运行相关测试，跨模块边界或流程语义变化时再执行完整回归。

也可先独立运行监督后端，依次查询 `/api/facility/facts?refresh=true` 和 `/api/facility/state?refresh=true`，然后接入工作台验证 BPMN。健康接口只表示进程运行，适配器未连接时事实和放行条件保持 unknown。

模拟器固定使用 `de.dfki.cos.basys.common:scxml:0.5.1`，这是包含 `org.apache.commons.scxml2` 的 BaSyS 发行包，已移除失效的 Apache 时间戳快照依赖。兼容性测试覆盖 `In()`、无副作用的就绪查询、迁移赋值、祖先事件匹配，以及 SQLite 重启后的叶子状态和命令幂等恢复；更换引擎版本时需保留这些验证。

内嵌 Tango 接入调整后的验证结果：

- Java 构建及默认测试通过：设施契约 16 项、适配器 4 项、模拟器 36 项、设施后端 51 项、工作台 47 项。Java 验证使用 `-Dexec.skip=true`；命名调整后单独执行 `npm run build --prefix workbench-ui`，前端类型检查和构建通过。
- 初始化联调通过：十四类设备初始化与参数接收，43 条命令完成并保留运行证据。
- `EmbeddedTangoIT` 两项真实进程测试通过：设施仅创建一个设备子 JVM；活动命令阻止重建；重建失败保留旧环境；成功替换和关闭设施回收旧进程；外部设备接入不需要模拟器 JAR，关闭设施不停止外部设备。
- 设备动作失败、设备退出以及旧/歧义 BPMN 模型拒绝的定向测试通过。设备退出后流程停止，未获得终态的设备命令保留为 unknown。模拟器 `--validate` CLI 已验证正常 SCXML 和无效模型两个输入。

联调中同时修复了两处通信处理问题：设施 NDJSON 流不再被默认 30 秒异步请求超时截断，失联仍由客户端心跳检查识别；监督观测不可用时，流程按观测失效停止，不再因查询异常跳过终止处理。

组件/设备命名调整额外验证了不同名的绑定键与组件类型、旧配置及绑定 XML 的读取、冲突字段拒绝，以及历史依赖的锁存证据保留；初始化联调和两个真实进程测试通过。

旧的多组件事实测试仍未通过：`preamp_02` 的手工动作请求缺少束线准入上下文，被设施后端拒绝；未为测试放宽准入检查。全量 `WorkbenchIT` 尚未确认通过，以上结果仅代表列出的验证范围。

## 当前可运行与未完成范围

- `gxlf_shot_run_smoke`：十四类设备初始化、十二类设备参数接收与本发动作证据。
- `gxlf_supervision_bridge`：设备变化 → 事实 → 监督失效 → BPMN 异常边界验证。
- `gxlf_stage_hold`：附加光学保持的技术测试；隔离测试契约不能作为真实安全条件。
- `gxlf_shot_run_target`：完整发次的目标契约，`isExecutable="false"`，可校验/预览，启动必须拒绝。

真实联锁、服务心跳、资源预约、专用触发时序、正式结果和安全退出接口仍需接入；缺少外部观测不自动补成 valid。监督日志面向本地单实例；长期未决命令人工裁决、恢复/泄能动作的独立准入政策和现场异常处置闭环尚未实现。设备 SQLite 只保证当前项目约定的状态与上下文恢复，任意 SCXML 定时器和外部副作用不在已保证范围内。

当前运行语义以[监督运行边界](../architecture/facility-runtime.md)和模型契约为准，历史讨论与实施清单见[归档](../../archive/README.md)。
