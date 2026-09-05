package org.example.diagnosis.access;

import org.example.diagnosis.context.DiagnosisContext;
import org.example.diagnosis.context.DiagnosisContextHolder;
import org.example.diagnosis.memory.ToolResultSummarizer;
import org.example.diagnosis.profile.ProjectProfile;
import org.example.diagnosis.profile.RegisteredProject;
import org.example.diagnosis.telemetry.DemoTelemetryStore;
import org.example.diagnosis.telemetry.LogRecord;
import org.example.diagnosis.telemetry.MetricSnapshot;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ScopedDataAccess {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private static final Pattern STACK_LINE = Pattern.compile("([\\w.]+)\\.([\\w$]+)\\(([^:]+):(\\d+)\\)");

    private final DemoTelemetryStore telemetry;
    private final ToolResultSummarizer summarizer;

    public ScopedDataAccess(DemoTelemetryStore telemetry, ToolResultSummarizer summarizer) {
        this.telemetry = telemetry;
        this.summarizer = summarizer;
    }

    public void assertProject(String projectId) {
        DiagnosisContext context = DiagnosisContextHolder.get();
        if (context == null) {
            throw new IllegalStateException("缺少诊断上下文，拒绝查询");
        }
        if (context.isProjectMode()) {
            if (projectId == null || !projectId.equals(context.getProjectId())) {
                throw new IllegalArgumentException("projectId 与权威上下文不一致: " + projectId);
            }
        } else if (projectId != null && !projectId.isBlank()
                && context.getExpansionProjectIds().stream().noneMatch(projectId::equals)
                && !projectId.equals(context.getProjectId())) {
            throw new IllegalArgumentException("平台级诊断不允许查询未授权项目: " + projectId);
        }
        RegisteredProject project = context.getProject();
        if (context.isProjectMode() && (project == null || !project.isDiagnosable())) {
            throw new IllegalArgumentException("项目不可诊断或未注册: " + projectId);
        }
    }

    public QueryResult queryPlatformMetrics() {
        DemoTelemetryStore.PlatformState platform = telemetry.platform();
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(metricItem("memory_used_percent", platform.memoryUsedPercent, "%", 85, "upper", "主机内存使用率"));
        items.add(metricItem("disk_used_percent", platform.diskUsedPercent, "%", 90, "upper", "主机磁盘使用率"));
        ToolResultSummarizer.SummarizedResult summarized = summarizer.downsampleMetrics(
                "platform", "host-resources", windowStart(), windowEnd(),
                List.of(platform.memoryUsedPercent, platform.diskUsedPercent));
        return QueryResult.builder()
                .success(true)
                .tool("queryPlatformMetrics")
                .datasource("platform-metrics")
                .summary(summarized.text() + " 内存占用主体=" + platform.memoryOwner + " 磁盘占用主体=" + platform.diskOwner)
                .rawRef(summarized.rawRef())
                .items(items)
                .extra(Map.of(
                        "memory_owner", platform.memoryOwner,
                        "disk_owner", platform.diskOwner,
                        "alerts", platform.alerts
                ))
                .build();
    }

    public QueryResult queryProjectMetric(String projectId, String metricName) {
        assertProject(projectId);
        DiagnosisContext context = DiagnosisContextHolder.get();
        ProjectProfile.MetricDef def = findMetric(context.profile(), metricName);
        if (def == null) {
            return QueryResult.fail("queryProjectMetric", "project-metrics",
                    "指标未在项目档案中声明: " + metricName + "，禁止用近似指标替代");
        }
        MetricSnapshot snapshot = telemetry.project(projectId).metrics.get(resolveKey(def));
        if (snapshot == null) {
            snapshot = telemetry.project(projectId).metrics.values().stream()
                    .filter(item -> def.getName().equals(item.getName()))
                    .findFirst().orElse(null);
        }
        if (snapshot == null) {
            return QueryResult.fail("queryProjectMetric", "project-metrics",
                    "档案已声明但当前采集不可用: " + metricName);
        }
        ToolResultSummarizer.SummarizedResult summarized = summarizer.downsampleMetrics(
                projectId, def.getName(), windowStart(), windowEnd(), snapshot.getSeries());
        return QueryResult.builder()
                .success(true)
                .tool("queryProjectMetric")
                .datasource("project-metrics")
                .summary(def.getName() + "=" + snapshot.getValue() + snapshot.getUnit() + " " + summarized.text())
                .rawRef(summarized.rawRef())
                .items(List.of(metricItem(def.getName(), snapshot.getValue(), snapshot.getUnit(),
                        snapshot.getThreshold(), snapshot.getThresholdDirection(), snapshot.getMeaning())))
                .build();
    }

    public QueryResult queryProjectLogs(String projectId) {
        assertProject(projectId);
        DiagnosisContext context = DiagnosisContextHolder.get();
        ProjectProfile.LogSource logs = context.profile() == null ? null : context.profile().getDatasources().getLogs();
        String locator = logs == null ? projectId : logs.getLocator();
        Map<String, String> fieldMap = logs == null || logs.getFieldMap() == null ? Map.of() : logs.getFieldMap();
        List<Map<String, String>> normalized = new ArrayList<>();
        for (LogRecord record : telemetry.project(projectId).logs) {
            normalized.add(normalize(record, fieldMap, projectId));
        }
        ToolResultSummarizer.SummarizedResult summarized = summarizer.summarizeLogs(
                projectId, locator, windowStart(), windowEnd(), normalized);
        return QueryResult.builder()
                .success(true)
                .tool("queryProjectLogs")
                .datasource("project-logs")
                .summary(summarized.text())
                .rawRef(summarized.rawRef())
                .extra(Map.of("records", normalized, "locator", locator))
                .build();
    }

    public QueryResult queryChanges(String projectId) {
        assertProject(projectId);
        DemoTelemetryStore.ProjectState state = telemetry.project(projectId);
        if (state.changeUnreachable) {
            return QueryResult.fail("queryChanges", "change", "变更源不可达");
        }
        if (!state.changeAvailable) {
            return QueryResult.builder()
                    .success(true)
                    .tool("queryChanges")
                    .datasource("change")
                    .summary("近期无变更")
                    .extra(Map.of("available", false, "empty", true))
                    .build();
        }
        return QueryResult.builder()
                .success(true)
                .tool("queryChanges")
                .datasource("change")
                .summary(state.changeAt + " " + state.changeId + " " + state.changeSummary)
                .extra(Map.of(
                        "available", true,
                        "change_id", state.changeId,
                        "at", state.changeAt,
                        "summary", state.changeSummary
                ))
                .build();
    }

    public QueryResult locateCode(String projectId) {
        assertProject(projectId);
        DiagnosisContext context = DiagnosisContextHolder.get();
        String prefix = context.profile() == null ? null : context.profile().getDatasources().getCode().getPackagePrefix();
        List<Map<String, Object>> clues = new ArrayList<>();
        for (LogRecord record : telemetry.project(projectId).logs) {
            if (record.getStack() == null || prefix == null) {
                continue;
            }
            Matcher matcher = STACK_LINE.matcher(record.getStack());
            while (matcher.find()) {
                String className = matcher.group(1);
                if (className.startsWith(prefix)) {
                    Map<String, Object> clue = new LinkedHashMap<>();
                    clue.put("file", matcher.group(3));
                    clue.put("method", matcher.group(2));
                    clue.put("line", matcher.group(4));
                    clue.put("class", className);
                    clue.put("source_log", record.getMessage());
                    clues.add(clue);
                }
            }
        }
        if (clues.isEmpty()) {
            return QueryResult.builder()
                    .success(true)
                    .tool("locateCode")
                    .datasource("code")
                    .summary("无堆栈或无匹配 packagePrefix 的帧，不给出代码位置")
                    .build();
        }
        return QueryResult.builder()
                .success(true)
                .tool("locateCode")
                .datasource("code")
                .summary("定位到 " + clues.size() + " 处文件/方法线索")
                .items(clues)
                .build();
    }

    public List<String> declaredMetricNames(ProjectProfile profile) {
        if (profile == null || profile.getDatasources() == null) {
            return List.of();
        }
        return profile.getDatasources().getMetrics().stream().map(ProjectProfile.MetricDef::getName).toList();
    }

    private static ProjectProfile.MetricDef findMetric(ProjectProfile profile, String name) {
        if (profile == null || name == null) {
            return null;
        }
        return profile.getDatasources().getMetrics().stream()
                .filter(m -> name.equals(m.getName()) || name.equals(resolveKey(m)))
                .findFirst().orElse(null);
    }

    private static String resolveKey(ProjectProfile.MetricDef def) {
        if (def.getPromQL() == null) {
            return def.getName();
        }
        String q = def.getPromQL();
        if (q.contains("issue_latency")) return "issue_latency_p99";
        if (q.contains("issue_success")) return "issue_success_rate";
        if (q.contains("inventory")) return "inventory_fail";
        if (q.contains("channel_latency")) return "channel_latency";
        if (q.contains("pay_success")) return "pay_success_rate";
        if (q.contains("order_qps")) return "order_qps";
        return def.getName();
    }

    private Map<String, String> normalize(LogRecord record, Map<String, String> fieldMap, String projectId) {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("time", record.getTime());
        raw.put("level", record.getLevel());
        raw.put("service", record.getService());
        raw.put("message", record.getMessage());
        raw.put("stack", record.getStack());
        Map<String, String> out = new LinkedHashMap<>();
        out.put("time", valueOrUnavailable(raw, fieldMap.getOrDefault("time", "time")));
        out.put("level", valueOrUnavailable(raw, fieldMap.getOrDefault("level", "level")));
        out.put("service", valueOrUnavailable(raw, fieldMap.getOrDefault("service", "service")));
        out.put("message", valueOrUnavailable(raw, fieldMap.getOrDefault("message", "message")));
        out.put("project", projectId);
        if (record.getStack() != null) {
            out.put("stack", record.getStack());
        }
        return out;
    }

    private static String valueOrUnavailable(Map<String, String> raw, String key) {
        String value = raw.get(key);
        return value == null || value.isBlank() ? "[不可用]" : value;
    }

    private static Map<String, Object> metricItem(String name, double value, String unit, double threshold,
                                                  String direction, String meaning) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("value", value);
        item.put("unit", unit);
        item.put("threshold", threshold);
        item.put("threshold_direction", direction);
        item.put("meaning", meaning);
        item.put("available", true);
        return item;
    }

    private String windowStart() {
        DiagnosisContext context = DiagnosisContextHolder.get();
        Instant instant = context == null ? Instant.now().minusSeconds(1800) : context.getWindowStart();
        return FMT.format(instant);
    }

    private String windowEnd() {
        DiagnosisContext context = DiagnosisContextHolder.get();
        Instant instant = context == null ? Instant.now() : context.getWindowEnd();
        return FMT.format(instant);
    }
}
