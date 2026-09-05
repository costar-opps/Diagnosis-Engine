package org.example.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.regex.Pattern;

/**
 * 修正 Agent 误将 Mock 日志主题名（如 system-metrics）当作 CLS topic_id 的问题。
 */
public class ClsMcpToolCallbackDecorator implements ToolCallback {

    private static final Logger logger = LoggerFactory.getLogger(ClsMcpToolCallbackDecorator.class);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ToolCallback delegate;
    private final ClsProperties clsProperties;

    public ClsMcpToolCallbackDecorator(ToolCallback delegate, ClsProperties clsProperties) {
        this.delegate = delegate;
        this.clsProperties = clsProperties;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(fixClsParameters(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(fixClsParameters(toolInput), toolContext);
    }

    private String fixClsParameters(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return toolInput;
        }
        String configuredTopicId = clsProperties.getTopicId();
        if (configuredTopicId == null || configuredTopicId.isBlank()) {
            return toolInput;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(toolInput);
            if (!root.isObject()) {
                return toolInput;
            }
            ObjectNode objectNode = (ObjectNode) root;
            boolean changed = false;

            changed |= fixTopicIdField(objectNode, "topic_id", configuredTopicId);
            changed |= fixTopicIdField(objectNode, "topicId", configuredTopicId);
            changed |= fixTopicIdField(objectNode, "TopicId", configuredTopicId);

            String region = clsProperties.getRegion();
            if (region != null && !region.isBlank()) {
                changed |= fixRegionField(objectNode, "region", region);
                changed |= fixRegionField(objectNode, "Region", region);
            }

            if (changed) {
                String fixed = OBJECT_MAPPER.writeValueAsString(objectNode);
                logger.warn("已自动修正 MCP 工具 {} 的 CLS 参数: {} -> {}",
                        delegate.getToolDefinition().name(), toolInput, fixed);
                return fixed;
            }
        } catch (Exception e) {
            logger.debug("跳过 CLS 参数修正（非 JSON 或解析失败）: {}", e.getMessage());
        }
        return toolInput;
    }

    private boolean fixTopicIdField(ObjectNode node, String fieldName, String configuredTopicId) {
        JsonNode valueNode = node.get(fieldName);
        if (valueNode == null || !valueNode.isTextual()) {
            return false;
        }
        String value = valueNode.asText().trim();
        if (isValidTopicId(value)) {
            return false;
        }
        node.put(fieldName, configuredTopicId);
        return true;
    }

    private boolean fixRegionField(ObjectNode node, String fieldName, String configuredRegion) {
        JsonNode valueNode = node.get(fieldName);
        if (valueNode == null || !valueNode.isTextual()) {
            return false;
        }
        String value = valueNode.asText().trim();
        if (value.isEmpty() || value.contains("_") || configuredRegion.equals(value)) {
            if (!configuredRegion.equals(value)) {
                node.put(fieldName, configuredRegion);
                return true;
            }
            return false;
        }
        return false;
    }

    private static boolean isValidTopicId(String value) {
        return value != null && !value.isBlank() && UUID_PATTERN.matcher(value).matches();
    }
}
