# 文档导航

- [项目入口与启动](../README.md)：目录、构建、服务和验证命令。
- [架构与迁移边界](architecture/workbench.md)：工作台、模拟服务、示例源文件及运行数据的职责。
- [工作台开发与联调](development/workbench.md)：模型版本、独立试验、环境配置、条件声明和调试接口。
- [GXLF 示例](../examples/gxlf/README.md)：当前四类组件与发射任务的验证范围。
- [GXLF 状态定义](domain/gxlf/component-states/README.md)：分系统和组件状态来源。
- [GXLF 业务流程](domain/gxlf/process-control/)：前期准备、正式发射和发射后处理说明。
- [设备接口契约](../contracts/README.md)：命令、结果及 NDJSON 事件格式。

GXLF 的可执行模型集中维护在 `examples/gxlf/`；业务文档描述的完整需求不等于当前示例已覆盖的执行范围。工作台用户模型及历史保存在 `data/`，不写回示例源文件。
