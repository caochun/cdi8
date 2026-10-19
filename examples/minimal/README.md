# 最小联调示例

- `component.scxml`：带完整组件元数据的最小状态机，支持从 idle 执行 activate，成功进入 active。
- `process.bpmn20.xml`：向 `demoTargets` 集合中的全部实例发送 activate 并等待 active。

工作台前端通过 `?raw` 直接导入这两个文件作为新建模型模板。修改模板只影响之后新建的模型，不改变用户已有草稿、版本或实例。

联调时分别创建两个模型并保存版本，将 SCXML 配置为类型 demo，选择 BPMN 为入口并应用环境；默认的 demoTargets 集合会绑定 demo 类型的全部实例。
