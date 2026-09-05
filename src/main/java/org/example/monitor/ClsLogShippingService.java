package org.example.monitor;

import com.tencentcloudapi.cls.producer.AsyncProducerClient;
import com.tencentcloudapi.cls.producer.AsyncProducerConfig;
import com.tencentcloudapi.cls.producer.common.LogItem;
import jakarta.annotation.PreDestroy;
import org.example.config.ClsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
@ConditionalOnProperty(prefix = "cls.log-shipping", name = "enabled", havingValue = "true")
public class ClsLogShippingService {

    private static final Logger logger = LoggerFactory.getLogger(ClsLogShippingService.class);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final ClsProperties clsProperties;
    private final HostMetricsCollector hostMetricsCollector;
    private final AtomicReference<AsyncProducerClient> producerClient = new AtomicReference<>();

    public ClsLogShippingService(ClsProperties clsProperties, HostMetricsCollector hostMetricsCollector) {
        this.clsProperties = clsProperties;
        this.hostMetricsCollector = hostMetricsCollector;
        logger.info("CLS 日志上报模块已加载，region={}, topicId={}, credentials={}",
                clsProperties.getRegion(),
                maskTopicId(clsProperties.getTopicId()),
                hasCredentials(clsProperties) ? "已配置" : "未配置");
    }

    @Scheduled(fixedDelayString = "#{${cls.log-shipping.interval-seconds:60} * 1000}")
    public void shipMetricsLog() {
        if (!hasCredentials(clsProperties)) {
            logger.warn("CLS 日志上报跳过：请在 cls.env 中配置 TENCENTCLOUD_SECRET_ID / TENCENTCLOUD_SECRET_KEY");
            return;
        }
        if (isBlank(clsProperties.getTopicId())) {
            logger.warn("CLS 日志上报跳过：请配置 CLS_TOPIC_ID");
            return;
        }

        HostMetricsSnapshot snapshot = hostMetricsCollector.getLatestSnapshot();
        try {
            AsyncProducerClient client = getOrCreateProducerClient();
            uploadSystemMetricsLog(client, snapshot);
            logger.debug("CLS 系统指标日志上报成功: cpu={}%, memory={}%, disk={}%",
                    snapshot.getCpuUsagePercent(),
                    snapshot.getMemoryUsagePercent(),
                    snapshot.getDiskUsagePercent());
        } catch (Exception e) {
            logger.error("CLS 日志上报失败: {} (region={}, topicId={})",
                    e.getMessage(),
                    clsProperties.getRegion(),
                    maskTopicId(clsProperties.getTopicId()));
            if (e.getMessage() != null && e.getMessage().contains("topic not exists")) {
                logger.error("请访问 http://localhost:9900/api/monitoring/cls-verify 查看 Topic 诊断结果");
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        AsyncProducerClient client = producerClient.getAndSet(null);
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            logger.warn("关闭 CLS Producer 失败: {}", e.getMessage());
        }
    }

    private AsyncProducerClient getOrCreateProducerClient() {
        AsyncProducerClient existing = producerClient.get();
        if (existing != null) {
            return existing;
        }
        synchronized (producerClient) {
            existing = producerClient.get();
            if (existing != null) {
                return existing;
            }
            AsyncProducerClient created = createProducerClient(clsProperties);
            producerClient.set(created);
            logger.info("CLS Producer 客户端已创建，topicId={}", maskTopicId(clsProperties.getTopicId()));
            return created;
        }
    }

    private void uploadSystemMetricsLog(AsyncProducerClient client, HostMetricsSnapshot snapshot) throws Exception {
        String serviceName = clsProperties.getLogShipping().getServiceName();
        String level = resolveLevel(snapshot);

        LogItem logItem = new LogItem(snapshot.getCollectedAtEpochMs() / 1000);
        logItem.PushBack("level", level);
        logItem.PushBack("service", serviceName);
        logItem.PushBack("hostname", snapshot.getHostname());
        logItem.PushBack("log_type", "system-metrics");
        logItem.PushBack("cpu_usage", String.valueOf(snapshot.getCpuUsagePercent()));
        logItem.PushBack("memory_usage", String.valueOf(snapshot.getMemoryUsagePercent()));
        logItem.PushBack("disk_usage", String.valueOf(snapshot.getDiskUsagePercent()));
        logItem.PushBack("message", buildMessage(snapshot));
        logItem.PushBack("timestamp", FORMATTER.format(Instant.ofEpochMilli(snapshot.getCollectedAtEpochMs())));

        client.putLogs(clsProperties.getTopicId(), List.of(logItem));
    }

    private String resolveLevel(HostMetricsSnapshot snapshot) {
        if (snapshot.getCpuUsagePercent() >= 80
                || snapshot.getMemoryUsagePercent() >= 85
                || snapshot.getDiskUsagePercent() >= 90) {
            return "WARN";
        }
        return "INFO";
    }

    private String buildMessage(HostMetricsSnapshot snapshot) {
        return String.format(
                "Host metrics on %s: CPU=%.1f%%, Memory=%.1f%%, Disk=%.1f%%",
                snapshot.getHostname(),
                snapshot.getCpuUsagePercent(),
                snapshot.getMemoryUsagePercent(),
                snapshot.getDiskUsagePercent());
    }

    private static AsyncProducerClient createProducerClient(ClsProperties properties) {
        String endpoint = properties.getRegion() + ".cls.tencentcs.com";
        AsyncProducerConfig config = new AsyncProducerConfig(
                endpoint,
                properties.getSecretId(),
                properties.getSecretKey(),
                properties.getTopicId());
        return new AsyncProducerClient(config);
    }

    private static boolean hasCredentials(ClsProperties properties) {
        return properties.hasCredentials();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String maskTopicId(String topicId) {
        if (isBlank(topicId) || topicId.length() <= 8) {
            return topicId;
        }
        return topicId.substring(0, 4) + "****" + topicId.substring(topicId.length() - 4);
    }
}
