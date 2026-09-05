package org.example.diagnosis.fault;

import org.example.diagnosis.telemetry.DemoTelemetryStore;
import org.example.diagnosis.telemetry.LogRecord;
import org.example.diagnosis.telemetry.MetricSnapshot;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FaultInjector {

    private static final Set<String> KNOWN = Set.of(
            "platform-memory-pressure", "platform-disk-pressure", "card-api-timeout",
            "card-success-drop", "card-change-regression", "card-silent-failure",
            "card-downstream-payment", "order-resource-contention"
    );

    private final DemoTelemetryStore store;
    private final Map<String, Boolean> active = new ConcurrentHashMap<>();

    public FaultInjector(DemoTelemetryStore store) {
        this.store = store;
    }

    public synchronized void inject(String scenarioId) {
        if (!KNOWN.contains(scenarioId)) {
            throw new IllegalArgumentException("未知场景: " + scenarioId);
        }
        store.resetAll();
        active.clear();
        switch (scenarioId) {
            case "platform-memory-pressure" -> injectMemory();
            case "platform-disk-pressure" -> injectDisk();
            case "card-api-timeout" -> injectCardTimeout();
            case "card-success-drop" -> injectCardSuccessDrop();
            case "card-change-regression" -> injectChangeRegression();
            case "card-silent-failure" -> injectSilentFailure();
            case "card-downstream-payment" -> injectDownstream();
            case "order-resource-contention" -> injectContention();
            default -> {
            }
        }
        active.put(scenarioId, true);
    }

    public synchronized void revoke(String scenarioId) {
        store.resetAll();
        active.remove(scenarioId);
    }

    public synchronized void revokeAll() {
        store.resetAll();
        active.clear();
    }

    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", active.keySet().stream().filter(id -> Boolean.TRUE.equals(active.get(id))).toList());
        body.put("platform_memory", store.platform().memoryUsedPercent);
        body.put("platform_disk", store.platform().diskUsedPercent);
        return body;
    }

    public boolean isActive(String scenarioId) {
        return Boolean.TRUE.equals(active.get(scenarioId));
    }

    private void injectMemory() {
        DemoTelemetryStore.PlatformState p = store.platform();
        p.memoryUsedPercent = 93;
        p.memoryOwner = "order-service";
        p.alerts.add("HighMemoryUsage");
        p.injectedScenario = "platform-memory-pressure";
        p.logs.add(log("WARN", "host", "memory usage 93%, top process order-service"));
        store.project("card-service").alerts.add("HostPressureWatch");
        store.project("payment-service").alerts.add("HostPressureWatch");
        store.project("order-service").logs.add(log("WARN", "order-service", "heap allocation spike, cache warm-up unbounded"));
    }

    private void injectDisk() {
        DemoTelemetryStore.PlatformState p = store.platform();
        p.diskUsedPercent = 94;
        p.diskOwner = "shared-runtime";
        p.alerts.add("HighDiskUsage");
        p.injectedScenario = "platform-disk-pressure";
        p.logs.add(log("ERROR", "host", "disk usage 94%, log shipping may fail"));
    }

    private void injectCardTimeout() {
        DemoTelemetryStore.ProjectState card = store.project("card-service");
        setMetric(card, "issue_latency_p99", 2180);
        card.alerts.add("CardApiSlowResponse");
        card.injectedScenario = "card-api-timeout";
        card.logs.add(log("ERROR", "card-service", "issueCard timeout after 2000ms at step=risk-check"));
        card.logs.add(stackLog("ERROR", "card-service",
                "java.util.concurrent.TimeoutException: riskCheck",
                "com.demo.card.service.IssueCardService.issue(IssueCardService.java:88)\n"
                        + "com.demo.card.web.IssueController.create(IssueController.java:41)"));
    }

    private void injectCardSuccessDrop() {
        DemoTelemetryStore.ProjectState card = store.project("card-service");
        setMetric(card, "issue_success_rate", 91.2);
        setMetric(card, "inventory_fail", 37);
        card.alerts.add("CardSuccessRateDrop");
        card.injectedScenario = "card-success-drop";
        card.logs.add(log("ERROR", "card-service", "inventory pre-occupy failed at step=allocate-card-no"));
        card.logs.add(log("ERROR", "card-service", "inventory pre-occupy failed at step=allocate-card-no"));
    }

    private void injectChangeRegression() {
        DemoTelemetryStore.ProjectState card = store.project("card-service");
        setMetric(card, "issue_success_rate", 92.0);
        card.changeAt = DemoTelemetryStore.now();
        card.changeId = "build-1043";
        card.changeSummary = "card-service 发布 v1.4.3，调整超时时间与库存重试";
        card.alerts.add("ErrorSpikeAfterDeploy");
        card.injectedScenario = "card-change-regression";
        card.logs.add(stackLog("ERROR", "card-service",
                "NullPointerException after deploy at retry policy",
                "com.demo.card.service.RetryPolicy.next(RetryPolicy.java:33)\n"
                        + "com.demo.card.service.IssueCardService.issue(IssueCardService.java:101)"));
    }

    private void injectSilentFailure() {
        DemoTelemetryStore.ProjectState card = store.project("card-service");
        setMetric(card, "issue_success_rate", 94.5);
        card.alerts.clear();
        card.injectedScenario = "card-silent-failure";
        card.logs.add(log("ERROR", "card-service", "callback dropped silently, no alert fired"));
    }

    private void injectDownstream() {
        DemoTelemetryStore.ProjectState card = store.project("card-service");
        DemoTelemetryStore.ProjectState pay = store.project("payment-service");
        setMetric(card, "issue_success_rate", 93.1);
        setMetric(pay, "pay_success_rate", 72.0);
        setMetric(pay, "channel_latency", 1800);
        card.alerts.add("CardPayCallbackFailure");
        pay.alerts.add("PaymentChannelTimeout");
        card.injectedScenario = "card-downstream-payment";
        card.logs.add(log("ERROR", "card-service", "downstream payment-service timeout, dependency=payment-service"));
        pay.logs.add(log("ERROR", "payment-service", "channel timeout from unionpay"));
    }

    private void injectContention() {
        injectMemory();
        store.platform().injectedScenario = "order-resource-contention";
        store.platform().memoryOwner = "order-service";
        store.project("card-service").logs.add(log("WARN", "card-service", "gc pause 1.2s, likely host memory contention"));
        store.project("order-service").injectedScenario = "order-resource-contention";
    }

    private static void setMetric(DemoTelemetryStore.ProjectState state, String key, double value) {
        MetricSnapshot snapshot = state.metrics.get(key);
        if (snapshot != null) {
            snapshot.setValue(value);
            snapshot.getSeries().add(value);
        }
    }

    private static LogRecord log(String level, String service, String message) {
        return new LogRecord(DemoTelemetryStore.now(), level, service, message, null);
    }

    private static LogRecord stackLog(String level, String service, String message, String stack) {
        return new LogRecord(DemoTelemetryStore.now(), level, service, message, stack);
    }
}
