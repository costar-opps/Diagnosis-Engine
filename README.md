# Diagnosis-Engine

面向 CI/CD 交付环境的多项目故障诊断引擎。

一台服务器上同时跑多个项目时，故障排查往往要在 Prometheus、日志平台和运维文档之间手工对齐时间线。本项目用同一套 Multi-Agent 编排回答两类问题：

- **平台级**：谁在抢占共享 CPU / 内存 / 磁盘，影响了哪些服务
- **项目级**：某个项目的业务链路哪一步坏了

基于 Spring Boot 3 + Spring AI Alibaba Agent Framework（Supervisor / Planner / Executor），结论走 SSE 流式输出，并带工具调用进度。

## 当前完成度

面试官或试用者可以按这个范围理解本仓库，避免把规格当成已上线能力。

| 能力 | 状态 |
|------|------|
| 平台级诊断（Prometheus + CLS 日志 + RAG） | 可端到端演示 |
| 诊断控制台三栏 UI（平台 / 项目 / 报告） | 已落地 |
| 工具调用进度（SSE `progress`）与 MCP 会话保活 | 已落地 |
| 项目级诊断 API / 编排 | UI + 占位接口（返回 501） |
| 项目档案路由、三层记忆、全链路 Tracing、故障注入评测 | 仅有 OpenSpec 设计，代码未实现 |

设计文档在 `openspec/changes/add-cicd-multi-project-diagnosis/`，控制台 UI 变更在 `openspec/changes/add-diagnosis-console-ui/`。

## 技术栈

Java 17 · Spring Boot 3.2 · Spring AI Alibaba · DashScope · Milvus · Prometheus · 腾讯云 CLS（MCP） · 原生 HTML/CSS/JS + Chart.js

## 快速启动

需要：Java 17、Maven、Docker Desktop、Node.js（跑 CLS MCP）、阿里云 DashScope Key。CLS 为可选，缺了只能做指标侧诊断。

```powershell
git clone https://github.com/<your-username>/Diagnosis-Engine.git
cd Diagnosis-Engine
Copy-Item cls.env.example cls.env
# 编辑 cls.env：至少填写 DASHSCOPE_API_KEY；要用日志证据再填腾讯云 CLS 相关项
```

然后按顺序启动：

1. `docker compose -f vector-database.yml up -d`（Milvus，端口 19530）
2. `.\scripts\start-prometheus.ps1`（9090）
3. `.\scripts\start-mcp.ps1`（3000，窗口不要关）
4. `.\scripts\start-app-with-cls.ps1`（9900，窗口不要关）
5. 浏览器打开 http://localhost:9900 ，进入「诊断控制台」

完整步骤与关停方式见 [docs/启动手册.md](./docs/启动手册.md)、[docs/项目启停指南.md](./docs/项目启停指南.md)。

**密钥不要写进仓库。** `application.yml` 只读环境变量；把真实值放在已忽略的 `cls.env` 里。

## 仓库结构

```text
Diagnosis-Engine/
├── docs/                          # 启动 / 启停
├── openspec/changes/              # 已落地 UI 与未落地多项目方案
├── scripts/                       # Windows 启动脚本
├── monitoring/                    # Prometheus 配置（不含本地二进制）
├── vector-database.yml            # 本地 Milvus
└── src/main/java/org/example/
    ├── agent/                     # 工具与进度拦截器
    ├── controller/                # 对话 + 诊断控制台 API
    ├── service/                   # AI Ops / Chat / 向量检索
    ├── monitor/                   # 平台层指标
    └── config/                    # MCP 装饰器、会话保活、环境变量加载
```

## 许可证

Apache License 2.0，见 [LICENSE](./LICENSE)。
