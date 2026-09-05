package org.example.diagnosis;

import org.example.diagnosis.fault.FaultInjector;
import org.example.diagnosis.fault.ScenarioCatalog;
import org.example.diagnosis.fault.ScenarioEvaluator;
import org.example.diagnosis.observe.DiagnosisTrace;
import org.example.diagnosis.observe.TraceEvent;
import org.example.diagnosis.report.DiagnosisReport;
import org.example.diagnosis.context.DiagnosisMode;
import org.example.diagnosis.telemetry.DemoTelemetryStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaultAndTraceTest {

    @Test
    void injectAndRevokeRestoreMetrics() {
        DemoTelemetryStore store = new DemoTelemetryStore();
        FaultInjector injector = new FaultInjector(store);
        double before = store.project("card-service").metrics.get("issue_latency_p99").getValue();
        injector.inject("card-api-timeout");
        assertTrue(store.project("card-service").metrics.get("issue_latency_p99").getValue() > 1000);
        injector.revoke("card-api-timeout");
        assertEquals(before, store.project("card-service").metrics.get("issue_latency_p99").getValue());
        assertFalse(injector.isActive("card-api-timeout"));
    }

    @Test
    void catalogCoversFiveCategories() {
        ScenarioCatalog catalog = new ScenarioCatalog();
        assertEquals(8, catalog.all().size());
        assertTrue(catalog.all().stream().anyMatch(s -> "platform_resource".equals(s.getCategory())));
        assertTrue(catalog.all().stream().anyMatch(s -> "project_business".equals(s.getCategory())));
        assertTrue(catalog.all().stream().anyMatch(s -> "change_regression".equals(s.getCategory())));
        assertTrue(catalog.all().stream().anyMatch(s -> "silent".equals(s.getCategory())));
        assertTrue(catalog.all().stream().anyMatch(s -> "cross_project".equals(s.getCategory())));
        assertTrue(catalog.demoSet().size() >= 2);
        assertTrue(catalog.all().stream().anyMatch(s -> "holdout".equals(s.getSet())));
    }

    @Test
    void evaluatorChecksPoints() {
        ScenarioCatalog catalog = new ScenarioCatalog();
        DiagnosisReport report = DiagnosisReport.builder()
                .mode(DiagnosisMode.PLATFORM)
                .rootCause("主机内存瓶颈，占用主体 order-service")
                .overview("受影响项目 card-service")
                .markdown("平台级 内存 占用主体 order-service 受影响项目")
                .expansion(List.of())
                .build();
        var result = new ScenarioEvaluator().evaluate(catalog.find("platform-memory-pressure").orElseThrow(), report);
        assertTrue(result.passed());
    }

    @Test
    void traceCanRebuildSequence() {
        DiagnosisTrace trace = new DiagnosisTrace("tr-1", DiagnosisMode.PROJECT, "card-service");
        trace.add(TraceEvent.builder().type("NODE").name("planner").success(true).durationMs(1).build());
        trace.add(TraceEvent.builder().type("TOOL").name("queryProjectLogs").success(true).durationMs(12).build());
        trace.add(TraceEvent.builder().type("LLM").name("report-composer").inputTokens(10).outputTokens(8).success(true).build());
        assertEquals(3, trace.orderedEvents().size());
        assertEquals("planner", trace.orderedEvents().get(0).getName());
        assertEquals(18, trace.totalTokens());
    }
}
