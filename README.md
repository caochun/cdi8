# CDI8 模型开发与联调工作台

工作台支持 **BPMN / SCXML 模型编辑、校验、版本管理、模拟环境配置和流程联调**。浏览器通过 Vue / Monaco 编辑模型，Java / Flowable 执行 BPMN，独立的设备模拟服务通过真实 Tango 命令和属性事件提供组件行为。

GXLF 四类组件和发射任务作为内置业务示例。示例源文件、执行实现和用户运行数据分别维护。

```text
浏览器工作台
  │ REST
Java 工作台后端：模型版本、环境配置、Flowable 流程、运行历史
  │ HTTP 命令 / NDJSON 设备事件
Python Tango 适配与模拟服务（迁移期间继续使用）
  │ PyTango Command / CHANGE_EVENT
SCXML 组件实例 + 独立 SQLite 命令与事件记录
```

## 目录职责

| 目录 | 职责 |
|---|---|
| [frontend/](frontend/) | Vue / TypeScript 工作台、编辑器、图形预览及公共浏览器资源 |
| [server/](server/) | Spring Boot 后端和 Flowable；按模型、运行、集成和追踪分包 |
| [examples/minimal/](examples/minimal/) | 新建模型使用的最小 BPMN / SCXML，前端直接导入这些 XML |
| [examples/gxlf/](examples/gxlf/) | GXLF 的六个 BPMN 和四个 SCXML 示例模型 |
| [contracts/](contracts/) | 命令、结果、设备事件的接口约定和 JSON Schema |
| [simulator/](simulator/) | 现有 Python 组件运行时、Tango 设备、命令行和测试 |
| [tests/e2e/](tests/e2e/) | 通过真实服务和 Tango 验证完整联调流程 |
| [docs/architecture/](docs/architecture/) | 模块边界、资源归属及后续 Java 模拟器迁移计划 |
| [docs/development/](docs/development/) | 开发工作台操作和模型约定 |
| [docs/domain/gxlf/](docs/domain/gxlf/) | GXLF 状态定义与业务流程说明 |
| `data/` | 本地模型库、配置、数据库和运行事件，不提交 Git |

Java 构建不读取 Python 源码目录。示例从 `examples/` 打包，浏览器资源从 `frontend/` 打包；Python 源码运行也读取相同的权威资源，安装包则携带必要副本。

## 启动

需要 Java 17+、Maven、Node.js 22.12+，以及可安装 PyTango 的 Python 环境。以下命令在仓库根目录执行。

终端一，安装并启动演示模拟服务：

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -e './simulator[tango]'
PYTHONPATH=simulator .venv/bin/python -m gxlf_component_simulator.tango.control_bridge \
  --demo --auto-complete --completion-delay-ms 500
```

终端二，构建和启动工作台：

```bash
mvn clean package
java -jar server/target/workbench-server-0.1.0.jar
```

访问 **http://127.0.0.1:8080/workbench/**。

1. **模型开发**：编辑或导入模型，校验并保存不可变版本；SCXML 可以创建独立试运行。
2. **联调配置**：选择流程及子流程版本、组件模型版本、实例数量和反馈方式，配置设备集合与变量，然后应用模拟环境。
3. **运行调试**：启动流程，查看活动节点、等待条件、命令结果、设备状态及关联事件。

自动反馈只完成演示动作，外部许可和环境条件仍需明确确认。应用环境会创建新一代实例；运行中的流程会阻止环境切换。停止流程终止后续编排，已受理的设备动作仍可能继续。

独立运行总览保留在 `/runtime.html`。详细操作见[工作台开发说明](docs/development/workbench.md)。

## 开发与验证

```bash
# 构建前端、执行 Java 测试并打包；根 POM 统一聚合 server 模块
mvn clean package

# 已有前端构建时只执行 Java 测试
mvn -Dexec.skip=true test

# Python 测试；安装 PyTango 后也执行真实 Tango 集成用例
make simulator-test PYTHON=.venv/bin/python

# 启动 Java 后端，工作目录保持为仓库根目录
make operator

# 只开发前端；API 代理至 8080，运行页使用后端的已构建布局资源
npm run dev --prefix frontend
```

完整 GXLF 发射任务联调需要两个服务和全新的演示状态：

```bash
.venv/bin/python tests/e2e/gxlf_lifecycle_smoke.py
# 两类光源一起参与：添加 --sources seed shg
# 验证工作台固定版本发布（会创建新环境）：添加 --workbench
```

通用工作台发布与双实例执行可用 `python3 tests/e2e/workbench_smoke.py` 验证；它会创建模型并替换演示环境，也必须使用隔离数据。两个脚本均支持 `--base` 和 `--bridge` 指定测试服务地址。

GXLF 脚本显式提供演示反馈和外部条件。演示组件会持久化状态；需要重置时，在模拟服务启动命令中显式添加 `--reset-data`，它只清理所选演示实例的当前版本数据库。更详细的实例配置与手动设备操作见[模拟器说明](simulator/README.md)。

## 运行数据和当前范围

- 工作台数据库默认 `data/control-v2`；模型、版本、联调配置与运行快照保存在 `data/workbench/`。
- Python 演示实例、独立试验和环境实例仍使用模拟器的数据目录，以保留既有本地状态。
- 模型编辑不覆盖 `examples/`；导出 XML 后可经代码审查更新示例源文件。
- 现有 Python SCXML 执行器支持受限子集和项目自定义组件协议；目录重组没有改变其执行语义。
- Java / Apache Commons SCXML 与 JTango 的独立模拟设备是下一阶段迁移项，目前尚未替换 Python 服务。
- GXLF 的启动、完成和持续资格规则集中在后端 `scenarios/gxlf/`；通用执行通过规则接口调用。旧静态运行页后续迁入 Vue。

模块依赖及迁移边界见[架构说明](docs/architecture/workbench.md)，GXLF 验证范围见[示例说明](examples/gxlf/README.md)。
