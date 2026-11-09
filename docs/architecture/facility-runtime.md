# 两个 Java 后端的运行边界

设备接入与设施模型使用不同标识：`deviceBindingKey` 关联设备目录和设施绑定，`componentType` / `componentInstanceId` 表示组件类型和逻辑实例，`componentLabel` 仅用于显示。对应关系见[组件与设备命名](modules.md#组件与设备命名)。

设施运行系统与实验流程工作台分别部署。设备观测验证、设施事实、系统监督和设备命令准入均由 `facility-server` 承担；Flowable、发次编排、模型编辑和运行历史由 `workbench-server` 承担。关闭或重启工作台后，监督仍继续采集观测、计算事实、监测物理工作状态并查询未决命令结果。

```text
工作台 / Flowable（workbench-server，8080）
    │ HTTP 请求与 NDJSON 通知
    ▼
设施运行与监督（facility-server，8081）
    设备观测验证 → 设施事实 → 系统工作状态监督
                                  ↓
                           权威命令准入与结果证据
    内嵌 TangoAdapter；SimulatorLauncher 管理模拟设备进程
    │ JTango 命令、属性和事件
    ▼
真实设备或 Java SCXML 模拟设备
```

## 模块与数据归属

| 模块 | 拥有的职责 | 运行数据 |
|---|---|---|
| `component-contract` | 组件 SCXML 元数据及静态引用校验 | 无独立运行数据 |
| `facility-contract` | 设施定义、绑定、投影的 XML 模型、解析器与 XSD | 无独立运行数据 |
| `facility-server` | 观测有效性、设施事实、系统义务、动作准入、命令结果核验 | `data/supervision/` |
| `workbench-server` | 模型库、Flowable、发次记录、等待与保持作用域、工作台 API/UI | `data/workbench/`、H2 `data/control-v2` |
| `tango-adapter` | 设施后端内的 JTango 协议接入库 | 设备绑定配置，无设施事实存储 |
| `device-simulator` | 独立 SCXML 设备 JVM，按输入生成模拟配置 | `data/java-simulator/` |

两个后端都依赖共享契约。设施运行实现位于 `cn.cdi8.facility.runtime`，设施模型位于 `cn.cdi8.facility.contract`，工作台流程实现位于 `cn.cdi8.workbench.workflow`。`examples/` 作为内置模型的唯一源文件，构建时分别打包。完整模块依赖见[代码组织](modules.md)。

工作台的 `FacilityStateClient` 和 `FacilityDependencyClient` 是 HTTP 门面，不在本地重新生成权威设施事实。BPMN 的 `fac:operation` 只携带操作引用，`POST /admissions/check` 在 facility-server 内根据系统定义和事实计算准入证据；工作台不能通过本地组合事实或传入 `valid` 绕过它。旧 guard 入口暂时保留用于已有流程迁移。每个生产命令仍需由设施端重新核验，工作台不能通过传入有效依赖计划或释放自己的 watch 来移除命令的私有持续监控。

## 分阶段验证接口

所有接口以设施后端的 `/api/facility` 为前缀。原 `/api/supervision` 路径作为兼容入口保留。

| 接口 | 用途 |
|---|---|
| `GET /health` | 进程健康；不表示设备或联锁有效 |
| `GET /connection` | 设备观测通道状态 |
| `GET/POST /environment` | 读取或替换设施拥有的模拟环境；替换会检查活动义务、未决命令和活动设备命令 |
| `GET /devices`、`GET /devices/{device}` | 读取设备目录和原始快照，直接验证观测边界 |
| `GET /devices/{device}/history` | 读取设备动作历史 |
| `POST /validate` | 使用设施实际配置的模拟器校验 SCXML |
| `GET /facts?refresh=true` | 验证观测、逐设备事实、聚合事实和组合条件（兼容输出 guard）；不包含监督约束 |
| `GET /state?refresh=true` | 在事实之上增加工作状态义务及锁存事件 |
| `POST /admissions/check` | 按 `operationRef` 对当前设施事实执行一次启动准入检查并返回证据 |
| `GET/POST /models` | 读取或激活完整模型包 |
| `POST /devices/{device}/commands` | 权威动作准入、记录意图后下发 |
| `GET /devices/{device}/commands/{id}` | 查询已记录命令及准入证据 |
| `POST /dependencies/plan`、`check`、`release`、`retain` | 工作台作用域的依赖规划与监测 |
| `GET /events` | NDJSON 观测和状态变化通知；缺失通知后仍须重新查询 |
| `POST /adapter` | 白名单内的设备读取和实验控制；不能旁路生产命令准入 |

设备快照采用统一信封：组件快照和条件源快照都必须提供 `snapshot_kind`、`source_id`、非负 `state_revision` 及活动命令的一致字段。`tango-adapter` 在事件和补读进入设施运行时前拒绝缺字段或状态矛盾的快照；设施运行时随后负责来源切换、修订号回退和证据过期判断。GXLF 事实的逐项来源和 unknown 传播规则见[事实完整性矩阵](fact-integrity-matrix.md)。

可直接启动设施 JAR；仿真模式按设施服务器自己的 profile 创建 facility。用 `environment` 和 `devices` 验证设备观测，再用 `facts` 验证“设备观测 → 设施事实”，最后用 `state` 验证系统监督，整个过程不需要工作台。工作台只是随后连接该 facility 做流程编排。缺失、过期、来源不一致或修订号倒退的观测不能成为有效放行证据。

```bash
curl http://127.0.0.1:8081/api/facility/environment
curl http://127.0.0.1:8081/api/facility/devices
curl 'http://127.0.0.1:8081/api/facility/facts?refresh=true'
curl 'http://127.0.0.1:8081/api/facility/state?refresh=true'
```

## 监督义务与命令恢复

工作状态义务由物理事实激活，依靠明确的安全退出事实解除。停止 BPMN、设备进入 fault 或设备断连均不能证明能量已消失。瞬时失效会锁存，条件恢复后仍保留事件，直至观测到安全退出；事件历史继续保留。

监督端将模型、义务、事件和命令日志保存在自己的目录。日志采用临时文件、强制写入和原子替换；损坏或不可写时拒绝继续放行。该目录属于监督运行证据，不能作为可随意清除的缓存。当前实现面向单实例本地存储，不提供多副本协调或高可用日志。

命令先持久化唯一编号、意图、准入计划和证据，再调用适配器。响应丢失后，同一编号只查询结果；改变请求却复用编号会被拒绝。监督每两秒协调未决结果，即使没有工作台也不会停止。终态必须持久化后才可从缓存返回，工作台恢复不会自动重复 Execute。

监督重启会丢失停机期间的连续观测覆盖。重启前有效的活动义务和持续依赖不能仅凭恢复后的有效快照宣称一直成立；证据将保守地保持 unknown 或已锁存的失效。已持久化的终态可在设备不可用时继续查询。当前没有对长期未决命令的人工裁决接口。

更换模拟环境或设施模型时，监督检查活动义务、未决命令以及适配器中的活动命令。尚未退出的物理状态或未决结果会阻止替换。完全相同的模型包激活是恢复时的幂等操作，不重置义务。

## 构建与启动

在仓库根目录执行：

```bash
# 无需构建设备模拟器，打包两个后端与前端
make backend-package

# 设施后端可以独立启动并建立默认仿真 facility
java -jar facility-server/target/facility-server-0.1.0.jar --control.simulation.enabled=true
# 真实设备模式则使用 TANGO_BINDINGS，并关闭模拟环境
# java -jar facility-server/target/facility-server-0.1.0.jar --control.tango-bindings=bindings.json

# 工作台是可选客户端
java -jar workbench-server/target/workbench-server-0.1.0.jar

# 完整模拟链路（需要模拟器依赖可用）
make simulation
java scripts/Services.java status

# 已构建后，仅管理设施后端及其模拟设备子进程
java scripts/Services.java start facility
# 单独重启工作台，监督保持运行
java scripts/Services.java restart workbench
```

默认端口为工作台 8080、设施后端 8081。设备接入直接使用 JTango，不再开放 8766。`FACILITY_URL` 配置工作台连接的设施地址；`TANGO_BINDINGS` 指向已有设备绑定 JSON；`FACILITY_STORE` 配置监督日志目录；`FACILITY_PORT` 配置设施端口。仿真使用 `SIMULATION_ENABLED=true`、`SIMULATOR_PROFILE`、`SIMULATOR_JAR` 和 `SIMULATOR_DIRECTORY`；默认 profile 为 `classpath:simulation/gxlf-facility.json`，也可用外部 JSON 替换。模拟器由设施后端在独立 JVM 中启动。`TANGO_BRIDGE_URL` 已移除。

原 `SUPERVISION_URL`、`SUPERVISION_PORT`、`SUPERVISION_STORE` 环境变量，以及 `control.supervision-url`、`control.supervision-store` 配置仍可作为旧名称使用。新配置优先。默认数据目录继续为 `data/supervision/`；启动器沿用原 PID、日志和锁文件，以识别迁移前已运行的进程。

## 当前范围

当前完成独立运行、HTTP 边界和本地证据恢复。真实外部联锁、预约服务与心跳仍需接入，缺失观测保持 unknown。失效的活动系统义务目前会拦截所有生产动作；恢复、泄能等动作尚无独立的模型准入政策，不能将当前实现当作完整设施安全控制器。

`adapter` 中的 simulate、events 和 conditions 接口保留为实验控制。将系统用于真实设备前，需要按部署环境限制这些测试接口，并明确恢复动作与安全控制的责任边界。
