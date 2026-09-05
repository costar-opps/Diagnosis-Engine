package org.example.diagnosis.observe;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TraceEvent {
    /** NODE | LLM | TOOL */
    private String type;
    private String name;
    private String phase;
    private long startedAt;
    private long durationMs;
    private boolean success;
    private String summary;
    private String rejectReason;
    private String model;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer retryCount;
    private String arguments;
}
