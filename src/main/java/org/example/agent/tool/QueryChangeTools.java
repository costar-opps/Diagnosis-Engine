package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.diagnosis.access.ScopedDataAccess;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class QueryChangeTools {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScopedDataAccess scopedDataAccess;

    public QueryChangeTools(ScopedDataAccess scopedDataAccess) {
        this.scopedDataAccess = scopedDataAccess;
    }

    @Tool(description = "Query recent change events for a project. Distinguishes no-change from change-source-unreachable.")
    public String queryChanges(@ToolParam(description = "authoritative project id") String projectId) {
        try {
            return objectMapper.writeValueAsString(scopedDataAccess.queryChanges(projectId));
        } catch (Exception e) {
            return "{\"success\":false,\"datasource\":\"change\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    @Tool(description = "Locate file/method code clues from stack frames filtered by the project's packagePrefix.")
    public String locateCode(@ToolParam(description = "authoritative project id") String projectId) {
        try {
            return objectMapper.writeValueAsString(scopedDataAccess.locateCode(projectId));
        } catch (Exception e) {
            return "{\"success\":false,\"datasource\":\"code\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }
}
