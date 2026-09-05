package org.example.diagnosis.report;

import lombok.Builder;
import lombok.Data;
import org.example.diagnosis.context.DiagnosisMode;
import org.example.diagnosis.memory.EvidenceItem;
import org.example.diagnosis.observe.TraceEvent;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class DiagnosisReport {
    private String reportId;
    private String traceId;
    private String generatedAt;
    private DiagnosisMode mode;
    private String projectId;
    private String projectName;
    private String overview;
    @Builder.Default
    private List<String> timeline = new ArrayList<>();
    @Builder.Default
    private List<EvidenceItem> evidenceChain = new ArrayList<>();
    private String rootCause;
    /** high | medium | low */
    private String confidence;
    private String confidenceReason;
    @Builder.Default
    private List<String> pendingClues = new ArrayList<>();
    @Builder.Default
    private List<Suggestion> suggestions = new ArrayList<>();
    @Builder.Default
    private List<TraceEvent> toolSummary = new ArrayList<>();
    @Builder.Default
    private List<String> expansion = new ArrayList<>();
    private String markdown;
    private String relatedReportId;
    private boolean immutable;
    private Feedback feedback;

    @Data
    @Builder
    public static class Suggestion {
        private String text;
        /** document | knowledge | model */
        private String source;
        private String ref;
    }

    @Data
    @Builder
    public static class Feedback {
        private String decision;
        private String reason;
        private String at;
    }
}
