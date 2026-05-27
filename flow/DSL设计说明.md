# GXLF 激光发射控制流程 DSL 设计说明

> 本文档说明 `gxlf-firing-flow.yaml` 的设计思想、流程模型元素定义和设计决策。

---

# 第一部分：设计思想

## 1. 问题域特征

激光发射控制流程有以下特征，决定了 DSL 的设计方向：

**拓扑结构：不是线性流水线，而是 DAG。** 参数下发之后 6 条链路并行展开，各自经过不同长度的串行阶段，在"发射准备全部完成"汇合。正式发射后又扇出 8 条并行后处理链路，最终汇入"屏蔽门打开"。节点之间的依赖关系构成一个有向无环图（DAG），不能用简单的阶段→步骤两层结构表达。

**时序约束：存在硬实时子图。** 同步触发环节的 T+0/T+3/T+5 秒时序是物理约束，不是普通的"A 完成后执行 B"。三个动作（同步触发、泵浦触发准备、开关触发准备）在 T+0 同时发出，必须在 T+3 时刻收到就绪确认，T+5 完成触发。如果用普通 DAG 节点建模，会产生循环依赖。

**安全约束：全局守卫贯穿始终。** 安全联锁不是流程中的一个节点，而是一个随时可以中断任意节点的全局机制。它有自己的进入条件、触发源、触发动作和解除条件，独立于 DAG 拓扑。

**设备多样性：15 类分系统，1 到 60 套不等。** 同一条指令可能需要扇出到 60 套设备（如集中同步分系统按束线扇出），也可能只发给 1 套（如靶瞄准定位系统）。扇出策略和完成判定策略因节点而异。

**可复用结构：同步触发序列出现两次。** 主发射和预电离阶段各有一次同步触发，结构完全相同，只是前置条件和参数不同。

## 2. 核心设计决策

### 2.1 节点中心，而非边中心

DSL 以节点为中心组织：每个节点声明自己的属性和 `depends`（依赖的上游节点列表），DAG 拓扑从 depends 中推导。

**为什么不用边中心（单独维护一个边列表）：** 业务文档中出现过链路清单（第 5 章，边中心）和节点 card（第 6 章，节点中心）两套描述，分析时发现两者存在多处不一致（A2-A5 问题）。这正是因为拓扑信息和节点属性分开维护导致的。节点中心设计让拓扑和属性在同一处定义，消除了这种不一致的可能。

**推导而非声明：** 从所有节点的 depends 字段可以自动构建完整的 DAG，不需要人工维护边列表。校验脚本基于此做环检测、可达性分析和关键路径计算。

### 2.2 三种节点类型，而非一种

| 类型 | 语义 | 为什么需要 |
|------|------|-----------|
| `action` | 向分系统发送控制指令 | 覆盖 58 个 card 中的绝大多数 |
| `join` | AND 汇合点，等待所有 depends 完成，不下发指令 | "发射准备全部完成"等节点在业务上不执行动作，只是一个同步屏障 |
| `timed_sequence` | 带硬实时约束的时序子图 | 同步触发的 T+0/T+3/T+5 不能用普通 DAG 依赖表达 |

引入 `timed_sequence` 是为了解决文档中同步触发节点的循环依赖问题（A1 问题）：card 描述中，同步触发要求泵浦/开关触发准备完成，泵浦/开关触发准备又要求同步触发倒计时开始。这三个动作实际上不是独立的 DAG 节点，而是一个原子性的时序编排，用 timed_sequence 封装后，对 DAG 调度器暴露为一个整体节点，内部的时序约束由专门的执行器处理。

### 2.3 结构性依赖与运行时守卫分离

每个节点有两层前置条件：

```yaml
depends: [预放唤醒, 光纤种子源出光]   # 结构性：DAG 拓扑
runtime_guards:                        # 运行时：执行前检查
  - { service: 再生与双程放大组件, check: running }
  - { interlock: safety, check: normal }
```

**为什么分离：**

- `depends` 是静态的拓扑关系，可以在流程启动前做静态分析（环检测、关键路径、可达性）。DAG 调度器据此决定何时可以调度一个节点。
- `runtime_guards` 是动态的运行时检查，在节点即将执行时才评估。它检查的是系统服务状态、安全联锁状态等实时信息，这些信息无法在静态分析时获取。

如果把两者混在一起（如文档中的"执行判断条件"），会导致两个问题：无法做静态分析（因为混入了运行时条件），也难以判断哪些条件影响调度顺序、哪些只是执行前的安全检查。

### 2.4 安全联锁作为横切关注点

安全联锁放在 `global_guards` 中，独立于节点定义：

```yaml
global_guards:
  safety_interlock:
    enter_conditions: [...]
    trigger_sources: [...]
    on_trigger: [...]
    release_conditions: [...]
```

**为什么不把联锁建模为 DAG 中的节点：** 联锁不是流程中的一个步骤，而是一个全局约束——在流程的任意时刻，如果触发条件满足，都会立即中断所有节点。将其作为 DAG 节点会面临"需要连接到每个节点"的问题，形式上不优雅且容易遗漏。作为横切关注点，联锁与 DAG 正交：DAG 管正常流程的推进，联锁管异常情况的熔断。

紧急处理（紧急停车、紧急泄放等）同理放在 `global_guards.emergencies` 中，每种紧急操作声明自己的生效窗口（哪些节点执行期间有效）和触发动作。

### 2.5 设备扇出是节点属性，不是拓扑结构

```yaml
target:
  system: [集中同步分系统]
  fan_out: by_beam_line       # 按束线编号扇出到60套
  completion: all_success     # 全部成功才算完成
```

**为什么不把每个设备实例建模为子节点：** 如果把"向 60 套同步系统发指令"拆成 60 个子节点，DAG 会从 56 个节点膨胀到数千个，丧失可读性和可维护性。实际上，对流程调度器而言，"向 60 套设备发指令并等待全部返回"是一个原子操作，其内部的并行扇出是执行器层面的实现细节。

`fan_out` 策略（broadcast / by_beam_line / by_beam_group）和 `completion` 策略（all_success / any_success / majority）作为节点属性声明，由执行器在运行时解释。

### 2.6 模板实现复用

```yaml
templates:
  同步触发序列:
    timed_sequence:
      T+0s: { parallel: [...] }
      T+3s: { require: [...] }
      T+5s: { action: 正式触发 }

nodes:
  主发射触发:
    type: timed_sequence
    template: 同步触发序列
    args: { 触发类型: 主发射 }
  预电离触发:
    type: timed_sequence
    template: 同步触发序列
    args: { 触发类型: 预电离 }
```

主发射和预电离的同步触发结构完全相同（T+0/T+3/T+5 时序、参与的分系统），只是前置条件和触发类型不同。模板机制避免了重复定义，也确保两处修改时保持一致。

### 2.7 失败处理作为节点属性

```yaml
on_failure:
  strategy: retry          # retry | pause | abort | skip | emergency
  max_retries: 2
  on_max_retries: pause    # 重试耗尽后的降级策略
```

**为什么不用全局统一的失败策略：** 不同节点的失败后果差异极大：

- 发射充电失败需要紧急泄放（安全风险）
- 清场失败需要暂停等人工处理（安全前提）
- 后处理数据采集失败可以跳过（不阻塞流程）
- 配方切换失败可以重试（快速操作，暂态问题）

全局统一策略要么过于保守（全部暂停），要么过于激进（全部重试）。按节点声明失败策略，让每个节点的容错行为与其业务语义匹配。

## 3. 与业务文档的映射关系

| 文档结构 | DSL 映射 |
|----------|---------|
| 第 2 章 分系统清单 | `systems` 注册表 |
| 第 3 章 安全联锁规则 | `global_guards.safety_interlock` |
| 第 3 章 异常处理场景 | `global_guards.emergencies` |
| 第 5 章 链路清单（边） | 每个节点的 `depends`（自动推导 DAG） |
| 第 6 章 节点 card | 每个节点的完整定义（target, command, input, output, timeout, guards, on_failure） |
| 第 1 章 口径-同步触发 | `templates.同步触发序列` + `timed_sequence` 节点 |

文档中已确认的矛盾和勘误（A1-A6, B1-B3, C1, D1-D3）已在 DSL 中按分析结论修正，修正处均用 `# [修正Ax]` 注释标注。

## 4. 静态可分析性

DSL 的声明式设计使得以下分析可以在流程运行前完成（由 `validate.py` 实现）：

| 分析项 | 目的 |
|--------|------|
| DAG 环检测 | 确保 depends 不构成循环（如 A1 问题修正后的验证） |
| 可达性 | 从入口节点出发，所有节点都可达（不存在孤立节点） |
| 终点收敛 | 所有节点最终都能到达结束节点（不存在悬空分支） |
| 依赖引用检查 | depends 中引用的节点名都存在 |
| 系统引用检查 | target.system 引用的分系统都已注册 |
| 模板引用检查 | timed_sequence 引用的模板存在 |
| ID 唯一性 | 每个节点的 id 不重复 |
| 关键路径估算 | 基于 timeout 的最长串行路径，与业务预期时长交叉验证 |

这些分析如果在运行时才做，代价是一次失败的发射实验；在设计时做，代价只是一次脚本运行。

## 5. 不在 DSL 中表达的内容

以下内容有意排除在 DSL 之外：

| 内容 | 排除理由 | 归属 |
|------|---------|------|
| 发次间流水线调度 | 属于上层调度器，不在单次流程定义内 | 发射任务管理系统 |
| 具体参数值 | 发次编号、束线编号等运行时绑定 | 流程实例化时注入 |
| 设备通信细节 | Tango attribute 名、HTTP endpoint、序列化格式 | 设备适配层 |
| 分系统内部逻辑 | 预放大器如何做能量闭环 | 各分系统自治 |
| 光路性能量建模 | 不在本文控制逻辑范围内 | 物理仿真系统 |

DSL 只关心**控制面**：什么时候向谁发什么指令、等谁完成、失败了怎么办。数据面和物理面由其他系统负责。

## 6. 设计约束与权衡

**可读性优先于简洁性。** YAML 比自定义语法冗长，但不需要学习新语法，IDE 原生支持，JSON Schema 可以做结构校验。56 个节点的完整定义约 1000+ 行 YAML，每个节点平均 ~20 行，包含了全部业务信息，可以直接与文档第 6 章逐条对照。

**声明式优先于过程式。** 流程的主体是 DAG 拓扑和节点属性，天然适合声明式。过程式（用代码写 if/fork/join）会把拓扑淹没在控制逻辑里。唯一引入过程式语义的地方是 `timed_sequence`，因为时间窗口约束无法用纯依赖关系表达。

**节点 ID 与节点名共存。** `id`（如 N01）用于程序引用和日志追踪，节点名（如"参数下发"）用于 `depends` 中的人类可读引用。depends 使用节点名而非 ID，是因为在 YAML 中 `depends: [参数下发]` 比 `depends: [N01]` 可读性更好，维护时不需要反复查表。

---

# 第二部分：流程模型元素说明

## 7. 整体结构

```
FlowDefinition (YAML 文件)
├── flow                              # 流程基本信息
├── systems: Map<名称, System>         # 分系统注册表
├── global_guards                      # 全局守卫
│   ├── safety_interlock               #   安全联锁
│   └── emergencies: Emergency[]       #   紧急处理
├── templates: Map<名称, Template>     # 可复用子图模板
└── nodes: Map<名称, Node>            # 节点定义
         ├── ActionNode                #   控制动作节点 (type: action)
         ├── JoinNode                  #   AND 汇合节点 (type: join)
         └── TimedSequenceNode         #   时序子图节点 (type: timed_sequence)
```

五个一级元素各自独立，互相通过**名称引用**：节点引用系统名、引用模板名、引用其他节点名。这种松耦合使得每个部分可以独立阅读和校验。

## 8. flow — 流程基本信息

```yaml
flow:
  name: "GXLF激光发射控制流程"
  version: "1.0"
  description: "从参数下发到结束的完整发射运行链路"
```

| 字段 | 类型 | 必填 | 含义 |
|------|------|------|------|
| `name` | string | 是 | 流程名称，在日志和 UI 中展示 |
| `version` | string | 是 | 版本号，追踪流程定义的变更（如业务方确认某个待确认项后递增） |
| `description` | string | 否 | 流程范围和目的的自然语言描述 |

纯标识信息，不参与流程逻辑。

## 9. systems — 分系统注册表

```yaml
systems:
  集中同步分系统:           # ← key: 系统名，全局唯一
    instances: 60
    protocol: tango
    fan_out: by_beam_line
    description: "预放/测量重频配方、单次配方、同步触发"
```

| 字段 | 类型 | 必填 | 含义 |
|------|------|------|------|
| `instances` | integer ≥ 1 | 是 | 物理实例数量 |
| `protocol` | enum | 是 | 通信协议 |
| `fan_out` | enum | 否 | 默认扇出策略（节点可覆盖） |
| `description` | string | 否 | 职责描述 |

**protocol 取值：**

| 值 | 含义 |
|-----|------|
| `tango` | 通过 Tango Controls 框架的 DeviceProxy 通信 |
| `http` | 通过 HTTP 接口通信 |
| `tbd` | 接口类型待确认（用于基建系统等尚未定义接口的系统） |

**fan_out 取值：**

| 值 | 含义 | 典型系统 |
|-----|------|---------|
| `by_beam_line` | 按束线编号一对一扇出到 N 套设备 | 集中同步(60)、多程放大(60)、测量取样(60) |
| `by_beam_group` | 按束线组扇出，每组含多束 | 再生与双程放大(10)，每台管 6 束 |
| `broadcast` | 单条指令广播（通常只有 1 套） | 泵浦(1)、靶瞄(1)、控制环境(1) |

systems 是**实体注册表**——它不参与流程执行，而是作为节点 `target.system` 引用的合法值域。校验脚本检查所有节点引用的系统名是否在此注册。

## 10. global_guards — 全局守卫

### 10.1 safety_interlock — 安全联锁

```yaml
global_guards:
  safety_interlock:
    enter_conditions:
      - { source: 中子屏蔽门, check: locked }
      - { source: 人员计数, check: zero }
      - { source: 靶室真空度, check: normal }
    trigger_sources:
      - { name: 人员被困, type: manual }
      - { name: 真空度异常, type: auto, source: 多程放大系统钛泵/靶室真空度 }
    on_trigger:
      - { command: FlowTerminate, target: all_systems }
      - { command: DoorUnlock, target: 建安工程安防系统屏蔽门控制接口 }
    release_conditions:
      - "屏蔽门解除锁闭"
      - "警灯警报解除"
```

| 字段 | 含义 |
|------|------|
| `enter_conditions` | 联锁生效的前提（全部满足才进入联锁保护状态） |
| `trigger_sources` | 什么事件会触发熔断 |
| `on_trigger` | 熔断后立即执行的动作序列 |
| `release_conditions` | 联锁解除的条件 |

安全联锁是一个独立的**状态机**，与流程 DAG 并行运行：

```
未生效 ──(enter_conditions 满足)──→ 生效中 ──(trigger 触发)──→ 已触发
                                                                  │
                                                      执行 on_trigger
                                                                  │
                                    解除 ←──(release_conditions 满足)──┘
```

**enter_conditions 中的 Condition：**

| 字段 | 含义 |
|------|------|
| `source` | 被监测的对象 |
| `check` | 期望的状态值 |
| `detail` | 补充说明（可选） |

**trigger_sources 中的 TriggerSource：**

| 字段 | 含义 |
|------|------|
| `name` | 触发源名称 |
| `type` | `manual`（人工按钮）或 `auto`（系统自动检测） |
| `source` | 自动触发时的监测来源 |

### 10.2 emergencies — 紧急处理

```yaml
emergencies:
  - name: 紧急泄放
    description: "充电完成后未能触发"
    window_after: [发射充电, 预电离发射充电]
    condition: "未能触发"
    action: { command: EmergencyDischarge, target: 泵浦分系统 }
```

| 字段 | 类型 | 必填 | 含义 |
|------|------|------|------|
| `name` | string | 是 | 紧急操作名称 |
| `description` | string | 否 | 触发场景描述 |
| `window` | string[] | 否 | 在哪些节点**执行期间**可触发 |
| `window_after` | string[] | 否 | 在哪些节点**完成之后**可触发 |
| `window_during` | string[] | 否 | 在哪些节点**执行过程中**可触发 |
| `condition` | string | 否 | 附加触发条件 |
| `action` | { command, target } | 是 | 触发后执行的动作 |

三种时间窗口的关系：

```
节点执行时间线:    ┃━━━━ 执行中 ━━━━┃
                  │                │
  window_during ──┤  这段时间内    ├── window_after: 此之后
  window ─────────┤  同 during     │
```

紧急处理是一组**条件→动作规则**，绑定到特定的时间窗口。当操作员按下紧急按钮或系统检测到异常时，引擎查找当前活跃窗口匹配的规则并执行对应动作。

## 11. templates — 可复用模板

```yaml
templates:
  同步触发序列:              # ← key: 模板名
    params: [触发类型]
    timed_sequence:
      T+0s:
        parallel:
          - { command: SyncTrigger, target: 集中同步分系统 }
          - { command: PumpTriggerReady, target: 泵浦分系统 }
      T+3s:
        require:
          - { name: 泵浦触发准备完成, target: 泵浦分系统 }
      T+5s:
        action: "正式触发"
    on_timeout: EmergencyTrigger
    total_duration: 10s
```

| 字段 | 类型 | 必填 | 含义 |
|------|------|------|------|
| `params` | string[] | 否 | 参数列表，实例化时通过 `args` 传入 |
| `timed_sequence` | Map<时间偏移, 时间槽> | 是 | 时间窗口 → 动作的映射 |
| `on_timeout` | string | 否 | 整体超时的降级命令 |
| `total_duration` | duration | 否 | 序列最大时长 |

**时间偏移** 格式为 `T+{数字}{单位}`（如 `T+0s`、`T+3s`），以序列启动时刻为 T+0。

**时间槽** 有三种操作：

| 字段 | 含义 | 示例 |
|------|------|------|
| `parallel` | 在此时刻并行下发的指令列表 | T+0s 同时下发同步触发、泵浦准备、开关准备 |
| `require` | 在此时刻必须满足的就绪条件 | T+3s 泵浦和开关必须已完成准备 |
| `action` | 在此时刻执行的最终动作 | T+5s 正式触发 |

模板是一个**参数化的子流程定义**，它不直接出现在 DAG 中，而是被 `timed_sequence` 类型的节点引用。同一模板可被多个节点引用（主发射触发和预电离触发引用同一个模板），每次通过 `args` 传入不同参数。

模板内部的时间槽之间不是依赖关系（A 完成后执行 B），而是**绝对时间约束**（T+3s 时必须满足某条件）。这种语义无法用 `depends` 表达，因此需要独立的元素。

## 12. nodes — 节点定义

Node 是 DAG 中的顶点，也是 DSL 的核心元素。Map 的 key 是节点名（人类可读），value 是节点定义。

### 12.1 所有节点共有的字段

| 字段 | 类型 | 必填 | 含义 |
|------|------|------|------|
| `id` | string（格式 N + 2位数字） | 是 | 全局唯一标识，用于程序引用和日志 |
| `type` | enum: `action` / `join` / `timed_sequence` | 是 | 节点类型，决定行为语义和必填字段 |
| `depends` | string[] | 是 | 上游依赖节点名列表（空数组 = 入口节点） |
| `runtime_guards` | RuntimeGuard[] | 否 | 执行前的运行时检查 |
| `on_failure` | OnFailure | 否 | 失败处理策略 |
| `description` | string | 否 | 业务说明 |

**id 与节点名的分工：**

```yaml
nodes:
  参数下发:          # ← 节点名：人类可读，用于 depends 引用
    id: N01          # ← 节点 ID：程序标识，用于日志和状态追踪
```

两者一一对应但用途不同：节点名面向人（`depends: [参数下发]` 比 `depends: [N01]` 可读），id 面向机器（日志中 `[N01]` 比 `[参数下发]` 紧凑）。

**depends 的语义 — AND 依赖：**

```
depends: []          → 入口节点，流程启动时即可调度
depends: [A]         → 单一前驱，A 完成后可调度
depends: [A, B, C]   → AND 汇合：A、B、C 全部完成后才可调度
```

`depends` 定义了 DAG 的边——从每个依赖项到当前节点各画一条有向边。

### 12.2 action — 控制动作节点

当 `type: action` 时，节点代表一次向分系统发送控制指令的操作。在共有字段之外，还需要：

| 字段 | 类型 | 必填 | 含义 |
|------|------|------|------|
| `target` | Target | 是 | 指令发送目标 |
| `command` | string | 是 | 控制指令名称（CamelCase） |
| `call_mode` | enum: `sync` / `async` | 是 | 调用方式 |
| `timeout` | duration | 是 | 超时时长 |
| `input` | InputParam[] | 否 | 输入参数列表 |
| `output` | Map | 否 | 输出参数定义 |

**target — 指令目标：**

```yaml
target:
  system: [集中同步分系统]     # 目标系统（引用 systems 中的 key）
  fan_out: by_beam_line        # 扇出策略
  completion: all_success      # 完成判定策略
```

| completion 值 | 含义 | 场景 |
|------|------|------|
| `all_success` | 全部设备返回成功才算完成 | 绝大多数节点 |
| `any_success` | 任一成功即完成 | 容错场景（至少一路出光即可） |
| `majority` | 超半数成功即完成 | 预留 |

**call_mode — 调用方式：**

| 值 | 含义 | 典型时长 |
|-----|------|---------|
| `sync` | 同步阻塞等待返回 | 1~2s（配方切换、清零） |
| `async` | 异步等待完成回调/轮询 | 15s~40min（充电、出光、吹扫） |

**timeout — 超时时长：** 格式为 `数字 + 单位`（`2s`、`10min`、`1.5min`）。从指令发出到收到完成确认的最大等待时间，超时即判定失败。

**input — 输入参数：**

```yaml
input:
  - { name: 发次编号, type: string }
  - { name: 出光标识, type: enum, values: [0, 1], mapping: { 0: 关光, 1: 出光 } }
```

| 字段 | 含义 |
|------|------|
| `name` | 参数名称 |
| `type` | 数据类型（string / number / enum / boolean / object） |
| `values` | enum 类型的可选值列表 |
| `mapping` | 值到业务含义的映射（便于阅读） |

input 是**接口契约**——定义向分系统发送指令时需要什么参数。具体的参数值在运行时绑定（发次编号来自上游的发射任务管理系统），DSL 只定义名称和类型。

**output — 输出参数：** 大多数节点的输出是标准的三值接收状态（0:失败 / 1:收到 / 2:无服务权限）。少数节点有特殊输出（如屏蔽门状态）。

### 12.3 join — AND 汇合节点

当 `type: join` 时，节点是一个纯粹的同步屏障——不向任何分系统发指令，只等待所有 `depends` 完成。

```yaml
发射准备全部完成:
  id: N22
  type: join
  depends: [能量精闭环_1, 诊断设备发射准备, 靶瞄进入打靶状态, ...]
  runtime_guards:
    - { check: 各系统服务运行正常 }
```

不需要 target、command、timeout 等字段。join 存在的意义是**显式标记业务中的汇合点**——文档中的"发射准备全部完成"就是一个典型的 join：9 项 readiness 全部满足后通过，本身不下发任何指令。

### 12.4 timed_sequence — 时序子图节点

当 `type: timed_sequence` 时，节点内部是一个带绝对时间约束的子流程。

```yaml
主发射触发:
  id: N34
  type: timed_sequence
  template: 同步触发序列       # 引用模板
  args: { 触发类型: 主发射 }   # 传给模板的参数
  depends: [发射充电, 警灯警示音乐开启]
  on_failure:
    strategy: emergency
    action: { command: EmergencyTrigger, target: 集中同步分系统 }
```

| 字段 | 含义 |
|------|------|
| `template` | 引用的模板名 |
| `args` | 传递给模板的参数值 |

对 DAG 调度器而言，timed_sequence 是一个**不可分割的原子节点**——它有 depends（等上游完成后才启动），有 on_failure（失败后的处理策略），但内部的 T+0/T+3/T+5 时序由专门的时序执行器管理，不暴露给调度器。

为什么不拆成多个 action 节点：同步触发、泵浦触发准备、开关触发准备之间的关系不是"A 完成后执行 B"，而是"T+0 同时启动，T+3 检查就绪，T+5 最终触发"。拆成独立节点会形成循环依赖（A1 问题），timed_sequence 用时间偏移量代替依赖边来表达这种关系。

## 13. runtime_guards — 运行时守卫

```yaml
runtime_guards:
  - { service: 再生与双程放大组件, check: running }
  - { node: 光纤种子源出光, check: completed }
  - { interlock: safety, check: normal }
  - { check: 片放吹扫停止 }
```

| 字段 | 必填 | 含义 |
|------|------|------|
| `check` | 是 | 期望的状态值或检查项名称 |
| `service` | 否 | 检查某个分系统的服务运行状态 |
| `node` | 否 | 检查某个节点的完成状态 |
| `interlock` | 否 | 检查安全联锁状态 |

四种使用模式：

| 写法 | 含义 |
|------|------|
| `{ service: X, check: running }` | 检查分系统 X 的服务是否正常运行 |
| `{ node: X, check: completed }` | 检查节点 X 是否已完成（防御性检查） |
| `{ interlock: safety, check: normal }` | 检查安全联锁状态是否正常 |
| `{ check: 片放吹扫停止 }` | 通用检查项 |

**与 depends 的区别：**

```
depends: [预放唤醒]
  → 调度级别：DAG 调度器在预放唤醒完成前不会调度本节点
  → 静态可分析

runtime_guards: [{ node: 预放唤醒, check: completed }]
  → 执行级别：节点即将执行时再次确认（防御性）
  → 不参与静态分析
```

两者可以共存。guard 中的 `{ node: X, check: completed }` 在大多数情况下与 `depends: [X]` 冗余，但有时 guard 会检查非直接上游的节点（如能量精闭环_2 检查"光纤种子源出光"，但出光不在它的 depends 中，因为有更近的依赖路径）。

## 14. on_failure — 失败处理策略

```yaml
on_failure:
  strategy: retry
  max_retries: 2
  retry_interval: 10s
  on_max_retries: pause
  notify: [总控操作员]
```

| 字段 | 类型 | 必填 | 含义 |
|------|------|------|------|
| `strategy` | enum | 是 | 首选失败策略 |
| `max_retries` | integer ≥ 0 | 否 | 最大重试次数（strategy=retry 时） |
| `retry_interval` | duration | 否 | 两次重试间隔 |
| `on_max_retries` | enum | 否 | 重试耗尽后的降级策略 |
| `notify` | string[] | 否 | 需要通知的角色 |
| `action` | { command, target } | 否 | strategy=emergency 时的紧急动作 |

**五种策略的行为：**

```
节点失败
  │
  ├─ retry ──→ 重试(最多 max_retries 次) ──→ 成功 ✓
  │                                       └──→ on_max_retries（降级）
  │
  ├─ pause ──→ 暂停流程，等待人工干预 ──→ 人工继续 / 人工终止
  │
  ├─ abort ──→ 立即终止整个流程
  │
  ├─ skip ───→ 标记为跳过，继续后续节点
  │
  └─ emergency ─→ 执行 action 中的紧急操作 ──→ 流程进入紧急状态
```

strategy 是一个**决策链**——retry 可以降级到 on_max_retries，on_max_retries 可以是 pause / abort / skip / emergency 中的任一个。这允许组合出"先重试 2 次，不行就暂停等人"这样的分级策略。

## 15. 约束总结

### 类型约束（JSON Schema 校验）

| 约束 | 说明 |
|------|------|
| action 节点必须有 target, command, call_mode, timeout | 有指令才需要目标和超时 |
| join 节点只需 id, type, depends | 不执行操作 |
| timed_sequence 节点需 template 或内联定义 | 时序来源 |
| id 格式为 `N` + 2位数字 | 如 N01, N56 |
| timeout 格式为数字 + `s`/`min` | 如 2s, 10min |
| strategy 限定为 retry/pause/abort/skip/emergency | 枚举约束 |

### 引用完整性约束（validate.py 校验）

| 约束 | 说明 |
|------|------|
| depends 中的节点名必须存在于 nodes | 不允许悬空引用 |
| target.system 必须存在于 systems | 不允许引用未注册系统 |
| template 必须存在于 templates | 不允许引用不存在的模板 |
| 所有节点 id 全局唯一 | 不允许重复 |

### 拓扑约束（validate.py 校验）

| 约束 | 说明 |
|------|------|
| DAG 无环 | depends 构成的有向图不允许有环 |
| 全连通 | 从入口节点出发所有节点可达 |
| 终点收敛 | 所有节点都能到达终点节点 |

## 16. 从定义到运行的四层关系

```
DSL 规范层（本文档）
  │  定义了"流程定义中可以出现什么"
  ↓
流程定义层（gxlf-firing-flow.yaml）
  │  是规范的一个具体实例
  │  定义了"这个流程包含哪些节点和依赖"
  ↓
流程实例层（运行时）
  │  是流程定义的一次绑定
  │  绑定了发次编号、束线编号等具体参数
  │  每次发射创建一个流程实例
  ↓
执行层（流程引擎）
     根据流程实例的 DAG 拓扑调度节点
     根据节点定义发送指令、检查状态、处理失败
```

每一层只依赖上一层的定义，不需要知道下一层的细节。
