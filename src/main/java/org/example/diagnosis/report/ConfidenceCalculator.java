package org.example.diagnosis.report;

import org.example.diagnosis.memory.EvidenceBlackboard;
import org.example.diagnosis.memory.EvidenceItem;
import org.springframework.stereotype.Component;

@Component
public class ConfidenceCalculator {

    public Result calculate(EvidenceBlackboard board) {
        int score = 3;
        StringBuilder reason = new StringBuilder();
        boolean logFailed = board.getFailedTools().stream().anyMatch(t -> t.toLowerCase().contains("log"));
        boolean metricFailed = board.getFailedTools().stream().anyMatch(t -> t.toLowerCase().contains("metric"));
        long rooted = board.getEvidences().stream()
                .filter(item -> item.getRawRef() != null && !"historical_clue".equals(item.getRole()))
                .count();
        long hypotheses = board.getHypotheses().size();
        if (logFailed) {
            score--;
            reason.append("日志数据源失败；");
        }
        if (metricFailed) {
            score--;
            reason.append("指标数据源失败；");
        }
        if (rooted == 0) {
            score--;
            reason.append("无带 rawRef 的本次取证；");
        }
        if (hypotheses > 2) {
            score--;
            reason.append("候选原因过多；");
        }
        if (board.isTrimmed() || board.isBudgetExhausted()) {
            score = Math.min(score, 2);
            reason.append("发生裁剪或预算终止，置信度不得为高；");
        }
        String level = score >= 3 ? "high" : score == 2 ? "medium" : "low";
        if (reason.isEmpty()) {
            reason.append("关键数据源成功且证据可溯源");
        }
        return new Result(level, reason.toString());
    }

    public boolean canBeRootCause(EvidenceItem item) {
        return item != null && item.getRawRef() != null && !"historical_clue".equals(item.getRole());
    }

    public record Result(String level, String reason) {
    }
}
