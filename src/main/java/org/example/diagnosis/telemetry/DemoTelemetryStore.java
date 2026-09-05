package org.example.diagnosis.telemetry;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DemoTelemetryStore {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Map<String, ProjectState> projects = new ConcurrentHashMap<>();
    private final PlatformState platform = new PlatformState();

    public DemoTelemetryStore() {
        resetAll();
    }

    public synchronized void resetAll() {
        projects.clear();
        projects.put("card-service", healthyCard());
        projects.put("payment-service", healthyPayment());
        projects.put("order-service", healthyOrder());
        platform.reset();
    }

    public ProjectState project(String projectId) {
        return projects.computeIfAbsent(projectId, id -> empty(id));
    }

    public PlatformState platform() {
        return platform;
    }

    public Map<String, ProjectState> allProjects() {
        return projects;
    }

    public static String now() {
        return LocalDateTime.now().format(FMT);
    }

    private static ProjectState healthyCard() {
        ProjectState state = empty("card-service");
        state.metrics.put("issue_latency_p99", metric("出卡接口 P99 耗时", 320, "ms", 800, "upper", "用户提交出卡到返回的端到端耗时"));
        state.metrics.put("issue_success_rate", metric("出卡成功率", 99.4, "%", 98, "lower", "出卡关键链路成功率"));
        state.metrics.put("inventory_fail", metric("库存预占失败次数", 1, "次/5min", 8, "upper", "卡号池预占失败"));
        state.changeAvailable = true;
        state.changeId = "build-1042";
        state.changeAt = "2026-09-05 16:30:00";
        state.changeSummary = "card-service 发布 v1.4.2，调整超时与重试";
        return state;
    }

    private static ProjectState healthyPayment() {
        ProjectState state = empty("payment-service");
        state.metrics.put("channel_latency", metric("支付渠道平均响应", 180, "ms", 500, "upper", "调用渠道平均耗时"));
        state.metrics.put("pay_success_rate", metric("支付成功率", 99.7, "%", 98, "lower", "支付回调成功占比"));
        state.changeAvailable = true;
        state.changeId = "build-881";
        state.changeAt = "2026-09-04 21:10:00";
        state.changeSummary = "payment-service 发布渠道超时调优";
        return state;
    }

    private static ProjectState healthyOrder() {
        ProjectState state = empty("order-service");
        state.metrics.put("order_qps", metric("下单 QPS", 12, "qps", 80, "upper", "配角项目流量"));
        state.changeAvailable = false;
        return state;
    }

    private static ProjectState empty(String id) {
        ProjectState state = new ProjectState();
        state.projectId = id;
        return state;
    }

    private static MetricSnapshot metric(String name, double value, String unit, double threshold,
                                         String direction, String meaning) {
        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setName(name);
        snapshot.setValue(value);
        snapshot.setUnit(unit);
        snapshot.setThreshold(threshold);
        snapshot.setThresholdDirection(direction);
        snapshot.setMeaning(meaning);
        snapshot.setAvailable(true);
        snapshot.setSeries(new ArrayList<>(List.of(value, value, value)));
        return snapshot;
    }

    public static class ProjectState {
        public String projectId;
        public final Map<String, MetricSnapshot> metrics = new LinkedHashMap<>();
        public final List<LogRecord> logs = new ArrayList<>();
        public final List<String> alerts = new ArrayList<>();
        public boolean changeAvailable;
        public boolean changeUnreachable;
        public String changeId;
        public String changeAt;
        public String changeSummary;
        public String injectedScenario;
    }

    public static class PlatformState {
        public double memoryUsedPercent = 41;
        public double diskUsedPercent = 48;
        public String memoryOwner = "shared-runtime";
        public String diskOwner = "shared-runtime";
        public final List<LogRecord> logs = new ArrayList<>();
        public final List<String> alerts = new ArrayList<>();
        public String injectedScenario;

        public void reset() {
            memoryUsedPercent = 41;
            diskUsedPercent = 48;
            memoryOwner = "shared-runtime";
            diskOwner = "shared-runtime";
            logs.clear();
            alerts.clear();
            injectedScenario = null;
        }
    }
}
