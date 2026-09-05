package org.example.diagnosis;

import org.example.diagnosis.memory.ContextAssembler;
import org.example.diagnosis.memory.EvidenceBlackboard;
import org.example.diagnosis.memory.EvidenceItem;
import org.example.diagnosis.memory.RawRef;
import org.example.diagnosis.memory.SessionMemoryStore;
import org.example.diagnosis.memory.ToolResultSummarizer;
import org.example.diagnosis.report.ConfidenceCalculator;
import org.example.diagnosis.context.DiagnosisContext;
import org.example.diagnosis.context.DiagnosisMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextMemoryTest {

    @Test
    void summarizeLogsKeepsRawRef() {
        DiagnosisProperties properties = new DiagnosisProperties();
        properties.getContext().setLogSummaryThreshold(2);
        ToolResultSummarizer summarizer = new ToolResultSummarizer(properties);
        List<Map<String, String>> records = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            records.add(Map.of("time", "t" + i, "level", "ERROR", "message", "boom timeout"));
        }
        var result = summarizer.summarizeLogs("card", "demo", "a", "b", records);
        assertTrue(result.summarized());
        assertTrue(result.text().contains("rawRef="));
        assertEquals(5, result.rawRef().getTotalCount());
    }

    @Test
    void switchProjectClearsBusinessNotes() {
        SessionMemoryStore store = new SessionMemoryStore();
        store.getOrCreate("s1").getBusinessNotes().add("card latency");
        store.bindProject("s1", "card-service");
        var result = store.bindProject("s1", "payment-service");
        assertTrue(result.switched());
        assertTrue(store.getOrCreate("s1").getBusinessNotes().isEmpty());
        assertTrue(result.notice().contains("card-service"));
    }

    @Test
    void newBlackboardDoesNotReuseOld() {
        EvidenceBlackboard first = new EvidenceBlackboard("d1");
        first.addEvidence(EvidenceItem.builder().title("old").build());
        EvidenceBlackboard second = new EvidenceBlackboard("d2");
        assertTrue(second.getEvidences().isEmpty());
        assertNotEquals(first.getDiagnosisId(), second.getDiagnosisId());
    }

    @Test
    void budgetTrimLowersConfidence() {
        DiagnosisProperties properties = new DiagnosisProperties();
        properties.getContext().setTokenBudget(10);
        properties.getContext().setTrimKeepRecentSteps(1);
        ContextAssembler assembler = new ContextAssembler(properties, new ToolResultSummarizer(properties));
        EvidenceBlackboard board = new EvidenceBlackboard("d");
        board.setMode(DiagnosisMode.PROJECT);
        board.addEvidence(EvidenceItem.builder().title("e1").summary("x".repeat(80))
                .rawRef(RawRef.builder().queryId("r1").build()).build());
        board.addEvidence(EvidenceItem.builder().title("e2").summary("y".repeat(80))
                .rawRef(RawRef.builder().queryId("r2").build()).build());
        DiagnosisContext context = DiagnosisContext.builder().mode(DiagnosisMode.PROJECT).projectId("card-service").build();
        assembler.assemble(context, board, "tool");
        assertTrue(board.isTrimmed() || board.isBudgetExhausted());
        ConfidenceCalculator.Result confidence = new ConfidenceCalculator().calculate(board);
        assertFalse("high".equals(confidence.level()));
    }
}
