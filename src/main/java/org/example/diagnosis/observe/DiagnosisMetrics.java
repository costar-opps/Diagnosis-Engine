package org.example.diagnosis.observe;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.example.diagnosis.context.DiagnosisMode;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class DiagnosisMetrics {

    private final MeterRegistry registry;

    public DiagnosisMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordCompleted(DiagnosisMode mode, String projectId, boolean success, long durationMs,
                                int toolCalls, int tokens, int toolFailures) {
        String modeTag = mode == null ? "unknown" : mode.name().toLowerCase();
        String projectTag = projectId == null || projectId.isBlank() ? "platform" : sanitize(projectId);
        Counter.builder("diagnosis_runs_total")
                .tag("mode", modeTag)
                .tag("project", projectTag)
                .tag("result", success ? "success" : "failure")
                .register(registry)
                .increment();
        Timer.builder("diagnosis_duration_ms")
                .tag("mode", modeTag)
                .tag("project", projectTag)
                .register(registry)
                .record(Duration.ofMillis(Math.max(0, durationMs)));
        Counter.builder("diagnosis_tool_calls_total")
                .tag("mode", modeTag)
                .tag("project", projectTag)
                .register(registry)
                .increment(toolCalls);
        Counter.builder("diagnosis_tokens_total")
                .tag("mode", modeTag)
                .tag("project", projectTag)
                .register(registry)
                .increment(tokens);
        if (toolFailures > 0) {
            Counter.builder("diagnosis_tool_failures_total")
                    .tag("mode", modeTag)
                    .tag("project", projectTag)
                    .register(registry)
                    .increment(toolFailures);
        }
    }

    private static String sanitize(String projectId) {
        return projectId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
