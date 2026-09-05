package org.example.diagnosis.access;

import lombok.Builder;
import lombok.Data;
import org.example.diagnosis.memory.RawRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class QueryResult {
    private boolean success;
    private String tool;
    private String datasource;
    private String message;
    private String summary;
    private RawRef rawRef;
    @Builder.Default
    private List<Map<String, Object>> items = new ArrayList<>();
    @Builder.Default
    private Map<String, Object> extra = new LinkedHashMap<>();

    public static QueryResult fail(String tool, String datasource, String message) {
        return QueryResult.builder()
                .success(false)
                .tool(tool)
                .datasource(datasource)
                .message(message)
                .summary(message)
                .build();
    }
}
