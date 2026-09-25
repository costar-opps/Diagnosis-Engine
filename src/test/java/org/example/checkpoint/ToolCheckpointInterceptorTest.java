package org.example.checkpoint;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import org.example.agent.observability.ToolCallProgressInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolCheckpointInterceptorTest {
    @TempDir
    Path tempDir;

    @Test
    void successfulToolIsSkippedDuringRecovery() {
        CheckpointProperties properties = new CheckpointProperties();
        properties.setDirectory(tempDir.toString());
        properties.setHmacSecret("test-checkpoint-secret-with-more-than-32-characters");
        SessionIdentityService identities = new SessionIdentityService(properties);
        CheckpointService checkpoints = new CheckpointService(
                new FileCheckpointStore(new ObjectMapper(), properties), identities, properties);
        var owner = identities.issue();
        CheckpointRecord record = checkpoints.create(owner.sessionId(), owner.sessionToken(), "CHAT",
                CheckpointRecord.CHAT_WORKFLOW, "query", List.of());
        ToolCallRequest firstRequest = new ToolCallRequest("queryMetrics", "{}", "call-1", Map.of());
        AtomicInteger invocations = new AtomicInteger();

        ToolCallProgressInterceptor first = new ToolCallProgressInterceptor(ignored -> { },
                checkpoints, record, null);
        ToolCallResponse initial = first.interceptToolCall(firstRequest, request -> {
            invocations.incrementAndGet();
            return ToolCallResponse.of(request.getToolCallId(), request.getToolName(), "{\"cpu\":80}");
        });

        CheckpointRecord.RecoveryAudit audit = checkpoints.beginRecovery(record);
        ToolCallProgressInterceptor resumed = new ToolCallProgressInterceptor(ignored -> { },
                checkpoints, record, audit);
        ToolCallRequest resumedRequest = new ToolCallRequest("queryMetrics", "{}", "call-2", Map.of());
        ToolCallResponse cached = resumed.interceptToolCall(resumedRequest, request -> {
            invocations.incrementAndGet();
            return ToolCallResponse.of(request.getToolCallId(), request.getToolName(), "unexpected");
        });

        assertEquals(1, invocations.get());
        assertEquals(initial.getResult(), cached.getResult());
        assertEquals("call-2", cached.getToolCallId());
        assertEquals(List.of("queryMetrics"), audit.getSkippedTools());
    }
}
