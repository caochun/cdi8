# GXLF 实验流程集中控制系统

根项目为 Spring Boot + Flowable 应用，真正部署并执行 BPMN，覆盖 14 个动作节点、种子源三实例、二倍频单实例、联合门禁、结果等待、错误/定时器分支，以及联锁后的人工补偿。
流程不再调用 Python `JointFanoutSequentialExperiment`。Python 只作为分系统仿真和 Tango 网关。

## 仓库结构

```text
cdi8/
├── pom.xml                     # 根 Maven 项目
├── src/main/java/              # Spring Boot + Flowable 后端
├── src/main/resources/         # 配置、可执行 BPMN、SQL、操作页面
├── src/test/java/              # Java 引擎集成测试
├── simulator/
│   ├── pyproject.toml          # 独立 Python 仿真包
│   ├── README.md
│   └── gxlf_sim_system/        # PyTango 服务、分系统模型、测试及对照实现
├── scripts/                    # 跨模块联调脚本
├── docs/                       # 架构、图示、进度与参考资料
├── Makefile
└── README.md
```

主应用在根目录运行 `mvn test`、`mvn spring-boot:run`。Python 模块使用独立的包配置，
详见 [仿真模块说明](simulator/README.md)。原 Python 流程执行器及操作台保留为对照实现，
不参与 Java 主应用的流程调度。

[实施进度](docs/implementation-plan-progress.md) · [BPMN 设计说明](docs/laser-joint-fanout-bpmn.md) ·
[模型验证计划](docs/model-correctness-verification.md)

## 架构

```text
浏览器 / REST API
      ↓
Spring Boot + Flowable + H2
      ├─ BPMN 调度、并行多实例、接收任务、定时器、用户任务和历史
      ├─ 数据库命令 outbox / 设备结果 inbox
      └─ DeviceAdapter（HTTP）
             ↓
Python Tango 网关（协议适配，无流程编排）
             ↓ DeviceProxy：Command / Attribute
PyTango SubsystemDevice ×4
      └─ 原有 YAML 状态机 + 每实例 SQLite 持久化
```

第一版采用 Python 网关接入 Tango，以复用 PyTango 和分系统状态机。Java 没有直接链接 JTango；
`DeviceAdapter` 可替换为未来的 Java Tango 客户端实现。网关有两个显式模式：

- `--mode local`：不经过 Tango，直接调用相同设备仿真行为，用于轻量开发。
- `--mode tango`：真正调用 PyTango DeviceProxy。默认用 MultiDeviceTestContext 启动无数据库的开发 Device Server；这不是现场 Tango DB 部署。

## 启动

Java 编译目标 17，Maven 3.9+。依赖固定为 Flowable 7.1.0 / Spring Boot 3.3.4（该 Flowable 版本的兼容基线）。
本次在 Java 27 上完成编译、启动和集成测试，部署前应在目标 JDK 环境再次验证。

终端一，在仓库根目录：

```bash
python3 -m pip install -e './simulator[tango]'
python3 -m gxlf_sim_system.tango.gateway --mode tango --port 8766
```

没有 PyTango 时可先用 `--mode local`，但不能将该模式的通过结果当作 Tango 通信验证。

终端二：

```bash
mvn test package
java -jar target/control-server-0.1.0.jar
```

访问 **http://127.0.0.1:8080**。这套页面由 Spring Boot 提供，实际驱动 Flowable。
原 Python 操作台 `http://127.0.0.1:8765` 仍是旧执行器的对照演示，不会自动切换到 Flowable。

网关地址可以修改：

```bash
DEVICE_ADAPTER_URL=http://127.0.0.1:8767 java -jar target/control-server-0.1.0.jar
```

默认 H2 文件在启动目录的 `data/control`；设备 SQLite 默认在仓库 `simulator/gxlf_sim_system/output/devices/`。
重启使用相同路径可恢复已有任务与结果。不要在未结束的流程中切换设备网关或清空设备数据库。

接入已有 Tango Server 时，创建逻辑 ID 到 Device 地址的 JSON：

```json
{
  "seed_01": "gxlf/seed/01",
  "seed_02": "gxlf/seed/02",
  "seed_03": "gxlf/seed/03",
  "shg_01": "gxlf/shg/01"
}
```

```bash
python3 -m gxlf_sim_system.tango.gateway --mode tango --tango-devices devices.json
```

已有服务需实现下述契约。设备数据库注册、权限、地址与属性配置由部署环境提供；可用
`python3 -m gxlf_sim_system.tango.device <server-instance>` 启动注册好的仿真 Device Server。

## 关键文件

| 文件 | 职责 |
|---|---|
| `src/main/resources/processes/laser-joint.bpmn20.xml` | 已可部署的 Flowable BPMN；现在是 Java 侧唯一流程编排来源 |
| `Devices.java` | 分系统前置校验、入队、反馈验证、联合门禁及结果记录 |
| `CommandWorker.java` | outbox 发送、查询结果、关联唤醒、取消确认 |
| `RunService.java` | 实验启动幂等、设备占用、联锁、人工补偿和运行查询 |
| `ControlApi.java` | REST 接口 |
| `simulator/gxlf_sim_system/tango/device.py` | 实际 PyTango Device Server 类 |
| `simulator/gxlf_sim_system/tango/runtime.py` | 持久化仿真状态与幂等设备命令 |
| `simulator/gxlf_sim_system/tango/gateway.py` | HTTP 到 Tango 适配；不编排实验 |

`docs/bpmn/` 目录里的 `.bpmn` 仍为引擎无关设计稿；运行时加载的是本模块 `.bpmn20.xml`。
运行版现在也包含 BPMN DI：主图及各层子流程的节点坐标、边界事件位置和连线路径。
可直接将 `src/main/resources/processes/laser-joint.bpmn20.xml` 导入 bpmn.io 查看，并进入“完整实验周期”或“联锁与人工补偿”子流程。
[运行版主图预览](docs/bpmn/laser-joint-runtime-overview.svg)。bpmn.io 用于查看/建模，不执行 Flowable 的 Java 表达式。
之前版本只包含执行定义，bpmn-js 会报 `no diagram to display`；该报错是缺少布局，不是 Flowable 无法解析流程。
旧 Python YAML 保留为对照基线，Java 不运行时解析它。两套流程定义的后续变更需要同步评审，不能视为自动同步。

## 执行与持久化语义

1. BPMN 的发送服务任务只在与 Flowable 相同的数据库事务中写 `device_command`，随后进入 receiveTask。
2. 后台 worker 从已提交的 outbox 发送命令，command ID 在重试时不变。
3. Device 的 Execute 按 command ID 去重，返回 accepted 或 rejected；accepted 不表示动作完成。
4. 分系统独立产生最终结果，保存后由网关查询。Java worker 保存结果，并唤醒数据库记录的对应 execution。
5. `verify` 核对命令、动作、任务结果、主状态、当前状态和业务状态；实例失败抛出 BPMN Error。
6. 并行多实例必须全部成功。边界错误终止整组，取消请求进入 outbox 等待设备确认，旧反馈不会重新推进已经结束的节点。

首版使用 **GetResult 轮询**，并未在 Java 侧接 Tango Event 订阅。Device 的 Snapshot CHANGE_EVENT 已实现并实测，
后续可用于加速反馈；持久化 GetResult 仍可作为漏事件和重启后的恢复来源。

Flowable 并行多实例表示任务生命周期并行；本版 worker 顺序发送短命令，设备接受后异步等待，不保证多个 Command 同时抵达设备。
当前只允许一组物理绑定被一个活动实验占用，H2 的 lease 行锁防止两个实验同时启动。

## API 与操作

- `POST /api/runs`，`{"requestId":"run-001"}`：创建实验，重复相同编号返回同一实例。
- `GET /api/runs/run-001`：运行结果、活动任务、设备命令错误、人工任务及 Flowable 活动历史。
- `POST /api/runs/run-001/commands/<commandId>/simulate`，`{"outcome":"success"}`：在设备端注入模拟反馈。
- `outcome` 也支持 `failure`、`communication_error`、`fault_lock`。
- `POST /api/runs/run-001/interlock`：向该流程发送关联的 Interlock 消息，中断当前执行，保留 FAILED 结果。
- `POST /api/runs/run-001/compensate`：完成人工审批任务，按 seed_01/02/03、shg_01 顺序执行 abort_reset。

补偿审批前必须等待取消请求全部确认；审批不是补偿成功，每个复位动作还需要自己的设备结果。
补偿失败后重新进入人工审批，当前策略是重跑整条链；补偿成功不把实验改回 SUCCEEDED。
普通失败直接结束流程，不自动补偿。流程结束后的现场联锁应由独立安全管理处理，不能向已结束的 BPMN 实例发消息。

Device 契约：

| Tango 接口 | 输入 / 输出 |
|---|---|
| Execute | JSON `{command_id, action}` → 关联结果及 accepted/rejected 状态 |
| GetResult | command ID → 持久化结果 `{command_id, action, status, snapshot}` |
| Cancel | command ID → 幂等取消结果；先取消后迟到的发送也会被拒绝执行 |
| Simulate | JSON `{command_id, outcome}` → 仿真结果；真实设备服务不应暴露该测试命令 |
| Snapshot | JSON 字符串属性及 CHANGE_EVENT，包含分系统状态和当前任务 |

Tango DevString JSON 使用 ASCII 转义，避免中文状态在底层字符串编码中丢失；HTTP JSON 解码后仍显示中文。

## 验证

```bash
make control-test                      # 6 项 Flowable + H2 测试，1 项 BPMN 布局完整性测试
make simulator-test                    # Python 回归；无 PyTango 时仅协议测试跳过
make test                              # Java 与 Python 全部测试
python3 -m unittest gxlf_sim_system.tests.test_pytango_device -v
python3 scripts/flowable_smoke.py        # 运行中的 Java + 网关，全周期联调
```

本次已验证：

- 14 个 BPMN 动作节点，28 次实例命令，全部设备关机后才 SUCCEEDED；
- 多实例等待、重复启动、设备互斥、任一实例失败、Flowable 定时器和状态失效门禁；
- 真实 Tango Command/Attribute/CHANGE_EVENT；
- 实际 Java 重启：参数节点等待期间停机，设备在 Java 离线时完成，重启后续跑至成功，无重复实例命令；
- 真实 Tango 联锁→取消确认→人工审批→四次串行补偿，最终实验仍 FAILED；
- 浏览器逐实例反馈跑通完整周期。

Java 集成测试使用独立 FakeAdapter 验证引擎行为；真实协议及重启验证是额外联调结果，不能互相替代。

## 当前边界

这是首个可运行的迁移版本，不是生产部署完成：

- HTTP 服务与设备网关均监听 loopback；尚无多用户认证、分级权限、TLS 或网络故障下的 HA 部署。
- `SIMULATION_CONTROLS=false` 可关闭 Java 仿真入口，但真实网关也必须禁用/移除 Simulate。
- 真正的硬安全联锁仍须在设备/专用控制层执行；取消软件任务不能证明物理设备已关光。
- BPMN 定时器存在调度延迟，超时与成功同时发生时由事务先后决定；尚未实现硬截止时间优先的仲裁。
- 补偿暂未配置独立的超时和失败实例增量重试；定时参数是仿真值。
- 设备复位后业务状态是未就绪；再次实验前需按业务规则关机再自检，程序不会偷偷重置真实设备状态。
- 当前叶子状态规则在 Java 适配校验中有明确映射，尚未做自动生成或完整 complete/sound 证明。
- 旧 Python 操作台的 JSONL 回放未迁移；Java 当前提供 Flowable 持久化活动历史。
