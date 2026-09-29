# 动作契约、实例目录和流程绑定

本轮保留两个分系统和完整 14 节点流程，将 Java 里的业务条件与设备常量提取为三个配置文件。
它们与 BPMN 分工，而不是新增另一套流程执行器。

## 文件与职责

| 文件（相对仓库根目录） | 定义什么 |
|---|---|
| `src/main/resources/processes/laser-joint.bpmn20.xml` | 顺序、并行多实例、等待点、超时、错误路径、人工补偿 |
| `src/main/resources/control-model/action-contracts.yaml` | 分系统对外动作契约：类型、逻辑动作、参数、前置条件和成功结果 |
| `src/main/resources/control-model/device-catalog.yaml` | 逻辑实例、分系统类型、适配器 ID 和实例分组 |
| `src/main/resources/control-model/workflow-bindings.yaml` | BPMN 节点引用哪些契约与分组、默认参数、跨系统门禁、流程变量来源 |
| `simulator/gxlf_sim_system/models/excel-*-state-machine.yaml` | 分系统内部仿真行为：接受动作后如何转移状态、失败、异常与恢复 |

`workflow-bindings.yaml` 不定义节点顺序；节点顺序仍只在 BPMN 中。动作契约不会替设备计算物理结果，
它描述集中控制要求设备满足的对外条件。

## 用 seed_emit 走一遍

BPMN 调用改成：

```text
devices.prepare(execution, 'seed_emit')
    → 并行多实例
      devices.enqueue(execution, 'seed_emit', deviceId)
      receiveTask 等待本实例结果
      devices.verify(execution, 'seed_emit')
```

节点绑定为：

```yaml
seed_emit:
  group: seed
  contracts:
    seed: seed.seed_source_emit
  parameters: {}
  guard: laser_ready
```

解释：

1. `group: seed` 从实例目录中得到 seed_01、seed_02、seed_03。
2. 每个设备的 `system_type: seed` 选择 `seed.seed_source_emit` 契约。
3. `prepare` 逐实例检查契约前置条件，再检查流程级 `laser_ready`。
4. `enqueue` 将契约中的 `action: seed_source_emit`、本次参数、适配器 ID 和契约快照一起保存为 outbox 命令。
5. worker 经 HTTP/Tango 适配层下发，DeviceRuntime 接受或拒绝动作，并独立维护设备状态。
6. 最终反馈匹配 command ID、运行、节点和动作后，`verify` 用已保存的契约检查完成状态。
7. 每个实例正常完成，Flowable 的多实例活动才全部成功；一个实例失败仍走原来的 BPMN 错误路径。

出光契约的关键条件如下（完整定义见 YAML）：

```yaml
system_type: seed
action: seed_source_emit
precondition:
  all:
    - {field: main_state, in: [正常]}
    - {field: current_state, in: [参数下发完成]}
    - {field: active_action, equals: null}
completion:
  all:
    - {field: main_state, equals: 正常}
    - {field: current_state, equals: 出光完成}
    - {field: business_state, equals: 出光}
    - {field: task_state, equals: succeeded}
    - {field: active_action, equals: null}
```

为保持原行为，局部前置条件按现有状态模型及 Java 的空闲要求迁移；流程门禁仍额外要求业务“就绪”。
联锁后的 `abort_reset` 使用同一套契约选择机制，由 compensation 节点按设备类型选择对应复位契约。

## 局部条件和联合条件分别表达

- 局部契约回答“这一个设备是否允许该动作，以及其成功结果应是什么”。
- `laser_ready` 回答“全部种子实例 AND 二倍频实例现在是否都准备好”。
- `shg_emit_allowed` 回答“种子源仍处于正常出光，二倍频仍处于参数准备完成”。
- `shutdown_all` 回答“所有选定实例是否关机完成且业务未就绪”。

条件解释器只支持 `all`、`any`、`equals` 和 `in`。流程级叶子条件增加 `group` 和 `quantifier: all/any`。
字段缺失不等于显式 null；组不能为空；未知字段、运算符或值域错误会在启动时拒绝。
配置不支持任意脚本、EL 表达式或 YAML 锚点复用。连续保持条件、状态新鲜度和外部事件仲裁尚未在本 DSL 实现，
不能把一次门禁检查理解为条件持续成立。

## 实例分组与运行冻结

`device-catalog.yaml` 的 `adapter_id` 是 HTTP/Tango 网关接受的逻辑设备 ID。
实际 Tango Device 地址仍由网关的 `--tango-devices` 文件映射，Java 不在本轮直接使用 JTango。

流程变量由 workflow 配置生成：

- seedInstances：seed 组列表，供种子源并行多实例使用；
- shgInstance：shg 组的唯一实例，要求该组恰好一个设备；
- allInstances：all 组列表，供串行补偿使用，列表顺序有意义。

例如把种子实例从三个调整为两个，修改实例目录及相关分组即可；BPMN 多实例结构和通用 Java 服务不用修改。
适配层仍必须提供被选中的设备。此轮不实现运行中动态增减实例。

每次启动实验把三个配置以及本次参数写入 `experiment_run.model_snapshot`，并记录规范化 JSON 的 SHA-256。
已有实验读取自己的数据库快照，不读取启动后更新的配置；每条设备命令还保存 `contract_ref`、`contract_json`、
`params_json` 和 `adapter_id`。数组顺序保留，JSON 对象键顺序不影响指纹。
`GET /api/model/checks` 的指纹是当前基础配置，运行的 MODEL_HASH 还包含本次参数，二者不要求相同。

## 参数通路

启动接口兼容不带参数的旧请求；新增按节点传参：

```json
{
  "requestId": "recipe-example-001",
  "parameters": {
    "seed_configure": {"recipe_id": "seed-recipe-42"},
    "shg_configure": {"recipe_id": "shg-recipe-17"}
  }
}
```

参数默认值来自节点绑定，由本次输入覆盖。校验在创建实验前执行；未知参数、类型不符或缺少必填参数均拒绝。
同一 requestId 不能改用另一份参数。设备端同一 command ID 也不能更换动作或参数。
recipe_id 当前是**演示用的可选字符串字段**，不宣称来自 Excel 或真实设备接口；仿真器持久化并回传它，
不会据此计算真实激光输出。类型校验目前不包含嵌套对象结构、范围或跨字段约束。

## 启动校验和正常路径检查

应用启动时执行：

1. 检查三个配置的结构、字段值域、实例分组、参数模式和契约适用类型。
2. 检查 BPMN 中的节点引用、条件引用、动作所属作用域及多实例/单实例变量的目标分组。
3. 从配置初态出发，沿当前 BPMN normal_cycle 的正常路径传播契约成功状态，检查后续前置条件、联合门禁和结束条件。
4. 不合法配置或确定冲突会阻止启动，异常信息包含相关节点或字段。

查看通过校验的配置报告：

```bash
curl http://127.0.0.1:8080/api/model/checks
```

报告包含正常路径的 PASS，以及整体形式验证仍为 UNDEFINED 的说明。
本检查器针对当前应用支持的 BPMN 结构，不是通用 BPMN 模型检查器；未探索外部事件、定时竞态、全部异常/补偿路径。
OR/in 等无法得到唯一后态的情况报告 UNDEFINED，不猜测结果。

测试还独立读取两份仿真状态 YAML，对照契约前置状态组合和成功状态。这能发现配置不一致，
但不能证明契约、状态机与 Excel 业务语义同时正确；complete/sound 验证仍需要独立参考语义。

## 使用与迁移

默认加载 classpath 下 `control-model/` 的三个文件。可在部署时指定外部目录：

```bash
java -jar target/control-server-0.1.0.jar --control.model-directory=file:/absolute/path/to/control-model/
```

目录需包含完整的三个文件；修改后重启应用生效，新实验使用新版本，已有具备快照的实验继续使用原版本。
数据库变更为新增列，历史数据保留。但旧版本尚未完成的 BPMN 实例引用旧 Java 方法签名且没有契约快照，
本轮没有做这类实例的热迁移；升级前应使用旧版完成活动实验，或另行制定迁移方案。不能自动用新配置解释旧实例。

本轮保留原节点 ID、14 个动作的正常顺序、并行多实例、超时配置、异常处理及人工补偿。
只调整动作/条件引用和运行时解释方式；没有引入第三个分系统，也没有改变硬安全联锁的职责边界。
