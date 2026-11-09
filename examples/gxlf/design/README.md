# 实验发次设计模型

本目录保存不可执行的 BPMN 设计契约，不能直接作为 Flowable 运行入口。保留一份目标契约，与可执行验证切片分别维护：

- [gxlf_shot_run_target.bpmn20.xml](gxlf_shot_run_target.bpmn20.xml)：当前目标流程契约，按“发次建立与资源准入 → 设施准备 → 安全准备 → 光路与靶瞄准备 → 充电与触发 → 发射结果确认 → 后处理与安全退出 → 归档与资源释放”组织完整运行系统；明确阶段入口操作准入、完成事实、保持条件、设备动作边界、系统监督异常和未决外部接口。
- [../processes/gxlf_shot_run_smoke.bpmn20.xml](../processes/gxlf_shot_run_smoke.bpmn20.xml)：当前可执行回归切片，覆盖上电检查、功能检查和参数接收。

模型复用：

- [设施系统定义](../system/gxlf-facility.system-definition.xml)：设备、端口、拓扑、能力依赖、结构约束和设计说明；
- [设施实例绑定](../system/gxlf-facility-binding.xml)：运行实例地址和束线映射引用；
- [设施状态投影](../system/gxlf-facility-state-projection.xml)：设施观测和事实；操作准入位于系统定义，由 facility-server 计算；
- [发次运行模型](../../../docs/interfaces/shot-run.schema.json)：本发阶段和证据记录。

扩展语义：

- `fac:operation`：任务进入前由 facility-server 计算的设施操作准入；
- `fac:requires`：旧流程迁移用的 guard 入口；
- `fac:completion`：本次动作发出后等待的事实或 guard，不等同于历史证据；
- `fac:hold`：阶段运行期间持续保持的输入和事实条件，失效后进入中断异常路径；
- `fac:actions` / `fac:capability`：设备动作或外部能力边界；
- `run:stage` / `run:records`：本发阶段和证据归档边界。

目标流程中的 `contract-unresolved`、`external-interface-required` 和 `manual-confirmation-required` 是明确的设计缺口，不得通过占位表达式伪装成可执行成功。系统安全、联锁、服务健康和储能约束属于系统固有义务，不能因为某个 BPMN 阶段结束而自动释放。

前一版业务主干已移到[历史归档](../../../archive/README.md)，不再作为运行模型或验证依赖。
