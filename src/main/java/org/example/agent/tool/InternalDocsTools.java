package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.diagnosis.DiagnosisProperties;
import org.example.diagnosis.context.DiagnosisContextHolder;
import org.example.diagnosis.context.DiagnosisMode;
import org.example.diagnosis.knowledge.ScopedKnowledgeStore;
import org.example.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InternalDocsTools {

    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);
    public static final String TOOL_QUERY_INTERNAL_DOCS = "queryInternalDocs";

    private final VectorSearchService vectorSearchService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${rag.top-k:3}")
    private int topK = 3;

    @Autowired(required = false)
    private ScopedKnowledgeStore scopedKnowledgeStore;

    @Autowired(required = false)
    private DiagnosisProperties diagnosisProperties;

    @Autowired
    public InternalDocsTools(VectorSearchService vectorSearchService) {
        this.vectorSearchService = vectorSearchService;
    }

    @Tool(description = "Search internal docs. Diagnosis context filters by project/platform scope. Misses return no_results and never invent sources.")
    public String queryInternalDocs(
            @ToolParam(description = "Search query describing what information you are looking for")
            String query) {
        try {
            if (scopedKnowledgeStore != null) {
                var ctx = DiagnosisContextHolder.get();
                DiagnosisMode mode = ctx == null ? DiagnosisMode.PLATFORM : ctx.getMode();
                String pid = ctx == null ? null : ctx.getProjectId();
                boolean cross = diagnosisProperties != null && diagnosisProperties.isCrossProjectRetrievalEnabled();
                var docs = scopedKnowledgeStore.search(mode, pid, cross, query);
                if (docs.isEmpty()) {
                    return "{\"status\":\"no_results\",\"message\":\"No relevant documents found. Do not invent sources.\"}";
                }
                return objectMapper.writeValueAsString(docs);
            }
            List<VectorSearchService.SearchResult> searchResults =
                    vectorSearchService.searchSimilarDocuments(query, topK);
            if (searchResults.isEmpty()) {
                return "{\"status\": \"no_results\", \"message\": \"No relevant documents found in the knowledge base.\"}";
            }
            return objectMapper.writeValueAsString(searchResults);
        } catch (Exception e) {
            logger.error("[工具错误] queryInternalDocs 执行失败", e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}",
                    e.getMessage());
        }
    }
}
