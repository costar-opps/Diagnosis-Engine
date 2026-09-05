package org.example.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.example.agent.tool.QueryMetricsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiOpsDashboardService {

    private static final Logger logger = LoggerFactory.getLogger(AiOpsDashboardService.class);
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DISPLAY_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(DISPLAY_ZONE);

    private static final Map<String, String> METRIC_QUERIES = Map.of(
            "cpu", "host_cpu_usage_percent",
            "memory", "host_memory_usage_percent",
            "disk", "host_disk_usage_percent");

    private final HostMetricsCollector hostMetricsCollector;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private OkHttpClient httpClient;

    @Value("${prometheus.base-url}")
    private String prometheusBaseUrl;

    @Value("${prometheus.timeout:10}")
    private int timeout;

    @Value("${prometheus.mock-enabled:false}")
    private boolean mockEnabled;

    public AiOpsDashboardService(HostMetricsCollector hostMetricsCollector) {
        this.hostMetricsCollector = hostMetricsCollector;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(timeout))
                .readTimeout(Duration.ofSeconds(timeout))
                .build();
    }

    public AiOpsDashboardData buildDashboard(int rangeMinutes) {
        int safeRange = Math.max(15, Math.min(rangeMinutes, 240));
        HostMetricsSnapshot snapshot = hostMetricsCollector.getLatestSnapshot();
        Instant now = Instant.now();
        Instant start = now.minusSeconds(safeRange * 60L);

        AiOpsDashboardData.Series series;
        List<AiOpsDashboardData.AlertItem> alerts;
        try {
            if (mockEnabled) {
                series = buildMockSeries(snapshot, start, now);
                alerts = buildMockAlerts(now);
            } else {
                series = fetchSeriesFromPrometheus(start, now);
                alerts = fetchAlertsFromPrometheus();
            }
        } catch (Exception e) {
            logger.warn("构建 AI Ops 仪表盘数据失败，回退到当前快照: {}", e.getMessage());
            series = buildFallbackSeries(snapshot, start, now);
            alerts = List.of();
        }

        return AiOpsDashboardData.builder()
                .generatedAt(DISPLAY_FORMATTER.format(now))
                .hostname(snapshot.getHostname())
                .currentMetrics(AiOpsDashboardData.CurrentMetrics.builder()
                        .cpu(snapshot.getCpuUsagePercent())
                        .memory(snapshot.getMemoryUsagePercent())
                        .disk(snapshot.getDiskUsagePercent())
                        .collectedAt(DISPLAY_FORMATTER.format(
                                Instant.ofEpochMilli(snapshot.getCollectedAtEpochMs())))
                        .build())
                .thresholds(AiOpsDashboardData.Thresholds.builder()
                        .cpu(80)
                        .memory(85)
                        .disk(90)
                        .build())
                .alerts(alerts)
                .series(series)
                .rangeMinutes(safeRange)
                .build();
    }

    private List<AiOpsDashboardData.AlertItem> fetchAlertsFromPrometheus() throws Exception {
        String apiUrl = prometheusBaseUrl + "/api/v1/alerts";
        Request request = new Request.Builder().url(apiUrl).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new RuntimeException("Prometheus alerts HTTP " + response.code());
            }
            JsonNode root = objectMapper.readTree(response.body().string());
            if (!"success".equals(root.path("status").asText())) {
                throw new RuntimeException("Prometheus alerts status != success");
            }
            JsonNode alertsNode = root.path("data").path("alerts");
            Map<String, AiOpsDashboardData.AlertItem> deduped = new LinkedHashMap<>();
            for (JsonNode alertNode : alertsNode) {
                String alertName = alertNode.path("labels").path("alertname").asText("");
                if (alertName.isBlank() || deduped.containsKey(alertName)) {
                    continue;
                }
                String activeAt = alertNode.path("activeAt").asText("");
                deduped.put(alertName, AiOpsDashboardData.AlertItem.builder()
                        .alertName(alertName)
                        .state(alertNode.path("state").asText("unknown"))
                        .severity(alertNode.path("labels").path("severity").asText("warning"))
                        .service(alertNode.path("labels").path("service").asText("local-host"))
                        .summary(alertNode.path("annotations").path("summary").asText(""))
                        .description(alertNode.path("annotations").path("description").asText(""))
                        .activeAt(activeAt)
                        .activeAtDisplay(formatInstant(activeAt))
                        .duration(calculateDuration(activeAt))
                        .build());
            }
            return new ArrayList<>(deduped.values());
        }
    }

    private AiOpsDashboardData.Series fetchSeriesFromPrometheus(Instant start, Instant end) throws Exception {
        AiOpsDashboardData.Series.SeriesBuilder builder = AiOpsDashboardData.Series.builder();
        for (Map.Entry<String, String> entry : METRIC_QUERIES.entrySet()) {
            List<AiOpsDashboardData.DataPoint> points = queryRange(entry.getValue(), start, end);
            switch (entry.getKey()) {
                case "cpu" -> builder.cpu(points);
                case "memory" -> builder.memory(points);
                case "disk" -> builder.disk(points);
                default -> { }
            }
        }
        return builder.build();
    }

    private List<AiOpsDashboardData.DataPoint> queryRange(String query, Instant start, Instant end) throws Exception {
        HttpUrl url = HttpUrl.parse(prometheusBaseUrl + "/api/v1/query_range").newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("start", String.valueOf(start.getEpochSecond()))
                .addQueryParameter("end", String.valueOf(end.getEpochSecond()))
                .addQueryParameter("step", "60")
                .build();

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new RuntimeException("Prometheus query_range HTTP " + response.code());
            }
            JsonNode root = objectMapper.readTree(response.body().string());
            if (!"success".equals(root.path("status").asText())) {
                throw new RuntimeException("Prometheus query_range status != success");
            }
            JsonNode result = root.path("data").path("result");
            if (!result.isArray() || result.isEmpty()) {
                return List.of();
            }
            JsonNode values = result.get(0).path("values");
            List<AiOpsDashboardData.DataPoint> points = new ArrayList<>();
            for (JsonNode pair : values) {
                if (pair.size() < 2) {
                    continue;
                }
                long epochSec = pair.get(0).asLong();
                double value = pair.get(1).asDouble();
                Instant instant = Instant.ofEpochSecond(epochSec);
                points.add(AiOpsDashboardData.DataPoint.builder()
                        .epochMs(epochSec * 1000)
                        .time(DISPLAY_FORMATTER.format(instant))
                        .value(Math.round(value * 10.0) / 10.0)
                        .build());
            }
            return points;
        }
    }

    private AiOpsDashboardData.Series buildMockSeries(HostMetricsSnapshot snapshot, Instant start, Instant end) {
        List<AiOpsDashboardData.DataPoint> cpu = new ArrayList<>();
        List<AiOpsDashboardData.DataPoint> memory = new ArrayList<>();
        List<AiOpsDashboardData.DataPoint> disk = new ArrayList<>();
        long stepSeconds = 60;
        for (long t = start.getEpochSecond(); t <= end.getEpochSecond(); t += stepSeconds) {
            Instant instant = Instant.ofEpochSecond(t);
            double wave = Math.sin(t / 120.0) * 5;
            cpu.add(point(instant, clamp(snapshot.getCpuUsagePercent() + wave, 0, 100)));
            memory.add(point(instant, clamp(snapshot.getMemoryUsagePercent() + wave * 0.8, 0, 100)));
            disk.add(point(instant, clamp(snapshot.getDiskUsagePercent() + wave * 0.3, 0, 100)));
        }
        return AiOpsDashboardData.Series.builder()
                .cpu(cpu)
                .memory(memory)
                .disk(disk)
                .build();
    }

    private AiOpsDashboardData.Series buildFallbackSeries(HostMetricsSnapshot snapshot, Instant start, Instant end) {
        AiOpsDashboardData.DataPoint current = point(
                Instant.ofEpochMilli(snapshot.getCollectedAtEpochMs()),
                snapshot.getCpuUsagePercent());
        AiOpsDashboardData.DataPoint mem = point(
                Instant.ofEpochMilli(snapshot.getCollectedAtEpochMs()),
                snapshot.getMemoryUsagePercent());
        AiOpsDashboardData.DataPoint disk = point(
                Instant.ofEpochMilli(snapshot.getCollectedAtEpochMs()),
                snapshot.getDiskUsagePercent());
        return AiOpsDashboardData.Series.builder()
                .cpu(List.of(current))
                .memory(List.of(mem))
                .disk(List.of(disk))
                .build();
    }

    private List<AiOpsDashboardData.AlertItem> buildMockAlerts(Instant now) {
        Instant activeAt = now.minusSeconds(15 * 60);
        String activeAtStr = activeAt.toString();
        return List.of(
                AiOpsDashboardData.AlertItem.builder()
                        .alertName("HighMemoryUsage")
                        .state("firing")
                        .severity("warning")
                        .service("local-host")
                        .summary("本机内存使用率过高")
                        .description("服务 local-host 的内存使用率持续超过 85%")
                        .activeAt(activeAtStr)
                        .activeAtDisplay(DISPLAY_FORMATTER.format(activeAt))
                        .duration("15m0s")
                        .build());
    }

    private AiOpsDashboardData.DataPoint point(Instant instant, double value) {
        return AiOpsDashboardData.DataPoint.builder()
                .epochMs(instant.toEpochMilli())
                .time(DISPLAY_FORMATTER.format(instant))
                .value(Math.round(value * 10.0) / 10.0)
                .build();
    }

    private String formatInstant(String isoText) {
        if (isoText == null || isoText.isBlank()) {
            return "-";
        }
        try {
            return DISPLAY_FORMATTER.format(Instant.parse(isoText));
        } catch (Exception e) {
            return isoText;
        }
    }

    private String calculateDuration(String activeAtStr) {
        try {
            Instant activeAt = Instant.parse(activeAtStr);
            Duration duration = Duration.between(activeAt, Instant.now());
            long hours = duration.toHours();
            long minutes = duration.toMinutes() % 60;
            long seconds = duration.getSeconds() % 60;
            if (hours > 0) {
                return String.format("%dh%dm%ds", hours, minutes, seconds);
            }
            if (minutes > 0) {
                return String.format("%dm%ds", minutes, seconds);
            }
            return String.format("%ds", seconds);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
