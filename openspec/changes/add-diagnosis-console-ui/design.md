## Context

动机见 `proposal.md - Why`。这里只记录影响实现方式的现状与约束。

**现有前端结构**

- 无构建工具、无框架。`index.html` 直接引入 `app.js` 与 `aiops-monitor.js`，两者通过 `Object.assign(SuperBizAgentApp.prototype, {...})` 共享同一个类。
- 视图切换已存在：`switchMainView(view)` 在 `#chatView` 与 `#aiopsMonitorView` 之间切 `active` class。侧栏「AI Ops 监控」按钮已绑定该方法。
- 监控页已有完整的渲染链路：`renderAiOpsMonitorPage` → `renderDashboardIntoContainer` → `renderAlertCards` / `renderGaugeCards` / `renderChartsInContainer`（Chart.js）。
- 数据来源单一：`fetchAiOpsDashboard(rangeMinutes)` 调 `GET /api/monitoring/aiops-dashboard`，返回 `AiOpsDashboardData`（主机三指标 + 主机告警 + 三条时序）。
- 状态持久化已有：监控页结果存在 `localStorage` 的 `superbizagent_last_aiops` 键下，结构为 `{ completedAt, report, dashboard }`。
- 诊断入口已有：`POST /api/ai_ops`，由聊天视图中的按钮触发，完成后调 `persistAndShowAiOpsResult(report)` 跳转监控页。

**约束**

- 后端多项目能力尚未实现，项目层没有任何真实数据源。
- 现有对话链路稳定可用，不应因界面重构而回归。
- 演示需在无外部依赖的情况下可运行，包括后端占位接口未启动的情况。

## Goals / Non-Goals

**Goals**

- 用最小的文件改动量把产品形态固定下来，后续替换数据源时不再动布局与交互。
- 平台层完全复用既有渲染链路，零重写。
- 界面在后端占位接口缺失时仍完全可演示，且不把样例数据伪装成真实数据。
- 前端字段命名与 `add-cicd-multi-project-diagnosis` 的档案与报告结构对齐。

**Non-Goals**

- 不引入前端框架、打包器或路由库。
- 不重写对话视图，不改变会话管理与消息渲染。
- 不实现报告的时间线与证据链分类展开（第二期，本次报告区仍为 Markdown 渲染 + 操作条）。
- 不为项目层业务指标绘制趋势图（等真实指标就位后再加）。

## Decisions

### D1：在既有监控视图内重构，不新增主视图

候选：新增第三个 `main-view` / 在 `#aiopsMonitorView` 内部重排。

选后者。`switchMainView` 的三处调用、侧栏按钮绑定、`persistAndShowAiOpsResult` 的跳转逻辑都指向 `aiops-monitor` 这个视图标识，新增视图意味着这些全要改一遍，且会出现「监控页」与「控制台」两个含义重叠的入口。

保留 `#aiopsMonitorView` 的 id 与 `switchMainView('aiops-monitor')` 的调用契约，只重排其内部 DOM。这样 `app.js` 的改动被压缩到文案与诊断参数两处。

### D2：三层分区映射到三个容器，平台层容器直接托管既有渲染

```
  #aiopsMonitorView
  ├── .de-console-toolbar        新增：项目切换器 + 刷新 + 形态标识
  ├── #dePlatformPanel           新增容器
  │     └── #aiopsMonitorDashboard   既有 id，原样移入
  ├── #deProjectPanel            新增：业务指标 / 活跃异常 / 最近变更 / 诊断入口
  └── #deReportPanel             新增容器
        ├── #aiopsMonitorReport      既有 id，原样移入
        └── #deReportActions         新增：采纳 / 驳回 / 追踪信息
```

`#aiopsMonitorDashboard` 与 `#aiopsMonitorReport` 两个 id 保持不变，意味着 `renderDashboardIntoContainer`、`renderChartsInContainer`、Markdown 渲染与代码高亮全部零改动，只是它们的父容器变了。这是把平台层重写成本压到零的关键。

### D3：项目层渲染独立成函数，与平台层互不调用

新增 `renderProjectPanel(summary)`，与 `renderDashboardIntoContainer` 平级、互不引用。刷新时两者并行发起、各自 `catch`：

```
   refreshConsole()
        ├─ fetchAiOpsDashboard()      → renderPlatformPanel()   catch → 平台层失败态
        └─ fetchProjectSummary(id)    → renderProjectPanel()    catch → 项目层降级
```

用 `Promise.allSettled` 而非 `Promise.all`，直接满足 `diagnosis-console-shell` 的「单层失败不影响另一层」与 `ui-data-contracts` 的「降级不扩散」两条要求。

### D4：样例数据放独立文件，且必须带标记

新增 `diagnosis-sample-data.js`，只导出常量，不含逻辑。前端在接口失败时使用它，同时把该分区置为 `sample` 状态。

样例标注不是可选的 UI 细节，而是规格要求（`ui-data-contracts`）。实现上用一个分区级状态字段驱动三件事：显示样例角标、禁用提交类按钮、在诊断入口上给出提示。把它做成状态而非散落的 if，是为了避免出现「标注忘了加但按钮已经能点」的不一致。

占位接口自身也返回占位标记。于是界面有两级可信度:

```
   真实数据        接口返回且无占位标记
   占位数据        接口可达但标记为 placeholder   → 角标「占位」
   样例数据        接口不可达，走本地常量          → 角标「样例」+ 禁用写操作
```

### D5：项目状态用一个字段收口，持久化进 localStorage

新增 `this.currentProjectId`，所有分区渲染从它派生。持久化到 `localStorage` 的独立键（不塞进既有的 `superbizagent_last_aiops`，避免污染既有结构）。

页面加载时读取，若该项目不在最新列表中则回退到首个可诊断项目并提示——对应 `project-switching-ui` 的「原项目已移除」场景。

报告按项目隔离：既有的 `superbizagent_last_aiops` 单键结构改为按 `projectId` 分槽存储，平台级报告存在固定槽位。这样切换项目时报告层能正确跟随，不会串项目。

### D6：占位接口用独立 Controller，不碰现有监控链路

新增 `DiagnosisConsoleController`，挂在 `/api/diagnosis` 下，四个端点：

| 方法 | 路径 | 占位行为 |
|---|---|---|
| GET | `/projects` | 返回静态项目列表，带 `placeholder: true` |
| GET | `/projects/{id}/summary` | 已知 id 返回静态概览，未知 id 返回 404 |
| POST | `/diagnoses` | 返回 501 与明确的未实现说明 |
| POST | `/reports/{id}/feedback` | 接收并回显，不持久化，带占位标记 |

不改 `MonitoringController` 与 `AiOpsDashboardService`：平台层继续走 `/api/monitoring/aiops-dashboard`。把占位接口和真实接口放在不同的路径前缀下，后续替换实现时边界清晰，也避免有人误以为监控接口已经支持多项目。

`POST /diagnoses` 返回 501 而不是转发到既有 `/api/ai_ops`：既有诊断链路没有项目概念，转发会产出一份与所选项目无关的报告，比明确的未实现更有误导性。前端在收到 501 时展示"项目级诊断待后端实现"，并保留通过既有入口触发平台级分析的能力。

### D7：字段命名对齐已有后端结构

项目概览的字段命名参照 `AiOpsDashboardData` 的既有风格（下划线命名 + `@JsonProperty`），并与 `add-cicd-multi-project-diagnosis` 中 ProjectProfile 与报告结构的术语一致：

```
   项目列表项      id / name / diagnosable / reason / alert_count
   项目概览        project_id / metrics[] / alerts[] / last_change
   指标项          name / value / unit / threshold / meaning / available
   异常项          alert_name / severity / active_at_display / duration
   最近变更        available / at_display / change_id / summary
```

指标项带 `available` 字段而非用 null 表示缺失，变更信息带 `available` 而非用空对象表示不可用——两者都对应规格里「不可用必须区别于零值/无数据」的要求，用显式布尔比让前端猜 null 语义更可靠。

## Risks / Trade-offs

- **改动 `index.html` 时误删既有 id 导致监控页整体失效** → 重排时只移动 `#aiopsMonitorDashboard` 与 `#aiopsMonitorReport` 两个节点的位置，不改其 id 与 class；改完先验证既有的「查看当前监控数据」路径可用，再叠加新分区。

- **样例数据被当成真实数据用于演示** → 样例状态驱动角标与按钮禁用（D4），并在文档中说明演示前应确认角标不存在；占位接口自身也带标记，形成两道提示。

- **报告按项目分槽后，旧的 localStorage 数据结构不兼容** → 读取时做一次结构探测，识别为旧结构则迁移到平台级槽位并保留，不直接丢弃用户已有的最近一次分析结果。

- **项目切换频繁触发请求** → 切换时对项目层请求做去抖，并在响应返回时校验其对应的项目标识仍是当前选中项，避免慢响应覆盖新选择的数据。

- **CSS 新增样式与既有 `.aiops-*` 冲突** → 新增样式统一使用 `de-` 前缀，不修改既有 `.aiops-*` 规则；平台层内部沿用既有样式，外层容器才使用新前缀。

- **本次固定下来的布局在真实数据就位后需要返工** → 项目层的指标以列表形式渲染而非写死四个卡片位，指标数量与名称由数据驱动；这样真实指标与样例指标数量不同时无需改结构。

## Migration Plan

分四步，每步结束界面均可运行。

1. **骨架与样例**：重排 `index.html` 分区、新增 `de-` 样式、新增 `diagnosis-sample-data.js`；项目层与项目列表全部使用样例数据，不调任何新接口。此步结束即可完整演示交互。
2. **平台层接回**：把 `#aiopsMonitorDashboard` 的渲染纳入新的 `refreshConsole` 编排，验证平台层真实数据与项目层样例数据共存，且失败互不影响。
3. **占位接口**：新增 `DiagnosisConsoleController`，前端改为优先请求接口、失败降级到样例；验证占位标记与样例标记两级提示均正确显示。
4. **报告层与反馈**：报告按项目分槽、旧结构迁移、采纳与驳回交互、追踪信息占位区。

**回滚策略**

- 第 1 至 2 步的回滚方式是恢复 `index.html` 的分区结构；`aiops-monitor.js` 新增函数不删除也不会被调用。
- 第 3 步的占位 Controller 为独立类，直接移除不影响任何既有端点。
- 第 4 步涉及 localStorage 结构变更，回滚时旧结构仍能被既有代码读取（迁移只增不改原键的语义）。

**与既有变更的关系**

本变更是 `add-cicd-multi-project-diagnosis` 第 10 组前端任务的前置切片。后者实施到对应阶段时，用真实实现替换 `DiagnosisConsoleController` 的占位逻辑即可，前端渲染层按 D7 的字段契约无需改动。

## Open Questions

- 项目层业务指标的趋势图是否需要（当前只展示当前值），取决于真实指标就位后的观感，不影响本次的容器结构与字段契约。
- 平台级诊断的入口最终放在平台层分区内还是保留在顶部工具栏，属于视觉位置调整，不影响分区划分与数据流。
