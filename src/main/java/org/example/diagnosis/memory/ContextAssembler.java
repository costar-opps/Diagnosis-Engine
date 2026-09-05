package org.example.diagnosis.memory;

import org.example.diagnosis.DiagnosisProperties;
import org.example.diagnosis.context.DiagnosisContext;
import org.example.diagnosis.context.DiagnosisMode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ContextAssembler {

    private final DiagnosisProperties properties;
    private final ToolResultSummarizer summarizer;

    public ContextAssembler(DiagnosisProperties properties, ToolResultSummarizer summarizer) {
        this.properties = properties;
        this.summarizer = summarizer;
    }

    /**
     * 每轮只注入黑板摘要 + 本步工具摘要，不回放已被覆盖的原文。
     */
    public String assemble(DiagnosisContext context, EvidenceBlackboard board, String latestToolSummary) {
        applyFirewall(context, board);
        String blackboard = board.summaryText();
        String latest = latestToolSummary == null ? "" : latestToolSummary;
        int tokens = summarizer.estimateTokens(blackboard) + summarizer.estimateTokens(latest);
        board.setEstimatedTokens(tokens);
        int budget = properties.getContext().getTokenBudget();
        if (tokens > budget) {
            trim(board);
            board.setBudgetExhausted(true);
            board.setEstimatedTokens(summarizer.estimateTokens(board.summaryText()));
        }
        return "【黑板摘要】\n" + board.summaryText() + "\n【本步工具摘要】\n" + latest;
    }

    public void applyFirewall(DiagnosisContext context, EvidenceBlackboard board) {
        if (context.getMode() == DiagnosisMode.PLATFORM) {
            for (EvidenceItem item : board.getEvidences()) {
                if ("log".equals(item.getCategory()) && item.getProjectScope() != null
                        && !"platform".equals(item.getProjectScope())) {
                    item.setRole("range");
                    if (item.getSummary() != null && !item.getSummary().contains("范围证据")) {
                        item.setSummary(item.getSummary() + "（仅作受影响范围证据，不得作为全平台唯一根因）");
                    }
                }
            }
        }
    }

    public void trim(EvidenceBlackboard board) {
        int keep = properties.getContext().getTrimKeepRecentSteps();
        List<EvidenceItem> evidences = board.getEvidences();
        if (evidences.size() > keep) {
            List<EvidenceItem> dropped = new ArrayList<>(evidences.subList(0, evidences.size() - keep));
            evidences.subList(0, evidences.size() - keep).clear();
            board.setTrimmed(true);
            board.getTrimRecords().add("丢弃已被摘要覆盖的早期证据 " + dropped.size() + " 条，保留最近 " + keep + " 条");
        }
    }
}
