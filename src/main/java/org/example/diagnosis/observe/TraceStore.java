package org.example.diagnosis.observe;

import org.example.diagnosis.DiagnosisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Service
public class TraceStore {

    private static final Logger log = LoggerFactory.getLogger(TraceStore.class);

    private final Map<String, DiagnosisTrace> traces = new ConcurrentHashMap<>();
    private final Executor async = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "diagnosis-trace-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final DiagnosisProperties properties;

    public TraceStore(DiagnosisProperties properties) {
        this.properties = properties;
    }

    public void save(DiagnosisTrace trace) {
        if (!properties.getObservability().isEnabled() || trace == null) {
            return;
        }
        async.execute(() -> {
            try {
                traces.put(trace.getTraceId(), trace);
                evict();
            } catch (Exception e) {
                log.warn("轨迹异步写入失败，不影响诊断主流程: {}", e.getMessage());
            }
        });
        traces.put(trace.getTraceId(), trace);
    }

    public Optional<DiagnosisTrace> find(String traceId) {
        evict();
        return Optional.ofNullable(traces.get(traceId));
    }

    private void evict() {
        long retainMs = Duration.ofHours(properties.getObservability().getRetainHours()).toMillis();
        long now = System.currentTimeMillis();
        traces.entrySet().removeIf(entry -> {
            Long ended = entry.getValue().getEndedAt();
            long stamp = ended == null ? entry.getValue().getStartedAt() : ended;
            return now - stamp > retainMs;
        });
    }
}
