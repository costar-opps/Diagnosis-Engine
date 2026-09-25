package org.example.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CheckpointServiceTest {
    @TempDir
    Path tempDir;

    private CheckpointProperties properties;
    private SessionIdentityService identities;
    private FileCheckpointStore store;
    private CheckpointService service;

    @BeforeEach
    void setUp() {
        properties = new CheckpointProperties();
        properties.setDirectory(tempDir.toString());
        properties.setHmacSecret("test-checkpoint-secret-with-more-than-32-characters");
        properties.setMaxFailedRetries(2);
        identities = new SessionIdentityService(properties);
        store = new FileCheckpointStore(new ObjectMapper(), properties);
        service = new CheckpointService(store, identities, properties);
    }

    @Test
    void signedSessionOwnsTaskAndRejectsOtherSession() {
        var owner = identities.issue();
        var attacker = identities.issue();
        CheckpointRecord record = service.create(owner.sessionId(), owner.sessionToken(), "CHAT",
                CheckpointRecord.CHAT_WORKFLOW, "hello", List.of());

        assertEquals(record.getTaskId(), service.require(owner.sessionId(), owner.sessionToken(),
                record.getTaskId(), CheckpointRecord.CHAT_WORKFLOW).getTaskId());
        assertThrows(IllegalArgumentException.class, () -> service.require(attacker.sessionId(),
                attacker.sessionToken(), record.getTaskId(), CheckpointRecord.CHAT_WORKFLOW));
        assertThrows(SecurityException.class, () -> service.require(owner.sessionId(), "bad-token",
                record.getTaskId(), CheckpointRecord.CHAT_WORKFLOW));
    }

    @Test
    void reloadsOnlyCommittedFileAndTurnsRunningToolUnknown() {
        var owner = identities.issue();
        CheckpointRecord record = service.create(owner.sessionId(), owner.sessionToken(), "AIOPS",
                CheckpointRecord.AIOPS_WORKFLOW, "diagnose", List.of());
        PendingTool tool = service.beforeTool(record, "queryLogs", "{\"range\":\"5m\"}");

        FileCheckpointStore restartedStore = new FileCheckpointStore(new ObjectMapper(), properties);
        CheckpointService restarted = new CheckpointService(restartedStore, identities, properties);
        CheckpointRecord loaded = restarted.require(owner.sessionId(), owner.sessionToken(),
                record.getTaskId(), CheckpointRecord.AIOPS_WORKFLOW);

        assertEquals(PendingTool.Status.UNKNOWN, loaded.getPendingTools().get(0).getStatus());
        assertEquals(tool.getKey(), loaded.getPendingTools().get(0).getKey());
    }

    @Test
    void validatesWorkflowAndRecordsRecoveryAudit() {
        var owner = identities.issue();
        CheckpointRecord record = service.create(owner.sessionId(), owner.sessionToken(), "CHAT",
                CheckpointRecord.CHAT_WORKFLOW, "hello", List.of());

        assertThrows(CheckpointService.CheckpointVersionException.class,
                () -> service.require(owner.sessionId(), owner.sessionToken(), record.getTaskId(),
                        CheckpointRecord.AIOPS_WORKFLOW));

        CheckpointRecord.RecoveryAudit audit = service.beginRecovery(record);
        audit.getSkippedTools().add("queryMetrics");
        service.finishRecovery(record, audit, "COMPLETED", "ok");
        CheckpointRecord loaded = store.find(owner.sessionId(), record.getTaskId()).orElseThrow();
        assertEquals(1, loaded.getRecoveryCount());
        assertEquals("COMPLETED", loaded.getRecoveryAudit().get(0).getResult());
        assertNotNull(loaded.getRecoveryAudit().get(0).getEndedAt());
    }

    @Test
    void persistsSucceededAndFailedToolStates() {
        var owner = identities.issue();
        CheckpointRecord record = service.create(owner.sessionId(), owner.sessionToken(), "CHAT",
                CheckpointRecord.CHAT_WORKFLOW, "hello", List.of());

        PendingTool succeeded = service.beforeTool(record, "queryMetrics", "{}");
        service.toolSucceeded(record, succeeded, "{\"value\":42}");
        PendingTool failed = service.beforeTool(record, "queryLogs", "{}");
        service.toolFailed(record, failed, "timeout");

        CheckpointRecord loaded = store.find(owner.sessionId(), record.getTaskId()).orElseThrow();
        assertEquals(PendingTool.Status.SUCCEEDED, loaded.getPendingTools().get(0).getStatus());
        assertEquals(PendingTool.Status.FAILED, loaded.getPendingTools().get(1).getStatus());
        assertTrue(service.mayRetry(loaded.getPendingTools().get(1)));
    }
}
