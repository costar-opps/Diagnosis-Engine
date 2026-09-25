package org.example.checkpoint;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class CheckpointRecord {
    public static final int SCHEMA_VERSION = 1;
    public static final String CHAT_WORKFLOW = "CHAT_V1";
    public static final String AIOPS_WORKFLOW = "AIOPS_V1";
    public static final String DIAGNOSIS_WORKFLOW = "DIAGNOSIS_V1";

    public enum Status { RUNNING, COMPLETED, FAILED }

    private int schemaVersion = SCHEMA_VERSION;
    private String workflowVersion;
    private String taskId;
    private String sessionId;
    private String taskType;
    private Status status = Status.RUNNING;
    private String phase;
    private String coreIntent;
    private List<Map<String, String>> recentConversation = new ArrayList<>();
    private Map<String, Object> taskState = new LinkedHashMap<>();
    private List<PendingTool> pendingTools = new ArrayList<>();
    private String partialOutput = "";
    private String finalOutput;
    private long createdAt = System.currentTimeMillis();
    private long updatedAt = createdAt;
    private int recoveryCount;
    private List<RecoveryAudit> recoveryAudit = new ArrayList<>();

    @Data
    public static class RecoveryAudit {
        private String sourceTaskId;
        private long startedAt;
        private Long endedAt;
        private String result;
        private List<String> skippedTools = new ArrayList<>();
        private List<String> retriedTools = new ArrayList<>();
        private List<String> verifiedTools = new ArrayList<>();
        private String message;
    }
}
