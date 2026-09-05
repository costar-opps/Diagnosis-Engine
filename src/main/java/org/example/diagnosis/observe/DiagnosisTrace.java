package org.example.diagnosis.observe;

import lombok.Data;
import org.example.diagnosis.context.DiagnosisMode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Data
public class DiagnosisTrace {
    private final String traceId;
    private final DiagnosisMode mode;
    private final String projectId;
    private final long startedAt = System.currentTimeMillis();
    private Long endedAt;
    private boolean success;
    private final List<TraceEvent> events = new CopyOnWriteArrayList<>();

    public DiagnosisTrace(String traceId, DiagnosisMode mode, String projectId) {
        this.traceId = traceId;
        this.mode = mode;
        this.projectId = projectId;
    }

    public void add(TraceEvent event) {
        events.add(event);
    }

    public void finish(boolean success) {
        this.success = success;
        this.endedAt = System.currentTimeMillis();
    }

    public List<TraceEvent> orderedEvents() {
        return new ArrayList<>(events);
    }

    public int totalTokens() {
        return events.stream()
                .mapToInt(e -> (e.getInputTokens() == null ? 0 : e.getInputTokens())
                        + (e.getOutputTokens() == null ? 0 : e.getOutputTokens()))
                .sum();
    }
}
