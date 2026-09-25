# Diagnosis-Engine

面向 CI/CD 交付环境的多项目故障诊断引擎。

一台服务器上同时跑多个项目时，故障排查往往要在 Prometheus、日志平台和运维文档之间手工对齐时间线。本项目用同一套引擎回答两类问题：

- **平台级**：谁在抢占共享 CPU / 内存 / 磁盘，影响了哪些服务
- **项目级**：某个项目的业务链路哪一步坏了

基于 Spring Boot 3 + Spring AI Alibaba。平台级诊断可走原有 Supervisor / Planner / Executor + SSE；项目级诊断走档案路由 + 证据黑板 + 追踪回放。

## 当前完成度

| 能力 | 状态 |
|------|------|
| 平台级诊断（Prometheus + CLS + RAG） | 可端到端演示 |
| 诊断控制台三栏 UI | 已落地 |
| 项目档案注册表与按 `projectId` 路由 | 已落地 |
| 项目级诊断编排（指标 / 日志 / 变更 / 代码线索 / 一跳扩展） | 已落地 |
| 三层记忆 + 证据黑板 + `rawRef` 摘要 + Token 预算置信度 | 已落地 |
| 全链路 Tracing（节点 / 工具 / LLM 事件，按 traceId 回放） | 已落地 |
| Checkpoint 恢复（Chat / AI Ops / 项目诊断） | 已落地（单机原子文件存储） |
| 8 个可撤销故障场景 + 评测跑批 | 已落地（进程内演示遥测） |
| 采纳后候选知识确认发布 | API 已落地 |

演示项目：`card-service`（主角）、`payment-service`（下游）、`order-service`（配角）。`legacy-report-center` 故意缺档案，用来展示「不可诊断」。

## 面试 3 分钟演示

启动应用后打开 http://localhost:9900 进入诊断控制台。

```powershell
# 注入「出卡超时」，再点项目层「一键诊断」
Invoke-RestMethod -Method POST http://localhost:9900/api/faults/card-api-timeout/inject
# 撤销
Invoke-RestMethod -Method POST http://localhost:9900/api/faults/card-api-timeout/revoke

# 8 场景跑批（演示集可加 ?set=demo）
Invoke-RestMethod -Method POST http://localhost:9900/api/faults/eval/batch
```

项目级诊断**不依赖大模型**也能出带证据链、置信度和 `trace_id` 的报告；报告页可复制追踪标识，`GET /api/diagnosis/traces/{traceId}` 回放节点 / 工具 / LLM 事件。

## 技术栈

Java 17 · Spring Boot 3.2 · Spring AI Alibaba · DashScope · Milvus · Prometheus · 腾讯云 CLS（MCP） · 原生 HTML/CSS/JS + Chart.js

## 快速启动

需要：Java 17、Maven、Docker Desktop。完整平台级诊断再加 Node.js、DashScope Key、可选 CLS。

```powershell
git clone https://github.com/costar-opps/Diagnosis-Engine.git
cd Diagnosis-Engine
Copy-Item cls.env.example cls.env
# 编辑 cls.env：至少填写 DASHSCOPE_API_KEY；要用日志证据再填腾讯云 CLS 相关项
# 生产环境另需设置至少 32 字符的 CHECKPOINT_HMAC_SECRET
```

1. `docker compose -f vector-database.yml up -d`
2. `.\scripts\start-prometheus.ps1`
3. `.\scripts\start-mcp.ps1`（窗口不要关）
4. `.\scripts\start-app-with-cls.ps1`（窗口不要关）
5. 浏览器打开 http://localhost:9900 ，进入「诊断控制台」

档案在 `config/projects/*.yml`。主机压力演示用 `.\scripts\inject-host-pressure.ps1 -Mode memory`（走可撤销注入，不打满本机磁盘）。

完整步骤见 [docs/启动手册.md](./docs/启动手册.md)。**密钥不要写进仓库。**

## Checkpoint 与任务恢复

系统为流式 Chat、Supervisor / Planner / Executor AI Ops 和项目级诊断维护版本化 Checkpoint。浏览器首次访问时由服务端签发 `session_id` 和 `session_token`，后续先校验可信会话，再按 `task_id` 定位任务，避免跨会话读取。

- 默认存储：`./runtime/checkpoints/<session_id>/<task_id>.json`
- 提交方式：先写 `.json.tmp`，完成后原子重命名；恢复只读取完整 `.json`
- 恢复策略：跳过 `SUCCEEDED` 工具，按上限重试 `FAILED` 工具，将中断时的 `RUNNING` 标为 `UNKNOWN` 并重新核对只读外部系统
- 兼容检查：恢复前校验 Checkpoint 状态版本、工作流版本；项目诊断还会校验项目档案指纹
- 恢复上下文：仅装配系统规则、核心意图、当前状态、最近相关对话和关键工具结果
- 审计信息：记录恢复来源、次数、跳过/重试/核对步骤及最终结果

配置示例：

```yaml
checkpoint:
  enabled: true
  directory: ./runtime/checkpoints
  hmac-secret: ${CHECKPOINT_HMAC_SECRET:local-development-checkpoint-secret-change-me}
  max-failed-retries: 2
  recent-conversation-pairs: 6
```

本实现面向单机演示，不序列化模型隐藏推理、Token 生成位置、Flux 或 Graph JVM 对象。多实例部署时应将文件存储替换为 Redis、关系数据库或对象存储，并务必覆盖默认开发密钥。

## 仓库结构

```text
Diagnosis-Engine/
├── config/projects/               # 项目档案 YAML
├── docs/
├── openspec/changes/
├── scripts/
└── src/main/java/org/example/
    ├── checkpoint/                # 身份校验 / 原子快照 / 工具状态 / 恢复审计
    ├── diagnosis/                 # 档案 / 记忆 / 追踪 / 故障评测 / 编排
    ├── agent/                     # 工具与进度拦截器
    ├── controller/
    ├── service/
    ├── monitor/
    └── config/
```

## 许可证

Apache License 2.0，见 [LICENSE](./LICENSE)。
