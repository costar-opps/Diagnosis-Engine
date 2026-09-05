## Why

当前系统只能诊断"本机 CPU / 内存 / 磁盘"这一类主机指标，所有日志、知识文档、告警都混在同一个全局命名空间里，无法回答真正的排查问题："CI/CD 服务器上 card-service 的出卡成功率跌了，是它自己的代码问题，还是同机的其他项目把内存吃满了？"

现实中一台 CI/CD 交付服务器同时运行多个项目，每个项目有自己的日志源、业务指标、运维文档和代码仓。人工排查要在多个系统间反复跳转、手工对齐时间线，耗时且依赖个人经验。本变更把系统从"单机指标问答"重构为"多项目 AI 诊断平台"：一套 Agent 引擎，通过项目档案切换数据面与认知面，输出带证据链的诊断报告。

## What Changes

- **新增项目档案（ProjectProfile）注册表**：以机器可读配置声明每个被监控项目的数据源、知识命名空间、业务语义、依赖关系；平台启动时加载并校验，运行时按 `projectId` 装载。

- **新增两种诊断形态**：
  - 平台级诊断（`PLATFORM`）：面向共享资源，回答"谁在抢资源、影响哪些项目"。
  - 项目级诊断（`PROJECT`）：面向单个项目业务链路，回答"业务哪一步坏了、为什么坏"。
  - 两种形态复用同一套 Supervisor / Planner / Executor 编排，仅在入口上下文、Prompt 变量、报告模板上分叉。

- **工具层从"全局单数据源"改为"按项目路由"**：`QueryLogsTools`、`QueryMetricsTools` 接收 `projectId`，根据档案解析出真实日志源定位符、字段映射与业务 PromQL；未注册项目直接拒绝而非回退到全局查询。**BREAKING**：现有工具签名与调用约定变更。

- **知识检索按项目隔离**：Milvus 文档写入与检索加上 `project_id`、`scope`（`project` / `platform`）元数据，项目级诊断同时取项目专属与平台通用文档；跨项目检索需显式开启。**BREAKING**：现有向量集合需重建或补写元数据。

- **新增结构化诊断报告**：统一输出故障概览、多源对齐时间线、分级证据链（指标 / 日志 / 变更 / 代码线索）、带置信度的根因判断、可溯源的处置建议，并支持人工采纳 / 驳回反馈。

- **新增跨项目一跳扩展查询**：当项目级诊断识别出下游依赖类故障时，依据档案中的依赖声明扩展查询下游项目，并在报告中显式标注扩展范围。

- **新增可复现故障场景库**：定义 8 个覆盖平台资源、项目业务、变更回归、静默故障、跨项目依赖与资源争用的注入场景，作为演示脚本与后续评测的共同基线。

- **新增取证驾驭层（Evidence Harness）**：以证据黑板作为单次诊断工作记忆，三层记忆只定义可见边界；工具返回在服务端按溯源契约摘要后再进窗；Token 预算同时约束上下文并压低不完整证据的置信度；历史结论只能经采纳晋升为知识线索，不得替代本次取证。

- **新增 Agent 执行可观测**：为每次诊断分配追踪标识，记录节点流转、LLM 调用的 Token 与耗时、工具调用的参数与成败，支持按标识回放定位；将诊断耗时、工具调用次数、Token 消耗、失败率等运行指标对外暴露。

- **新增知识自进化闭环**：人工采纳的诊断结论经结构化生成候选知识，确认后按归属命名空间回写知识库，成为后续同类故障的检索依据；驳回原因作为诊断质量的改进输入。

## Capabilities

### New Capabilities

- `project-registry`: 项目档案的结构定义、加载校验、运行时装载与项目列表查询
- `diagnosis-modes`: 平台级与项目级两种诊断形态的入口、分流判定与上下文装配
- `scoped-data-access`: 指标、日志、变更、代码线索等数据源按项目档案路由与隔离
- `scoped-knowledge-retrieval`: 知识文档按项目命名空间与平台通用范围的写入与检索
- `diagnosis-report`: 诊断报告的结构、证据链约束、置信度标注与人工反馈
- `fault-scenarios`: 可复现故障注入场景库及其期望诊断结论
- `context-memory`: 取证驾驭层：证据黑板、形态防火墙、溯源摘要、Token 预算驱动置信度、记忆分层边界
- `agent-observability`: 诊断执行轨迹的记录、回放定位与运行指标暴露
- `knowledge-evolution`: 人工反馈到可复用知识的结构化沉淀闭环

### Modified Capabilities

（无。`openspec/specs/` 当前为空，本变更引入的均为新能力。）

## Impact

**受影响代码**

- `org.example.agent.tool.QueryLogsTools` / `QueryMetricsTools`：新增项目维度参数与路由逻辑
- `org.example.agent.tool.InternalDocsTools`：检索增加项目与范围过滤
- `org.example.service.AiOpsService`：Supervisor / Planner / Executor 提示词改为模板 + 档案变量注入，增加诊断形态分流
- `org.example.controller.ChatController`：诊断入口增加 `projectId` 与 `mode`
- `org.example.monitor.AiOpsDashboardService` / `AiOpsDashboardData`：仪表盘拆分为平台层与项目层
- `org.example.client.MilvusClientFactory` 与文档写入链路：集合 schema 增加 `project_id`、`scope` 字段，并支持采纳结论的结构化回写
- `src/main/resources/static/aiops-monitor.js`：前端增加项目切换器、两种报告视图与候选知识确认入口
- 新增取证驾驭层：证据黑板、工具返回溯源摘要、Token 预算与置信度联动、记忆分层与晋升约束
- 新增执行轨迹记录模块：在 Graph 节点、LLM 调用与工具调用三处埋点，并向 Micrometer 暴露运行指标

**受影响配置与数据**

- `application.yml`：新增项目注册表位置、诊断形态默认值、跨项目扩展开关
- Milvus 集合：需要重建或数据迁移以补齐元数据字段
- `monitoring/prometheus/alerts.yml`：告警需带项目标签以便归属

**新增依赖与产物**

- 演示用被监控项目（建议 2～3 个轻量服务）与故障注入脚本
- 故障场景基线数据集，供后续评测使用
