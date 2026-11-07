> 历史资料：保留讨论与设计背景，不作为当前运行实现或验收依据。参见[归档说明](../README.md)。

# NIF ICCS 控制系统方案详解

> 整理自 LLNL 公开论文（UCRL-CONF-235206 等）和 lasers.llnl.gov 官方资料。
> 用于 GXLF 集中管控系统和 Broadview 可视化方案的参考。

NIF（National Ignition Facility，美国国家点火装置）是 192 束激光的 ICF（惯性约束聚变）装置，与 GXLF 同构但规模更大。它的控制系统叫 **ICCS**（Integrated Computer Control System），运行在约 2000 台计算机上，管理 66,000 个控制点，每次打靶执行约 200 万个自动化序列。NIF 的运营经验（每年约 400 发，2022 年实现点火）对 GXLF 有直接参考价值。

---

## 一、三层分布式架构

```
Shot Director（发射总指挥）
  ├─ 26 个 Collaboration Supervisor（协作监督器，每个管 8 束激光 + 共享资源）
  │     └─ 每个下辖 ~10 个 Shot Supervisor（子系统发射监督器）
  │           └─ 控制具体设备（电机、相机、二极管等）
  └─ 共享资源监督器（靶场、诊断、同步等）
```

**与 GXLF 的对应关系**：

| NIF 层级 | GXLF 对应 | 说明 |
|---|---|---|
| Shot Director | 运维任务管控组件 (`gxlf/flow_ctrl/01`) | 总控编排 |
| Collaboration Supervisor | 分系统服务（14 个） | 子系统级协调 |
| Shot Supervisor | 子组件服务 | 设备组级控制 |
| Front-End Processor (FEP) | 设备服务（Tango Device Server） | 单设备控制 |

NIF 多了一层 Collaboration Supervisor，因为 192 束需要按 Quad（4 束）和 Bundle（8 束）分组管理。GXLF 的 61 束规模可以省略这一层，直接由分系统服务管理。

---

## 二、发射编排：Macro Step 工作流引擎

NIF 的核心编排单元叫 **Macro Step**（宏步骤），类似 GXLF 的 DAG 节点：

- **每个 Macro Step 包含**：数据库驱动的验证阶段 + 脚本化的执行阶段
- **工作流定义存在数据库表中**：定义 Macro Step 的执行顺序和依赖关系
- **图形化模型编辑器**（Graphical Model Editor）：用于定义和查看执行模型
- **设备"去参与"**（de-participation）：可以把某束激光、某个诊断设备从本次发射中排除

### 一次发射的生命周期（4-8 小时）

```
Shot Setup
  ├─ 物理模型输入发射目标
  ├─ 激光参数配置
  ├─ 自动光束准直与验证
  ├─ 预备验证发射（低功率）
  ├─ 放大系统发射（全功率）
  │     └─ 最后 4 分钟倒计时，最后 2 秒高精度触发
  ├─ 打靶后数据采集
  └─ 数据归档与分析
```

### 状态机驱动

```
Collaboration Supervisor
   ↓ 翻译 Shot Director 的状态命令
Macro Step 序列（按数据库定义的依赖顺序）
   ↓ 分发到各 Shot Supervisor
Shot Supervisor
   ├─ 维护 Macro Step 顺序
   ├─ 处理失败和重启
   ├─ 支持监督器之间协作
   └─ 支持自动 / 手动执行模式
```

**对 GXLF 的启发**：GXLF 的三阶段 87 步 DAG 已经是同样的设计思路，可以参考 NIF 增加：
- 数据库驱动的工作流定义（而不是 YAML 文件）
- 图形化 DAG 编辑器
- 设备"去参与"机制（用于离线测试和故障设备排除）

---

## 三、可视化界面：Broadview + Control Panel 双层体系

这是 NIF UI 框架最值得 GXLF 参考的部分。NIF 的 GUI 框架（Java + JacORB CORBA）提供两类界面，由 ICCS 团队基于公共组件构建了 **120+ 个 Broadview 和 Control Panel** 在日常使用。

### Broadview（概要视图）

> "live, scalable schematic-like diagrams that graphically depict the high level status and positions of different devices, the laser pathway, current device reservations, and any associated alerts."

特征：
- **交互式原理图**，展示整条束线从种子源到靶室的流程
- 每个节点显示**实时状态**（颜色编码）
- **可点击下钻**到子系统级别，再到设备级别
- 本质是 SVG 风格的 **Synoptic Display**（概要图）
- 用 **ILog JViews**（商业矢量图引擎）渲染交互式原理图
- **连接方式**：Location Connectable（输入位置字符串 → 自动派生 taxon 集合）

### Control Panel（控制面板）

特征：
- 设备级的状态与控制界面
- 从 Broadview 点击某个节点后进入
- 显示具体参数、允许操作员手动干预
- **连接方式**：Taxon Connectable（输入单个设备 taxon → 直接连接）
- 基于 **Composite（复合控件）** 构建，每个 Composite 包含：标签 + 数据显示 + 数据输入

### Composite 控件设计要点

控制系统 UI 的核心挑战：**当前状态 vs 上次请求**。Composite 同时显示：
- 当前位置值
- 上次更新时间
- 上次请求的目标位置
- 请求发起时间
- 有效输入范围（min/max）
- 设备级故障（LED 指示灯）
- 连接级故障（数据是否过期）

每个 Composite 的 LED 灯报告故障，tooltip 显示有效输入范围和最近变更。

### 双层下钻关系

```
Broadview（全局概要图）
  "192束激光链路全景，每束一个色块表示状态"
     │
     │ 点击某个 Bundle
     ▼
Broadview（Bundle 级）
  "8束激光的详细链路图"
     │
     │ 点击某个设备
     ▼
Control Panel（设备级）
  "单台放大器的参数、状态、操作按钮"
```

### 入口：Navigator

所有 UI 交互从 **Navigator** 开始：
- 启动时查询 ICCS 配置数据库
- 展示所有连接点的树形结构
- 通过搜索 / 过滤工具下钻到具体 UI
- 添加新设备只需在配置数据库加一条记录

---

## 四、运行态监控工具

NIF 实际运行中部署了一组配套工具：

| 工具 | 用途 |
|---|---|
| **Shot Clock** | 实时显示发射流程进度（类似倒计时大屏） |
| **Status Board** | 当前发射状态 + 周计划 + 设施扫描信息，部署在各楼层和线上 |
| **Archive Viewer** | 发射后数据查看，支持 Dashboard 和表格两种视图，支持 WebDAV |
| **Configuration Checker** | 验证激光和靶室配置是否匹配实验需求 |
| **Approval Manager** | 审批流程追踪，审批通过后导出到控制系统执行 |
| **Shot Setup Tool (SST)** | 客户端-服务端应用，配置 NIF 实验 |
| **Shot Planner** | 主排程工具，让 RI（Shot Responsible Individual）提前定义实验设置 |
| **LoCoS** | Location, Component, and State 系统，追踪部件和校准数据 |
| **NIF Wiki** | 集中存储 shot logs、分析、性能数据 |

---

## 五、技术栈与软件演进

### 核心技术栈

```
Java 6 + JacORB CORBA          ← UI 与组件通信
ILog JViews                     ← 矢量化交互式原理图
Jidesoft JIDE                   ← 富组件交互
JFreeChart                      ← 动态图表
Swing API + AWT 事件线程        ← UI 渲染
CORBA Any (publish/subscribe)   ← 数据传播
```

### 通信架构

```
UI ←──CORBA──→ Supervisor（数据传播服务器）←──→ FEP（前端处理器）
                    │
                    └─ 发布/订阅模式
                       FEP 只向 Supervisor 发布一次
                       Supervisor 扇出给所有订阅的 UI
```

**关键设计**：
- UI 不轮询 FEP（避免性能问题）
- Supervisor 作为 fan-out 中介，FEP 只向 Supervisor 发布一次
- 数据通过 **CORBA Any** 类型传输（通用容器，支持基本类型和自定义结构）
- 每个 UI 作为 server 接收增量更新，通过 mapper queue 处理

### 软件演进

- **从 Ada 迁移到 Java**：早期用 Ada 95，现代化到 Java
- **从 PowerPC 到 Intel**：硬件升级
- **每年 4 次主要软件发布**：开发周期 6 个月（提前规划）
- **独立测试床环境**：广泛仿真后再合并到生产
- **不停机升级**："替换运行中车辆的每个零件"

### 规模数据

- 约 **400 万行**软件代码
- 每次打靶执行约 **200 万个**自动化序列
- 协调 **66,000 个**控制点（电机、二极管、相机）
- **2,000 个**计算机处理器
- **120+ 个** Broadview 和 Control Panel
- **每年约 400 发**

---

## 六、对 GXLF 的映射建议

### 架构对应

| NIF 概念 | GXLF 对应 | 可视化建议 |
|---|---|---|
| Shot Director | 运维任务管控组件 | 发射 DAG 全局视图 |
| Collaboration Supervisor | 分系统服务 | 分系统状态卡片 |
| Macro Step | DAG 节点（87 步） | 节点状态 + 依赖连线 |
| Broadview | — | 激光链路概要图（SVG 交互） |
| Control Panel | — | 设备详情面板 |
| Shot Clock | — | 发射进度时间轴 |
| Graphical Model Editor | — | DAG 编辑器（可选） |
| Navigator | — | 设备树 / 搜索入口 |

### 双维度可视化思路

NIF 的方案本质上是：
- **空间维度**：用 Broadview 展示激光链路拓扑
- **时间维度**：用 Macro Step 工作流展示发射流程进度
- **统一**：通过状态颜色编码

GXLF 可以直接借鉴：
1. **激光链路 Broadview**（已实现：`archive/docs/broadview/laser-chain.html`）
2. **诊断系统 Broadview**（已实现：`archive/docs/broadview/diagnostics.html`）
3. **三阶段 DAG 工作流视图**（待实现）
4. **设备 Control Panel**（点击下钻，显示 Tango Commands + 实时参数）

### 技术选型建议

NIF 用的是 Java Swing + ILog JViews + CORBA（2000s 的技术栈）。GXLF 现代化做法：

| 用途 | NIF 选型 | GXLF 建议 |
|---|---|---|
| UI 框架 | Java 6 Swing | Web 前端（React / Vue） |
| 矢量图引擎 | ILog JViews | SVG + D3 / React Flow |
| 通信 | JacORB CORBA | Tango (PyTango) + WebSocket |
| 数据库 | Oracle | GBase 8s（已选定） |
| 部署 | 桌面应用 | Web 应用（响应式） |

**优势**：现代 Web 前端比 NIF 当年的 Java Swing 灵活得多，开发体验和最终用户体验都更好。

---

## 七、Broadview 真实样例（基于公开论文 Figure 3）

NIF 的 ISP（Injection System Processor）Broadview 截图特征：

**布局**：
- 上半部分：ISP Diagnostic Side **Front View**（正视图）
- 下半部分：Transport Side **Top Down View**（俯视图）
- 同一段光路用两个物理视角呈现

**信息层次**：
1. **青色虚线** = 激光光路走向（beam path）
2. **光学元件用原理图符号绘制**（反射镜、透镜、快门、分束器、波片等）
3. **实时数值**直接标注在元件旁边，如 `3.85E-2 NOT_AT_SETPOINT`
4. **设备状态**用文字 + 颜色：`LOW_GAIN`、`IDLE`、`UNKNOWN`
5. **颜色编码**：绿色 = 正常，红色 = 故障 / 未知
6. **Shot 标识**贯穿全图：`Shot_N070911-002-999`

**底部状态栏**：`Connected | Automatic Updates | v7.0.0`

GXLF 的 [`archive/docs/broadview/diagnostics.html`](broadview/diagnostics.html) 和 [`archive/docs/broadview/laser-chain.html`](broadview/laser-chain.html) 已采用同样的视觉风格。

---

## 参考资料

- [Control and Information Systems for NIF](https://www.osti.gov/pages/biblio/1251031) — 系统总览
- [User Interface Framework for NIF](https://www.osti.gov/biblio/922313) — UCRL-CONF-235206，Broadview/Control Panel 定义
- [Shot Automation for NIF](https://www.osti.gov/biblio/885137) — 发射自动化框架
- [Orchestrating Shots for NIF](https://www.osti.gov/biblio/878225) — Shot Director 编排
- [NIF Control Systems Overview](https://lasers.llnl.gov/about/keys-to-success/control-systems) — 官方介绍
- [NIF User Guide - Data Handling Tools](https://nifuserguide.llnl.gov/home/8-data-handling/81-user-tools) — 运行态工具
- [NIF Distributed Computer Control System Overview (arXiv)](https://arxiv.org/abs/cs/0111045) — 架构概览
