package org.example.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Data
@Component
@ConfigurationProperties(prefix = "cls")
public class ClsProperties {

    private static final Logger logger = LoggerFactory.getLogger(ClsProperties.class);

    private String region = "ap-guangzhou";

    private String topicId = "";

    private String secretId = "";

    private String secretKey = "";

    private String logsetId = "";

    private String topicName = "local-monitor";

    private boolean mockEnabled = false;

    private LogShipping logShipping = new LogShipping();

    @PostConstruct
    public void applyEnvFallback() {
        Map<String, String> envFileValues = loadClsEnvFile();
        if (isBlank(secretId)) {
            secretId = firstNonBlank(
                    envFileValues.get("TENCENTCLOUD_SECRET_ID"),
                    System.getenv("TENCENTCLOUD_SECRET_ID"));
        }
        if (isBlank(secretKey)) {
            secretKey = firstNonBlank(
                    envFileValues.get("TENCENTCLOUD_SECRET_KEY"),
                    System.getenv("TENCENTCLOUD_SECRET_KEY"));
        }
        if (isBlank(topicId)) {
            topicId = firstNonBlank(
                    envFileValues.get("CLS_TOPIC_ID"),
                    System.getenv("CLS_TOPIC_ID"));
        }
        if (isBlank(logsetId)) {
            logsetId = firstNonBlank(
                    envFileValues.get("CLS_LOGSET_ID"),
                    System.getenv("CLS_LOGSET_ID"));
        }
        String regionFromEnv = firstNonBlank(
                envFileValues.get("CLS_REGION"),
                System.getenv("CLS_REGION"));
        if (!isBlank(regionFromEnv)) {
            region = regionFromEnv.trim();
        }
        String topicNameFromEnv = firstNonBlank(
                envFileValues.get("CLS_TOPIC_NAME"),
                System.getenv("CLS_TOPIC_NAME"));
        if (!isBlank(topicNameFromEnv)) {
            topicName = topicNameFromEnv.trim();
        }
        if (!logShipping.isEnabled()) {
            String enabled = firstNonBlank(
                    envFileValues.get("CLS_LOG_SHIPPING_ENABLED"),
                    System.getenv("CLS_LOG_SHIPPING_ENABLED"));
            if ("true".equalsIgnoreCase(enabled)) {
                logShipping.setEnabled(true);
            }
        }

        logger.info("CLS 配置加载完成: topicId={}, credentials={}, logShipping={}",
                mask(topicId),
                hasCredentials() ? "已配置" : "未配置",
                logShipping.isEnabled());
    }

    public boolean hasCredentials() {
        return !isBlank(secretId) && !isBlank(secretKey);
    }

    /**
     * 注入 Agent 提示词，避免把 Mock 里的 system-metrics 误当成 CLS topic_id。
     */
    public String buildMcpQueryInstructions() {
        String serviceName = logShipping.getServiceName();
        return """
                
                【CLS MCP 日志查询 - 必遵参数】
                - region: %s
                - topic_id: %s （必须是 UUID，禁止填 system-metrics / host-metrics / application-logs）
                - 日志主题名称: %s
                - 检索语法示例: service:%s AND log_type:system-metrics
                - 内存告警可搜: memory_usage:>85 或 level:WARN
                - TextToSearchLogQuery / GetTopicInfoByName 等 MCP 工具的 topic_id 参数只能填上面的 UUID
                """.formatted(region, topicId, topicName, serviceName);
    }

    private Map<String, String> loadClsEnvFile() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Path candidate : List.of(Paths.get("cls.env"), Paths.get(System.getProperty("user.dir"), "cls.env"))) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try (Stream<String> lines = Files.lines(candidate.toAbsolutePath().normalize(), StandardCharsets.UTF_8)) {
                lines.map(this::stripBom)
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .forEach(line -> {
                            int separatorIndex = line.indexOf('=');
                            if (separatorIndex <= 0) {
                                return;
                            }
                            String key = line.substring(0, separatorIndex).trim();
                            String value = line.substring(separatorIndex + 1).trim();
                            if (!key.isEmpty()) {
                                values.put(key, value);
                            }
                        });
                logger.info("已从 {} 读取 CLS 环境配置", candidate.toAbsolutePath().normalize());
                return values;
            } catch (IOException e) {
                logger.warn("读取 cls.env 失败: {}", e.getMessage());
            }
        }
        return values;
    }

    @Data
    public static class LogShipping {
        private boolean enabled = false;
        private int intervalSeconds = 60;
        private String serviceName = "local-host";
    }

    private String stripBom(String line) {
        if (line != null && !line.isEmpty() && line.charAt(0) == '\uFEFF') {
            return line.substring(1);
        }
        return line;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static String mask(String value) {
        if (isBlank(value) || value.length() <= 8) {
            return value;
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }
}
