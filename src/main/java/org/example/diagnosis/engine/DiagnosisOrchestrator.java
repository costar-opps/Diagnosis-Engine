package org.example.diagnosis.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.checkpoint.CheckpointRecord;
import org.example.checkpoint.CheckpointService;
import org.example.checkpoint.PendingTool;
import org.example.diagnosis.DiagnosisProperties;
import org.example.diagnosis.access.QueryResult;
import org.example.diagnosis.access.ScopedDataAccess;
import org.example.diagnosis.context.DiagnosisContext;
import org.example.diagnosis.context.DiagnosisContextHolder;
import org.example.diagnosis.context.DiagnosisMode;
import org.example.diagnosis.knowledge.ScopedKnowledgeStore;
import org.example.diagnosis.memory.ContextAssembler;
import org.example.diagnosis.memory.EvidenceBlackboard;
import org.example.diagnosis.memory.EvidenceItem;
import org.example.diagnosis.memory.RawRef;
import org.example.diagnosis.memory.SessionMemoryStore;
import org.example.diagnosis.observe.DiagnosisMetrics;
import org.example.diagnosis.observe.DiagnosisTrace;
import org.example.diagnosis.observe.TraceEvent;
import org.example.diagnosis.observe.TraceStore;
import org.example.diagnosis.profile.ProjectProfile;
import org.example.diagnosis.profile.ProjectRegistry;
import org.example.diagnosis.profile.RegisteredProject;
import org.example.diagnosis.report.ConfidenceCalculator;
import org.example.diagnosis.report.DiagnosisReport;
import org.example.diagnosis.report.ReportStore;
import org.example.diagnosis.telemetry.DemoTelemetryStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DiagnosisOrchestrator {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DiagnosisProperties properties;
    private final ProjectRegistry registry;
    private final ModeRouter modeRouter;
    private final PromptVariableAssembler prompts;
    private final ScopedDataAccess dataAccess;
    private final SessionMemoryStore sessionMemory;
    private final ContextAssembler contextAssembler;
    private final TraceStore traceStore;
    private final DiagnosisMetrics metrics;
    private final ReportStore reportStore;
    private final ConfidenceCalculator confidenceCalculator;
    private final ScopedKnowledgeStore knowledgeStore;
    private final DemoTelemetryStore telemetry;
    private final CheckpointService checkpoints;
    private final ObjectMapper objectMapper;
    private final Semaphore concurrency;
    private final ThreadLocal<CheckpointRun> checkpointRun = new ThreadLocal<>();

    public DiagnosisOrchestrator(DiagnosisProperties properties, ProjectRegistry registry, ModeRouter modeRouter,
                                 PromptVariableAssembler prompts, ScopedDataAccess dataAccess,
                                 SessionMemoryStore sessionMemory, ContextAssembler contextAssembler,
                                 TraceStore traceStore, DiagnosisMetrics metrics, ReportStore reportStore,
                                 ConfidenceCalculator confidenceCalculator, ScopedKnowledgeStore knowledgeStore,
                                 DemoTelemetryStore telemetry, CheckpointService checkpoints,
                                 ObjectMapper objectMapper) {
        this.properties = properties;
        this.registry = registry;
        this.modeRouter = modeRouter;
        this.prompts = prompts;
        this.dataAccess = dataAccess;
        this.sessionMemory = sessionMemory;
        this.contextAssembler = contextAssembler;
        this.traceStore = traceStore;
        this.metrics = metrics;
        this.reportStore = reportStore;
        this.confidenceCalculator = confidenceCalculator;
        this.knowledgeStore = knowledgeStore;
        this.telemetry = telemetry;
        this.checkpoints = checkpoints;
        this.objectMapper = objectMapper;
        this.concurrency = new Semaphore(properties.getLimits().getMaxConcurrent());
    }

    public DiagnosisReport diagnose(DiagnosisMode requestedMode, String projectId, String sessionId, String alertName) {
        return diagnose(requestedMode, projectId, sessionId, alertName, null, null);
    }

    public DiagnosisReport diagnose(DiagnosisMode requestedMode, String projectId, String sessionId, String alertName,
                                    CheckpointRecord checkpoint,
                                    CheckpointRecord.RecoveryAudit recoveryAudit) {
        boolean acquired;
        try {
            acquired = concurrency.tryAcquire(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("诊断并发等待被中断");
        }
        if (!acquired) {
            throw new IllegalStateException("并发诊断已达上限");
        }
        long started = System.currentTimeMillis();
        String traceId = "tr-" + UUID.randomUUID().toString().substring(0, 8);
        DiagnosisTrace trace = new DiagnosisTrace(traceId, requestedMode, projectId);
        EvidenceBlackboard board = new EvidenceBlackboard("diag-" + traceId);
        try {
            checkpointRun.set(new CheckpointRun(checkpoint, recoveryAudit));
            DiagnosisContext context = checkpoint != null && recoveryAudit != null
                    ? restoreContext(checkpoint, traceId)
                    : assembleContext(requestedMode, projectId, sessionId, alertName, traceId);
            if (checkpoint != null && recoveryAudit == null) {
                checkpoint.getTaskState().put("mode", context.getMode().name());
                checkpoint.getTaskState().put("project_id", context.getProjectId());
                checkpoint.getTaskState().put("session_id", context.getSessionId());
                checkpoint.getTaskState().put("window_start", context.getWindowStart().toString());
                checkpoint.getTaskState().put("window_end", context.getWindowEnd().toString());
                checkpoint.getTaskState().put("routing_reason", context.getRoutingReason());
                checkpoint.getTaskState().put("registry_fingerprint", registry.fingerprint());
                checkpoints.update(checkpoint, current -> current.setPhase("COLLECTING"));
            }
            trace = new DiagnosisTrace(traceId, context.getMode(), context.getProjectId());
            if (recoveryAudit != null) {
                trace.add(TraceEvent.builder().type("AUDIT").name("checkpoint-resume").phase("enter")
                        .startedAt(System.currentTimeMillis()).durationMs(1).success(true)
                        .summary("从 " + checkpoint.getTaskId() + " 第 " + checkpoint.getRecoveryCount() + " 次恢复")
                        .build());
            }
            board.setMode(context.getMode());
            board.setProjectId(context.getProjectId());
            DiagnosisContextHolder.set(context);
            node(trace, "planner", true, "装配上下文并规划取证");
            collect(context, board, trace);
            node(trace, "executor", true, "完成取证");
            String assembled = contextAssembler.assemble(context, board, lastToolSummary(trace));
            recordLlm(trace, assembled);
            DiagnosisReport report = buildReport(context, board, trace);
            reportStore.save(report);
            trace.finish(true);
            if (checkpoint != null) {
                String reportText = report.getMarkdown();
                checkpoints.update(checkpoint, current -> {
                    current.setStatus(CheckpointRecord.Status.COMPLETED);
                    current.setPhase("COMPLETED");
                    current.setPartialOutput(reportText);
                    current.setFinalOutput(reportText);
                    current.getTaskState().put("trace_id", report.getTraceId());
                    current.getTaskState().put("report_id", report.getReportId());
                });
                if (recoveryAudit != null) {
                    checkpoints.finishRecovery(checkpoint, recoveryAudit, "COMPLETED", "诊断恢复完成");
                }
            }
            return report;
        } catch (RuntimeException e) {
            trace.finish(false);
            if (checkpoint != null) {
                checkpoints.update(checkpoint, current -> {
                    current.setStatus(CheckpointRecord.Status.FAILED);
                    current.setPhase("FAILED");
                });
                if (recoveryAudit != null) {
                    checkpoints.finishRecovery(checkpoint, recoveryAudit, "FAILED", e.getMessage());
                }
            }
            throw e;
        } finally {
            checkpointRun.remove();
            DiagnosisContextHolder.clear();
            traceStore.save(trace);
            long duration = System.currentTimeMillis() - started;
            int toolCalls = (int) trace.orderedEvents().stream().filter(e -> "TOOL".equals(e.getType())).count();
            int failures = (int) trace.orderedEvents().stream()
                    .filter(e -> "TOOL".equals(e.getType()) && !e.isSuccess()).count();
            metrics.recordCompleted(trace.getMode(), trace.getProjectId(), Boolean.TRUE.equals(trace.isSuccess()),
                    duration, toolCalls, trace.totalTokens(), failures);
            concurrency.release();
        }
    }

    public DiagnosisContext assembleContext(DiagnosisMode requested, String projectId, String sessionId,
                                            String alertName, String traceId) {
        ModeRouter.Route route = modeRouter.decide(requested, projectId, alertName);
        Instant end = Instant.now();
        Instant start = end.minusSeconds(1800);
        RegisteredProject project = null;
        if (route.mode() == DiagnosisMode.PROJECT) {
            project = registry.requireDiagnosable(route.projectId());
            sessionMemory.bindProject(sessionId, route.projectId());
        }
        return DiagnosisContext.builder()
                .traceId(traceId)
                .sessionId(sessionId)
                .mode(route.mode())
                .projectId(route.projectId())
                .project(project)
                .windowStart(start)
                .windowEnd(end)
                .routingReason(route.reason())
                .build();
    }

    private DiagnosisContext restoreContext(CheckpointRecord checkpoint, String traceId) {
        Object expectedFingerprint = checkpoint.getTaskState().get("registry_fingerprint");
        if (expectedFingerprint == null || !registry.fingerprint().equals(expectedFingerprint.toString())) {
            throw new CheckpointService.CheckpointVersionException("项目档案版本已变化，拒绝恢复旧诊断");
        }
        DiagnosisMode mode = DiagnosisMode.from(String.valueOf(checkpoint.getTaskState().get("mode")));
        String projectId = nullableString(checkpoint.getTaskState().get("project_id"));
        String sessionId = nullableString(checkpoint.getTaskState().get("session_id"));
        RegisteredProject project = mode == DiagnosisMode.PROJECT ? registry.requireDiagnosable(projectId) : null;
        return DiagnosisContext.builder()
                .traceId(traceId)
                .sessionId(sessionId)
                .mode(mode)
                .projectId(projectId)
                .project(project)
                .windowStart(Instant.parse(String.valueOf(checkpoint.getTaskState().get("window_start"))))
                .windowEnd(Instant.parse(String.valueOf(checkpoint.getTaskState().get("window_end"))))
                .routingReason(String.valueOf(checkpoint.getTaskState().get("routing_reason")))
                .build();
    }

    private static String nullableString(Object value) {
        return value == null || "null".equals(value.toString()) ? null : value.toString();
    }

    private void collect(DiagnosisContext context, EvidenceBlackboard board, DiagnosisTrace trace) {
        int budget = properties.getLimits().getMaxToolCalls();
        AtomicInteger calls = new AtomicInteger();
        QueryResult platform = call(trace, board, calls, budget, "queryPlatformMetrics",
                dataAccess::queryPlatformMetrics);
        context.setPlatformScanSummary(platform.getSummary());
        if (platform.isSuccess()) {
            board.getAlerts().addAll(castList(platform.getExtra().get("alerts")));
            board.getAffectedProjects().addAll(affectedFromPlatform(platform));
            board.addEvidence(item("platform", "共享资源", platform.getSummary(), "platform",
                    context.getMode() == DiagnosisMode.PLATFORM ? "root_cause" : "range", platform.getRawRef()));
        }

        if (context.getMode() == DiagnosisMode.PROJECT) {
            collectProject(context, context.getProjectId(), board, trace, calls, budget, false);
            maybeExpand(context, board, trace, calls, budget);
        } else {
            telemetry.allProjects().forEach((id, state) -> {
                if (!state.alerts.isEmpty() || !state.logs.isEmpty()) {
                    board.getAffectedProjects().add(id);
                }
            });
        }

        searchKnowledge(context, board, trace, calls, budget);
    }

    private void collectProject(DiagnosisContext context, String projectId, EvidenceBlackboard board,
                                DiagnosisTrace trace, AtomicInteger calls, int budget, boolean expansion) {
        RegisteredProject registered = registry.find(projectId).orElse(null);
        if (registered == null || !registered.isDiagnosable()) {
            board.getFailedTools().add("project:" + projectId + " 未注册或不可诊断");
            return;
        }
        for (ProjectProfile.MetricDef metric : registered.getProfile().getDatasources().getMetrics()) {
            QueryResult result = call(trace, board, calls, budget,
                    "queryProjectMetric:" + projectId + ":" + metric.getName(),
                    () -> dataAccess.queryProjectMetric(projectId, metric.getName()));
            if (result.isSuccess()) {
                board.addEvidence(item("metric", metric.getName(), result.getSummary(), projectId,
                        expansion ? "range" : "root_cause", result.getRawRef()));
            }
        }
        QueryResult logs = call(trace, board, calls, budget, "queryProjectLogs:" + projectId,
                () -> dataAccess.queryProjectLogs(projectId));
        if (logs.isSuccess()) {
            board.addEvidence(item("log", projectId + " 日志", logs.getSummary(), projectId,
                    context.getMode() == DiagnosisMode.PLATFORM ? "range" : "root_cause", logs.getRawRef()));
        }
        QueryResult change = call(trace, board, calls, budget, "queryChanges:" + projectId,
                () -> dataAccess.queryChanges(projectId));
        if (change.isSuccess() && !Boolean.TRUE.equals(change.getExtra().get("empty"))) {
            board.addEvidence(item("change", "最近变更", change.getSummary(), projectId, "root_cause",
                    RawRef.builder().queryId("change-" + projectId).source("change")
                            .locator(String.valueOf(change.getExtra().getOrDefault("change_id", "")))
                            .totalCount(1).build()));
        }
        QueryResult code = call(trace, board, calls, budget, "locateCode:" + projectId,
                () -> dataAccess.locateCode(projectId));
        if (code.isSuccess() && code.getItems() != null && !code.getItems().isEmpty()) {
            board.addEvidence(item("code", "代码线索", code.getSummary(), projectId, "root_cause",
                    RawRef.builder().queryId("code-" + projectId).source("code").totalCount(code.getItems().size()).build()));
        }
        DemoTelemetryStore.ProjectState state = telemetry.project(projectId);
        board.getAlerts().addAll(state.alerts);
    }

    private void maybeExpand(DiagnosisContext context, EvidenceBlackboard board, DiagnosisTrace trace,
                             AtomicInteger calls, int budget) {
        if (!properties.isCrossProjectExpansionEnabled() || context.profile() == null) {
            return;
        }
        boolean hint = board.getEvidences().stream()
                .filter(e -> "log".equals(e.getCategory()))
                .anyMatch(e -> e.getSummary() != null && e.getSummary().contains("payment-service"));
        List<String> downstream = context.profile().getDependencies().getDownstream();
        if (!hint) {
            return;
        }
        if (downstream == null || downstream.isEmpty()) {
            context.getExpansionNotes().add("出现下游依赖线索，但档案未声明下游，不扩展");
            return;
        }
        for (String dep : downstream) {
            if (registry.find(dep).isEmpty()) {
                context.getExpansionNotes().add("依赖 " + dep + " 未注册，跳过扩展");
                continue;
            }
            context.getExpansionProjectIds().add(dep);
            context.getExpansionNotes().add("一跳扩展到 " + dep);
            collectProject(context, dep, board, trace, calls, budget, true);
        }
    }

    private void searchKnowledge(DiagnosisContext context, EvidenceBlackboard board, DiagnosisTrace trace,
                                 AtomicInteger calls, int budget) {
        String query = context.getProjectId() == null ? "内存 磁盘" : context.getProjectId();
        QueryResult result = call(trace, board, calls, budget, "queryInternalDocs", () -> {
            List<ScopedKnowledgeStore.Doc> docs = knowledgeStore.search(
                    context.getMode(), context.getProjectId(), properties.isCrossProjectRetrievalEnabled(), query);
            if (docs.isEmpty()) {
                return QueryResult.builder().success(true).tool("queryInternalDocs").datasource("knowledge")
                        .summary("知识检索无命中，不虚构文档来源").build();
            }
            StringBuilder sb = new StringBuilder();
            docs.forEach(doc -> sb.append(doc.title()).append(" [").append(doc.scope()).append("/")
                    .append(doc.projectId() == null ? "-" : doc.projectId()).append("]"));
            return QueryResult.builder().success(true).tool("queryInternalDocs").datasource("knowledge")
                    .summary(sb.toString())
                    .rawRef(RawRef.builder().queryId("kb-" + docs.get(0).id()).source("knowledge")
                            .totalCount(docs.size()).build())
                    .build();
        });
        if (result.isSuccess() && result.getRawRef() != null) {
            board.addEvidence(item("knowledge", "历史知识", result.getSummary() + "（historical_clue，须本次取证）",
                    context.getProjectId(), "historical_clue", result.getRawRef()));
        }
    }

    private DiagnosisReport buildReport(DiagnosisContext context, EvidenceBlackboard board, DiagnosisTrace trace) {
        List<EvidenceItem> rooted = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (EvidenceItem item : board.getEvidences()) {
            if (confidenceCalculator.canBeRootCause(item)) {
                rooted.add(item);
            } else {
                pending.add(item.getTitle() + "：" + item.getSummary());
            }
        }
        ConfidenceCalculator.Result confidence = confidenceCalculator.calculate(board);
        String root = rooted.isEmpty()
                ? "证据不足，仅输出待确认线索"
                : rooted.get(0).getTitle() + " — " + rooted.get(0).getSummary();
        List<String> timeline = new ArrayList<>();
        rooted.forEach(item -> timeline.add((item.getRawRef() == null ? "-" : item.getRawRef().getWindowEnd())
                + " " + item.getCategory() + " " + item.getTitle()));
        List<DiagnosisReport.Suggestion> suggestions = new ArrayList<>();
        suggestions.add(DiagnosisReport.Suggestion.builder()
                .text(context.getMode() == DiagnosisMode.PLATFORM
                        ? "先处理共享资源瓶颈并观察受影响项目恢复情况"
                        : "按关键链路从失败步骤回滚或限流，并核对最近变更")
                .source("document")
                .ref("scoped-knowledge")
                .build());
        String markdown = renderMarkdown(context, board, root, confidence, pending, suggestions, trace);
        return DiagnosisReport.builder()
                .reportId("rpt-" + context.getTraceId())
                .traceId(context.getTraceId())
                .generatedAt(LocalDateTime.now().format(FMT))
                .mode(context.getMode())
                .projectId(context.getProjectId())
                .projectName(context.getProject() == null ? "platform" : context.getProject().getName())
                .overview(context.getRoutingReason())
                .timeline(timeline)
                .evidenceChain(rooted)
                .rootCause(root)
                .confidence(confidence.level())
                .confidenceReason(confidence.reason())
                .pendingClues(pending)
                .suggestions(suggestions)
                .toolSummary(trace.orderedEvents().stream().filter(e -> "TOOL".equals(e.getType())).toList())
                .expansion(context.getExpansionNotes())
                .markdown(markdown)
                .build();
    }

    private String renderMarkdown(DiagnosisContext context, EvidenceBlackboard board, String root,
                                  ConfidenceCalculator.Result confidence, List<String> pending,
                                  List<DiagnosisReport.Suggestion> suggestions, DiagnosisTrace trace) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(context.getMode() == DiagnosisMode.PLATFORM ? "平台级" : "项目级").append("诊断报告\n\n");
        sb.append("- 追踪标识：`").append(context.getTraceId()).append("`\n");
        sb.append("- 提示词变量：\n```\n").append(prompts.assemble(context)).append("```\n");
        sb.append("- 置信度：**").append(confidence.level()).append("**（").append(confidence.reason()).append("）\n\n");
        sb.append("### 根因\n").append(root).append("\n\n");
        sb.append("### 证据链\n");
        board.getEvidences().forEach(item -> sb.append("- ").append(item.getCategory()).append(" / ")
                .append(item.getTitle()).append("：").append(item.getSummary()).append('\n'));
        if (!context.getExpansionNotes().isEmpty()) {
            sb.append("\n### 扩展范围\n");
            context.getExpansionNotes().forEach(note -> sb.append("- ").append(note).append('\n'));
        }
        if (!pending.isEmpty()) {
            sb.append("\n### 待确认线索\n");
            pending.forEach(p -> sb.append("- ").append(p).append('\n'));
        }
        sb.append("\n### 建议\n");
        suggestions.forEach(s -> sb.append("- ").append(s.getText()).append("（来源：").append(s.getSource()).append("）\n"));
        if (!board.getTrimRecords().isEmpty()) {
            sb.append("\n### 诊断过程\n");
            board.getTrimRecords().forEach(t -> sb.append("- ").append(t).append('\n'));
        }
        sb.append("\n### 工具轨迹\n");
        trace.orderedEvents().stream().filter(e -> "TOOL".equals(e.getType()))
                .forEach(e -> sb.append("1. ").append(e.getName()).append(" → ").append(e.getSummary()).append('\n'));
        return sb.toString();
    }

    private QueryResult call(DiagnosisTrace trace, EvidenceBlackboard board, AtomicInteger calls, int budget,
                             String name, java.util.function.Supplier<QueryResult> supplier) {
        CheckpointRun run = checkpointRun.get();
        PendingTool tracked = null;
        if (run != null && run.checkpoint() != null) {
            PendingTool previous = checkpoints.findTool(run.checkpoint(), name, "").orElse(null);
            if (previous != null && previous.getStatus() == PendingTool.Status.SUCCEEDED) {
                try {
                    QueryResult cached = objectMapper.readValue(previous.getResult(), QueryResult.class);
                    if (run.audit() != null) {
                        run.audit().getSkippedTools().add(name);
                        checkpoints.update(run.checkpoint(), ignored -> { });
                    }
                    trace.add(TraceEvent.builder().type("TOOL").name(name).phase("checkpoint-hit")
                            .startedAt(System.currentTimeMillis()).durationMs(0).success(cached.isSuccess())
                            .summary(cached.getSummary()).build());
                    return cached;
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("checkpoint 工具结果无法解析: " + name, e);
                }
            }
            if (previous != null && previous.getStatus() == PendingTool.Status.FAILED
                    && !checkpoints.mayRetry(previous)) {
                board.getFailedTools().add(name + ":达到恢复重试上限");
                return QueryResult.fail(name, "checkpoint", "达到恢复重试上限");
            }
            if (previous != null && run.audit() != null) {
                if (previous.getStatus() == PendingTool.Status.UNKNOWN) {
                    run.audit().getVerifiedTools().add(name);
                } else if (previous.getStatus() == PendingTool.Status.FAILED) {
                    run.audit().getRetriedTools().add(name);
                }
            }
            tracked = checkpoints.beforeTool(run.checkpoint(), name, "");
        }
        if (calls.incrementAndGet() > budget) {
            board.setBudgetExhausted(true);
            board.getTrimRecords().add("达到工具调用上限，提前终止");
            return QueryResult.fail(name, "limit", "工具调用次数达到上限");
        }
        long t0 = System.currentTimeMillis();
        try {
            QueryResult result = supplier.get();
            boolean ok = result.isSuccess();
            if (!ok) {
                board.getFailedTools().add(name + ":" + result.getMessage());
            }
            if (tracked != null) {
                if (ok) {
                    checkpoints.toolSucceeded(run.checkpoint(), tracked, writeResult(result));
                } else {
                    checkpoints.toolFailed(run.checkpoint(), tracked, result.getMessage());
                }
            }
            trace.add(TraceEvent.builder()
                    .type("TOOL").name(name).phase("call").startedAt(t0)
                    .durationMs(System.currentTimeMillis() - t0).success(ok)
                    .summary(result.getSummary()).rejectReason(ok ? null : result.getMessage())
                    .build());
            return result;
        } catch (RuntimeException e) {
            if (tracked != null) {
                checkpoints.toolFailed(run.checkpoint(), tracked, e.getMessage());
            }
            board.getFailedTools().add(name + ":" + e.getMessage());
            trace.add(TraceEvent.builder()
                    .type("TOOL").name(name).phase("call").startedAt(t0)
                    .durationMs(System.currentTimeMillis() - t0).success(false)
                    .summary(e.getMessage()).rejectReason(e.getMessage())
                    .build());
            return QueryResult.fail(name, "tool", e.getMessage());
        }
    }

    private String writeResult(QueryResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法提交工具 checkpoint", e);
        }
    }

    private void node(DiagnosisTrace trace, String name, boolean success, String summary) {
        trace.add(TraceEvent.builder()
                .type("NODE").name(name).phase("exit")
                .startedAt(System.currentTimeMillis()).durationMs(1)
                .success(success).summary(summary).build());
    }

    private void recordLlm(DiagnosisTrace trace, String assembled) {
        int tokens = Math.max(1, assembled.length() / 3);
        trace.add(TraceEvent.builder()
                .type("LLM").name("report-composer").model("template+budget")
                .phase("call").startedAt(System.currentTimeMillis()).durationMs(2)
                .success(true).inputTokens(tokens).outputTokens(Math.min(400, tokens / 2))
                .retryCount(0)
                .summary(properties.getObservability().isPersistPromptBody() ? assembled : "正文留存已关闭，保留结构化字段")
                .build());
    }

    private static String lastToolSummary(DiagnosisTrace trace) {
        return trace.orderedEvents().stream().filter(e -> "TOOL".equals(e.getType()))
                .reduce((a, b) -> b).map(TraceEvent::getSummary).orElse("");
    }

    private static EvidenceItem item(String category, String title, String summary, String scope,
                                     String role, RawRef rawRef) {
        return EvidenceItem.builder()
                .category(category).title(title).summary(summary)
                .projectScope(scope).role(role).rawRef(rawRef)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private List<String> affectedFromPlatform(QueryResult platform) {
        Object owner = platform.getExtra().get("memory_owner");
        if (owner != null && !"shared-runtime".equals(owner.toString())) {
            return List.of(owner.toString());
        }
        return telemetry.allProjects().keySet().stream().toList();
    }

    private record CheckpointRun(CheckpointRecord checkpoint, CheckpointRecord.RecoveryAudit audit) {
    }
}
