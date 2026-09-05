package org.example.diagnosis.memory;

import lombok.Data;
import org.example.diagnosis.context.DiagnosisMode;

import java.util.ArrayList;
import java.util.List;

@Data
public class EvidenceBlackboard {

    private final String diagnosisId;
    private DiagnosisMode mode;
    private String projectId;
    private final List<String> affectedProjects = new ArrayList<>();
    private final List<String> alerts = new ArrayList<>();
    private final List<EvidenceItem> evidences = new ArrayList<>();
    private final List<Hypothesis> hypotheses = new ArrayList<>();
    private final List<String> failedTools = new ArrayList<>();
    private final List<String> trimRecords = new ArrayList<>();
    private int estimatedTokens;
    private boolean budgetExhausted;
    private boolean trimmed;

    public EvidenceBlackboard(String diagnosisId) {
        this.diagnosisId = diagnosisId;
    }

    public void addEvidence(EvidenceItem item) {
        if (item != null) {
            evidences.add(item);
        }
    }

    public void clearBusinessZone() {
        evidences.removeIf(item -> !"platform".equals(item.getCategory()));
        hypotheses.clear();
        alerts.clear();
    }

    public String summaryText() {
        StringBuilder sb = new StringBuilder();
        sb.append("形态=").append(mode).append(" 项目=").append(projectId);
        if (!affectedProjects.isEmpty()) {
            sb.append(" 受影响=").append(affectedProjects);
        }
        sb.append("\n告警: ").append(alerts.isEmpty() ? "无" : String.join("；", alerts));
        sb.append("\n证据:\n");
        for (EvidenceItem item : evidences) {
            sb.append("- [").append(item.getCategory()).append("]");
            if (item.getProjectScope() != null) {
                sb.append(" scope=").append(item.getProjectScope());
            }
            sb.append(" ").append(item.getTitle()).append(" | ").append(item.getSummary());
            if (item.getRawRef() != null) {
                sb.append(" rawRef=").append(item.getRawRef().getQueryId());
            }
            sb.append('\n');
        }
        if (!failedTools.isEmpty()) {
            sb.append("失败工具: ").append(failedTools).append('\n');
        }
        if (!trimRecords.isEmpty()) {
            sb.append("裁剪: ").append(trimRecords).append('\n');
        }
        if (budgetExhausted) {
            sb.append("预算已耗尽，输出部分结论\n");
        }
        return sb.toString();
    }

    @Data
    public static class Hypothesis {
        private String text;
        /** open | supported | rejected */
        private String status = "open";
    }
}
