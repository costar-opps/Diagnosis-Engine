package org.example.controller;

import lombok.Builder;
import lombok.Data;
import org.example.config.ClsProperties;
import org.example.monitor.AiOpsDashboardData;
import org.example.monitor.AiOpsDashboardService;
import org.example.monitor.ClsTopicVerifier;
import org.example.monitor.HostMetricsCollector;
import org.example.monitor.HostMetricsSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/monitoring")
public class MonitoringController {

    private final HostMetricsCollector hostMetricsCollector;
    private final ClsProperties clsProperties;
    private final ClsTopicVerifier clsTopicVerifier;
    private final AiOpsDashboardService aiOpsDashboardService;

    @Value("${prometheus.base-url}")
    private String prometheusBaseUrl;

    @Value("${prometheus.mock-enabled:false}")
    private boolean prometheusMockEnabled;

    public MonitoringController(HostMetricsCollector hostMetricsCollector,
                              ClsProperties clsProperties,
                              ClsTopicVerifier clsTopicVerifier,
                              AiOpsDashboardService aiOpsDashboardService) {
        this.hostMetricsCollector = hostMetricsCollector;
        this.clsProperties = clsProperties;
        this.clsTopicVerifier = clsTopicVerifier;
        this.aiOpsDashboardService = aiOpsDashboardService;
    }

    @GetMapping("/status")
    public ResponseEntity<MonitoringStatusResponse> status() {
        HostMetricsSnapshot snapshot = hostMetricsCollector.getLatestSnapshot();
        Map<String, Object> checklist = new LinkedHashMap<>();
        checklist.put("prometheus_url", prometheusBaseUrl);
        checklist.put("prometheus_mock_enabled", prometheusMockEnabled);
        checklist.put("cls_mock_enabled", clsProperties.isMockEnabled());
        checklist.put("cls_log_shipping_enabled", clsProperties.getLogShipping().isEnabled());
        checklist.put("cls_topic_configured", !isBlank(clsProperties.getTopicId()));
        checklist.put("cls_credentials_configured", clsProperties.hasCredentials());
        checklist.put("cls_region", clsProperties.getRegion());
        checklist.put("cls_topic_id", clsProperties.getTopicId());
        checklist.put("cls_logset_id", clsProperties.getLogsetId());
        checklist.put("metrics_endpoint", "http://localhost:9900/actuator/prometheus");
        checklist.put("cls_verify_endpoint", "http://localhost:9900/api/monitoring/cls-verify");
        checklist.put("next_steps", buildNextSteps());

        return ResponseEntity.ok(MonitoringStatusResponse.builder()
                .hostMetrics(snapshot)
                .checklist(checklist)
                .build());
    }

    private String buildNextSteps() {
        if (prometheusMockEnabled) {
            return "将 prometheus.mock-enabled 设为 false，并启动 monitoring/docker-compose.yml 中的 Prometheus";
        }
        if (!clsProperties.getLogShipping().isEnabled()) {
            return "将 cls.log-shipping.enabled 设为 true，并填写 cls.topic-id 与密钥";
        }
        if (isBlank(clsProperties.getTopicId())) {
            return "请在腾讯云 CLS 控制台创建日志主题，并把 TopicId 填入 application.yml";
        }
        return "启动 cls-mcp-server 后，可在网页中使用 AI Ops 或对话查询真实告警与日志";
    }

    @GetMapping("/cls-verify")
    public ResponseEntity<ClsTopicVerifier.ClsTopicVerifyResult> verifyClsTopic() {
        return ResponseEntity.ok(clsTopicVerifier.verifyConfiguredTopic());
    }

    /**
     * AI Ops 可视化仪表盘数据：当前指标、Prometheus 告警（含触发时间）、近 N 分钟时序曲线。
     */
    @GetMapping("/aiops-dashboard")
    public ResponseEntity<AiOpsDashboardData> aiOpsDashboard(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "60") int rangeMinutes) {
        return ResponseEntity.ok(aiOpsDashboardService.buildDashboard(rangeMinutes));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Data
    @Builder
    public static class MonitoringStatusResponse {
        private HostMetricsSnapshot hostMetrics;
        private Map<String, Object> checklist;
    }
}
