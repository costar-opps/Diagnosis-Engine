package org.example.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 启动时自动加载项目根目录 cls.env，避免 IDE 直接运行时读不到环境变量。
 */
public class ClsEnvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "clsEnvFile";

    @Override
    public int getOrder() {
        // 在 application.yml 加载完成后再注入，避免被空占位符覆盖
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path envFile = resolveEnvFile();
        if (!Files.isRegularFile(envFile)) {
            return;
        }

        Map<String, Object> properties = loadEnvFile(envFile);
        if (properties.isEmpty()) {
            return;
        }

        mapClsProperties(properties);
        mapDashScopeProperties(properties);
        PropertySource<?> propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, properties);
        environment.getPropertySources().addFirst(propertySource);
    }

    private void mapClsProperties(Map<String, Object> properties) {
        copyProperty(properties, "TENCENTCLOUD_SECRET_ID", "cls.secret-id");
        copyProperty(properties, "TENCENTCLOUD_SECRET_KEY", "cls.secret-key");
        copyProperty(properties, "CLS_TOPIC_ID", "cls.topic-id");
        copyProperty(properties, "CLS_LOG_SHIPPING_ENABLED", "cls.log-shipping.enabled");
    }

    private void mapDashScopeProperties(Map<String, Object> properties) {
        copyProperty(properties, "DASHSCOPE_API_KEY", "spring.ai.dashscope.api-key");
        copyProperty(properties, "DASHSCOPE_API_KEY", "dashscope.api.key");
    }

    private void copyProperty(Map<String, Object> properties, String sourceKey, String targetKey) {
        Object value = properties.get(sourceKey);
        if (value != null && !value.toString().isBlank()) {
            properties.put(targetKey, value);
        }
    }

    private Path resolveEnvFile() {
        List<Path> candidates = List.of(
                Paths.get("cls.env"),
                Paths.get(System.getProperty("user.dir"), "cls.env")
        );

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return candidates.get(0);
    }

    private Map<String, Object> loadEnvFile(Path envFile) {
        Map<String, Object> properties = new LinkedHashMap<>();
        try (Stream<String> lines = Files.lines(envFile, StandardCharsets.UTF_8)) {
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
                            properties.put(key, value);
                        }
                    });
        } catch (IOException ignored) {
            return Map.of();
        }
        return properties;
    }

    private String stripBom(String line) {
        if (line != null && !line.isEmpty() && line.charAt(0) == '\uFEFF') {
            return line.substring(1);
        }
        return line;
    }
}
