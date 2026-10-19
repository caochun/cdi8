# 工作台模块与资源边界

项目定位是支持模型编辑、版本管理、模拟环境配置和流程联调的开发工作台。GXLF 是内置示例，不定义平台可使用的全部设备类型或流程。

## 当前模块

- `frontend/`：所有浏览器代码和第三方绘图资源。Vue 构建产物进入根目录 `target/workbench-static/workbench/`，未打包的运行页与 vendor 资源位于 `frontend/public/`，由 server 打包到静态资源根路径。根首页跳转仍由 server 提供。
- `server/`：Java Maven 模块，产物为 `server/target/workbench-server-0.1.0.jar`。根 POM 聚合模块并统一 Java / Flowable 版本；`mvn -pl server spring-boot:run` 从仓库根目录启动进程，继续使用原有 `data/`。
- `simulator/`：过渡期间的 Python 实现，保留命令行、PyTango 设备、桥接与工作台模拟接口。Java 后端不依赖其包内文件布局。
- `examples/`：示例模型唯一的版本化源文件。`minimal/` 直接供前端新建模型使用；`gxlf/bpmn/` 和 `gxlf/scxml/` 供 Java 内置模型导入与 Python 示例运行使用。
- `contracts/`：跨运行时的命令、结果与事件格式。协议格式不绑定 Python 类或 Java DTO。
- `tests/e2e/`：跨进程联调验证；各模块自己的单元与集成测试保留在模块内。

后端目前按职责分包：

| 包 | 职责 |
|---|---|
| `api` | REST 及页面入口 |
| `model` | 模型存储、校验、草稿编辑和版本发布 |
| `application` | 对 API 保持稳定的工作台门面 |
| `environment` | 联调配置存取、环境替换及空闲约束 |
| `run` | 固定版本的运行部署、Flowable 等待、命令推进及结果查询 |
| `integration.flowable` | 自定义组件命令活动和 BPMN 解析适配 |
| `integration.tango` | 设备服务 HTTP 客户端及事件连接 |
| `trace` | 按运行保存设备事件 |

`WorkbenchService` 保持 API 门面，委托 `ModelService`、`EnvironmentService` 和 `RunDeploymentService`。环境替换与发布共享环境锁，保留运行时禁止切换环境的约束。当前分包未增加新的部署服务。

## 模型源文件与运行数据

```text
examples/ 中受 Git 管理的模型
    ↓ 构建时打包、首次启动导入
工作台草稿与不可变版本（data/workbench）
    ↓ 选择版本、发布、应用环境
运行部署及模拟实例持有的模型快照
    ↓
命令、设备事件及运行历史
```

GXLF 模型在 Java classpath 中继续使用 `processes/`、`component-models/` 路径，保持 Flowable 自动部署及现有 API 行为。该 classpath 布局是构建产物，不是模型的源码存放位置。

Python 在源码环境直接解析 `examples/gxlf/scxml/`。wheel 和 sdist 构建会将模型复制到安装包内部；这些副本由构建生成，不作为第二份模型源码维护。安装包必须能够脱离仓库加载四类模型。浏览器绘图资源仅由 `frontend/public/vendor/schviz/` 维护和分发，Python 模拟服务不依赖它们。

用户草稿、版本、环境、实例数据库和日志不随目录重组自动迁移或重置。

## 后续迁移

1. 隔离 GXLF 专用资格检查和阶段完成判据，通用运行层通过明确的业务入口使用它们。
2. 新增独立的 `device-simulator/` Java 模块；先以 Apache Commons SCXML 和 JTango 实现种子源，复用既有命令、结果与事件协议。
3. 对照 Python 实现验证动作反馈、幂等、实例隔离、故障恢复、断连与补读，再迁移其余组件和环境管理。
4. 将 `frontend/public/runtime.html` 与对应脚本逐步迁入 Vue 的运行功能区，保持模型和历史显示行为一致。
5. 在覆盖及迁移完成后清理 Python 服务和旧静态运行页。

该迁移保留真实 Tango 协议边界。引擎测试与 Tango 联调分别验证；直接调用 Java 对象不替代协议测试。暂不为每个概念建立独立 Maven 模块，也不新增没有实际使用者的公共框架。
