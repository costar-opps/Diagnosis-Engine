package org.example.checkpoint;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class CheckpointService {
    private final FileCheckpointStore store;
    private final SessionIdentityService identities;
    private final CheckpointProperties properties;

    public CheckpointService(FileCheckpointStore store, SessionIdentityService identities,
                             CheckpointProperties properties) {
        this.store = store;
        this.identities = identities;
        this.properties = properties;
    }

    public CheckpointRecord create(String sessionId, String token, String type, String workflow,
                                   String intent, List<Map<String, String>> recentConversation) {
        identities.requireValid(sessionId, token);
        CheckpointRecord record = new CheckpointRecord();
        record.setTaskId("task-" + UUID.randomUUID());
        record.setSessionId(sessionId);
        record.setTaskType(type);
        record.setWorkflowVersion(workflow);
        record.setCoreIntent(intent);
        record.setPhase("CREATED");
        record.setRecentConversation(recentConversation == null ? List.of() : recentConversation);
        store.save(record);
        return record;
    }

    public synchronized CheckpointRecord require(String sessionId, String token, String taskId,
                                                 String expectedWorkflow) {
        identities.requireValid(sessionId, token);
        CheckpointRecord record = store.find(sessionId, taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        validateVersion(record, expectedWorkflow);
        boolean changed = false;
        for (PendingTool tool : record.getPendingTools()) {
            if (tool.getStatus() == PendingTool.Status.RUNNING) {
                tool.setStatus(PendingTool.Status.UNKNOWN);
                tool.setUpdatedAt(System.currentTimeMillis());
                changed = true;
            }
        }
        if (changed) {
            store.save(record);
        }
        return record;
    }

    public synchronized void update(CheckpointRecord record, Consumer<CheckpointRecord> change) {
        change.accept(record);
        store.save(record);
    }

    public void appendOutput(CheckpointRecord record, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        update(record, current -> current.setPartialOutput(current.getPartialOutput() + chunk));
    }

    public PendingTool beforeTool(CheckpointRecord record, String name, String arguments) {
        String key = toolKey(name, arguments);
        Optional<PendingTool> existing = record.getPendingTools().stream()
                .filter(tool -> key.equals(tool.getKey())).findFirst();
        PendingTool tool = existing.orElseGet(() -> {
            PendingTool created = new PendingTool(key, name, arguments);
            record.getPendingTools().add(created);
            return created;
        });
        update(record, current -> {
            tool.setStatus(PendingTool.Status.RUNNING);
            tool.setAttempts(tool.getAttempts() + 1);
            tool.setUpdatedAt(System.currentTimeMillis());
        });
        return tool;
    }

    public void toolSucceeded(CheckpointRecord record, PendingTool tool, String result) {
        update(record, current -> {
            tool.setStatus(PendingTool.Status.SUCCEEDED);
            tool.setResult(result);
            tool.setError(null);
            tool.setUpdatedAt(System.currentTimeMillis());
        });
    }

    public void toolFailed(CheckpointRecord record, PendingTool tool, String error) {
        update(record, current -> {
            tool.setStatus(PendingTool.Status.FAILED);
            tool.setError(error);
            tool.setUpdatedAt(System.currentTimeMillis());
        });
    }

    public boolean mayRetry(PendingTool tool) {
        return tool.getAttempts() <= properties.getMaxFailedRetries();
    }

    public Optional<PendingTool> findTool(CheckpointRecord record, String name, String arguments) {
        String key = toolKey(name, arguments);
        return record.getPendingTools().stream().filter(tool -> key.equals(tool.getKey())).findFirst();
    }

    public CheckpointRecord.RecoveryAudit beginRecovery(CheckpointRecord record) {
        CheckpointRecord.RecoveryAudit audit = new CheckpointRecord.RecoveryAudit();
        audit.setSourceTaskId(record.getTaskId());
        audit.setStartedAt(System.currentTimeMillis());
        update(record, current -> {
            current.setRecoveryCount(current.getRecoveryCount() + 1);
            current.getRecoveryAudit().add(audit);
            current.setStatus(CheckpointRecord.Status.RUNNING);
            current.setPhase("RECOVERING");
        });
        return audit;
    }

    public void finishRecovery(CheckpointRecord record, CheckpointRecord.RecoveryAudit audit,
                               String result, String message) {
        update(record, current -> {
            audit.setEndedAt(System.currentTimeMillis());
            audit.setResult(result);
            audit.setMessage(message);
        });
    }

    public List<CheckpointRecord> latest(String sessionId, String token, int limit) {
        identities.requireValid(sessionId, token);
        return store.latest(sessionId, limit);
    }

    public CheckpointRecord requireOwned(String sessionId, String token, String taskId) {
        identities.requireValid(sessionId, token);
        CheckpointRecord record = store.find(sessionId, taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        if (record.getSchemaVersion() != CheckpointRecord.SCHEMA_VERSION) {
            throw new CheckpointVersionException("checkpoint 状态版本不兼容");
        }
        return record;
    }

    public static void validateVersion(CheckpointRecord record, String expectedWorkflow) {
        if (record.getSchemaVersion() != CheckpointRecord.SCHEMA_VERSION) {
            throw new CheckpointVersionException("checkpoint 状态版本不兼容");
        }
        if (!expectedWorkflow.equals(record.getWorkflowVersion())) {
            throw new CheckpointVersionException("checkpoint 工作流版本不兼容");
        }
    }

    public static String toolKey(String name, String arguments) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((name + "\n" + (arguments == null ? "" : arguments.trim()))
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成工具幂等键", e);
        }
    }

    public static class CheckpointVersionException extends IllegalStateException {
        public CheckpointVersionException(String message) {
            super(message);
        }
    }
}
