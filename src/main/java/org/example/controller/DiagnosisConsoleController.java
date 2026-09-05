package org.example.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import org.example.diagnosis.context.DiagnosisMode;
import org.example.diagnosis.engine.DiagnosisOrchestrator;
import org.example.diagnosis.knowledge.CandidateKnowledgeStore;
import org.example.diagnosis.observe.TraceStore;
import org.example.diagnosis.profile.ProjectProfile;
import org.example.diagnosis.profile.ProjectRegistry;
import org.example.diagnosis.profile.RegisteredProject;
import org.example.diagnosis.report.DiagnosisReport;
import org.example.diagnosis.report.ReportStore;
import org.example.diagnosis.telemetry.DemoTelemetryStore;
import org.example.diagnosis.telemetry.MetricSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/diagnosis")
public class DiagnosisConsoleController {

    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ProjectRegistry registry;
    private final DiagnosisOrchestrator orchestrator;
    private final DemoTelemetryStore telemetry;
    private final ReportStore reportStore;
    private final CandidateKnowledgeStore candidates;
    private final TraceStore traceStore;

    public DiagnosisConsoleController(ProjectRegistry registry, DiagnosisOrchestrator orchestrator,
                                      DemoTelemetryStore telemetry, ReportStore reportStore,
                                      CandidateKnowledgeStore candidates, TraceStore traceStore) {
        this.registry = registry;
        this.orchestrator = orchestrator;
        this.telemetry = telemetry;
        this.reportStore = reportStore;
        this.candidates = candidates;
        this.traceStore = traceStore;
    }

    @GetMapping("/projects")
    public ResponseEntity<ProjectListResponse> listProjects() {
        List<ProjectListItem> items = new ArrayList<>();
        for (RegisteredProject project : registry.list()) {
            items.add(ProjectListItem.builder()
                    .id(project.getId())
                    .name(project.getName())
                    .diagnosable(project.isDiagnosable())
                    .reason(project.getReason() == null ? "" : project.getReason())
                    .alertCount(telemetry.project(project.getId()).alerts.size())
                    .build());
        }
        return ResponseEntity.ok(ProjectListResponse.builder().placeholder(false).projects(items).build());
    }

    @GetMapping("/projects/{projectId}/summary")
    public ResponseEntity<?> projectSummary(@PathVariable String projectId) {
        RegisteredProject project = registry.find(projectId).orElse(null);
        if (project == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("placeholder", false);
            body.put("project_id", projectId);
            body.put("message", "项目未在诊断引擎中注册，请先登记项目档案");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        DemoTelemetryStore.ProjectState state = telemetry.project(projectId);
        List<MetricItem> metrics = new ArrayList<>();
        if (project.getProfile() != null && project.getProfile().getDatasources() != null) {
            for (ProjectProfile.MetricDef def : project.getProfile().getDatasources().getMetrics()) {
                MetricSnapshot snapshot = state.metrics.values().stream()
                        .filter(m -> def.getName().equals(m.getName()))
                        .findFirst()
                        .orElse(null);
                metrics.add(MetricItem.builder()
                        .name(def.getName())
                        .value(snapshot == null ? null : snapshot.getValue())
                        .unit(def.getUnit())
                        .threshold(def.getThreshold())
                        .thresholdDirection(def.getThresholdDirection())
                        .meaning(def.getMeaning())
                        .available(snapshot != null && snapshot.isAvailable())
                        .build());
            }
        }
        List<ProjectAlertItem> alerts = state.alerts.stream()
                .map(name -> ProjectAlertItem.builder()
                        .alertName(name)
                        .severity("critical")
                        .activeAtDisplay(DemoTelemetryStore.now())
                        .duration("live")
                        .build())
                .toList();
        return ResponseEntity.ok(ProjectSummary.builder()
                .placeholder(false)
                .projectId(projectId)
                .projectName(project.getName())
                .metrics(metrics)
                .alerts(alerts)
                .lastChange(LastChange.builder()
                        .available(state.changeAvailable)
                        .atDisplay(state.changeAt)
                        .changeId(state.changeId)
                        .summary(state.changeSummary)
                        .build())
                .build());
    }

    @PostMapping("/diagnoses")
    public ResponseEntity<?> runDiagnosis(@RequestBody(required = false) DiagnosisRequest request) {
        try {
            DiagnosisMode mode = DiagnosisMode.from(request == null ? null : request.getMode());
            String projectId = request == null ? null : request.getProjectId();
            String sessionId = request == null || request.getSessionId() == null ? "console" : request.getSessionId();
            DiagnosisReport report = orchestrator.diagnose(mode, projectId, sessionId,
                    request == null ? null : request.getAlertName());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("implemented", true);
            body.put("placeholder", false);
            body.put("report_id", report.getReportId());
            body.put("trace_id", report.getTraceId());
            body.put("project_id", report.getProjectId());
            body.put("mode", report.getMode().name());
            body.put("confidence", report.getConfidence());
            body.put("report", report.getMarkdown());
            body.put("tool_calls", report.getToolSummary().stream().map(e -> {
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("name", e.getName());
                call.put("summary", e.getSummary());
                return call;
            }).toList());
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage(), "implemented", true));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", e.getMessage(), "implemented", true));
        }
    }

    @PostMapping("/reports/{reportId}/feedback")
    public ResponseEntity<Map<String, Object>> submitFeedback(@PathVariable String reportId,
                                                              @RequestBody(required = false) FeedbackRequest request) {
        DiagnosisReport report = reportStore.find(reportId).orElse(null);
        if (report == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "报告不存在", "report_id", reportId));
        }
        String decision = request == null ? null : request.getDecision();
        String reason = request == null ? null : request.getReason();
        report.setFeedback(DiagnosisReport.Feedback.builder()
                .decision(decision)
                .reason(reason)
                .at(LocalDateTime.now().format(DISPLAY_FORMAT))
                .build());
        var candidate = candidates.onFeedback(report, decision, reason);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("placeholder", false);
        body.put("persisted", true);
        body.put("report_id", reportId);
        body.put("decision", decision);
        body.put("reason", reason);
        body.put("feedback_at", report.getFeedback().getAt());
        candidate.ifPresent(c -> body.put("candidate_id", c.id()));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/registry/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        boolean ok = registry.tryReload();
        return ResponseEntity.ok(Map.of("reloaded", ok, "projects", registry.list().size()));
    }

    @GetMapping("/traces/{traceId}")
    public ResponseEntity<?> trace(@PathVariable String traceId) {
        return traceStore.find(traceId)
                .<ResponseEntity<?>>map(t -> ResponseEntity.ok(Map.of(
                        "trace_id", t.getTraceId(),
                        "mode", t.getMode(),
                        "project_id", t.getProjectId(),
                        "success", t.isSuccess(),
                        "tokens", t.totalTokens(),
                        "events", t.orderedEvents()
                )))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "轨迹不存在", "trace_id", traceId)));
    }

    @GetMapping("/knowledge/candidates")
    public List<CandidateKnowledgeStore.Candidate> listCandidates() {
        return candidates.list();
    }

    @PostMapping("/knowledge/candidates/{id}/confirm")
    public CandidateKnowledgeStore.Candidate confirm(@PathVariable String id,
                                                     @RequestBody(required = false) Map<String, String> body) {
        Map<String, String> safe = body == null ? Map.of() : body;
        return candidates.confirm(id, safe.get("feature"), safe.get("rootCause"), safe.get("action"));
    }

    @PostMapping("/knowledge/candidates/{id}/reject")
    public CandidateKnowledgeStore.Candidate rejectCandidate(@PathVariable String id,
                                                             @RequestBody(required = false) Map<String, String> body) {
        return candidates.reject(id, body == null ? "" : body.getOrDefault("reason", ""));
    }

    @Data
    @Builder
    public static class ProjectListResponse {
        private boolean placeholder;
        private List<ProjectListItem> projects;
    }

    @Data
    @Builder
    public static class ProjectListItem {
        private String id;
        private String name;
        private boolean diagnosable;
        private String reason;
        @JsonProperty("alert_count")
        private int alertCount;
    }

    @Data
    @Builder
    public static class ProjectSummary {
        private boolean placeholder;
        @JsonProperty("project_id")
        private String projectId;
        @JsonProperty("project_name")
        private String projectName;
        private List<MetricItem> metrics;
        private List<ProjectAlertItem> alerts;
        @JsonProperty("last_change")
        private LastChange lastChange;
    }

    @Data
    @Builder
    public static class MetricItem {
        private String name;
        private Double value;
        private String unit;
        private Double threshold;
        @JsonProperty("threshold_direction")
        private String thresholdDirection;
        private String meaning;
        private boolean available;
    }

    @Data
    @Builder
    public static class ProjectAlertItem {
        @JsonProperty("alert_name")
        private String alertName;
        private String severity;
        @JsonProperty("active_at_display")
        private String activeAtDisplay;
        private String duration;
    }

    @Data
    @Builder
    public static class LastChange {
        private boolean available;
        @JsonProperty("at_display")
        private String atDisplay;
        @JsonProperty("change_id")
        private String changeId;
        private String summary;
    }

    @Data
    public static class DiagnosisRequest {
        @JsonProperty("project_id")
        private String projectId;
        private String mode;
        @JsonProperty("session_id")
        private String sessionId;
        @JsonProperty("alert_name")
        private String alertName;
    }

    @Data
    public static class FeedbackRequest {
        private String decision;
        private String reason;
    }
}
