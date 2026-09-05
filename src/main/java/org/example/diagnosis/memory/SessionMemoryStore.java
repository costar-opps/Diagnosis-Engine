package org.example.diagnosis.memory;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionMemoryStore {

    private final Map<String, SessionMemory> sessions = new ConcurrentHashMap<>();

    public SessionMemory getOrCreate(String sessionId) {
        String key = sessionId == null || sessionId.isBlank() ? "anonymous" : sessionId;
        return sessions.computeIfAbsent(key, SessionMemory::new);
    }

    /**
     * 切换项目时清空业务证据，不沿用前一项目语义。
     */
    public SwitchResult bindProject(String sessionId, String projectId) {
        SessionMemory memory = getOrCreate(sessionId);
        String previous = memory.getBoundProjectId();
        boolean switched = previous != null && projectId != null && !previous.equals(projectId);
        if (switched) {
            memory.getBusinessNotes().clear();
            memory.setLastForeignConclusion("上一轮结论属于项目 " + previous + "，不能直接作为 "
                    + projectId + " 的依据，需确认后才能引用");
        }
        memory.setBoundProjectId(projectId);
        return new SwitchResult(switched, previous, memory.getLastForeignConclusion());
    }

    public record SwitchResult(boolean switched, String previousProjectId, String notice) {
    }

    public static class SessionMemory {
        private final String sessionId;
        private String boundProjectId;
        private String lastForeignConclusion;
        private final java.util.List<String> businessNotes = new java.util.ArrayList<>();

        public SessionMemory(String sessionId) {
            this.sessionId = sessionId;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getBoundProjectId() {
            return boundProjectId;
        }

        public void setBoundProjectId(String boundProjectId) {
            this.boundProjectId = boundProjectId;
        }

        public String getLastForeignConclusion() {
            return lastForeignConclusion;
        }

        public void setLastForeignConclusion(String lastForeignConclusion) {
            this.lastForeignConclusion = lastForeignConclusion;
        }

        public java.util.List<String> getBusinessNotes() {
            return businessNotes;
        }
    }
}
