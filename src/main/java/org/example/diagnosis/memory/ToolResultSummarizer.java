package org.example.diagnosis.memory;

import org.example.diagnosis.DiagnosisProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ToolResultSummarizer {

    private final DiagnosisProperties properties;

    public ToolResultSummarizer(DiagnosisProperties properties) {
        this.properties = properties;
    }

    public SummarizedResult summarizeLogs(String source, String locator, String windowStart, String windowEnd,
                                          List<Map<String, String>> records) {
        List<Map<String, String>> safe = records == null ? List.of() : records;
        RawRef rawRef = RawRef.builder()
                .queryId("log-" + UUID.randomUUID().toString().substring(0, 8))
                .source(source)
                .locator(locator)
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .totalCount(safe.size())
                .build();
        int threshold = properties.getContext().getLogSummaryThreshold();
        if (safe.size() <= threshold) {
            return new SummarizedResult(false, formatRecords(safe), rawRef, 0, Map.of());
        }
        Map<String, List<Map<String, String>>> grouped = new LinkedHashMap<>();
        for (Map<String, String> record : safe) {
            String signature = signatureOf(record);
            grouped.computeIfAbsent(signature, key -> new ArrayList<>()).add(record);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("日志摘要（原始 ").append(safe.size()).append(" 条，已按错误签名聚合）\n");
        grouped.forEach((signature, items) -> {
            sb.append("- ").append(signature).append(" ×").append(items.size());
            sb.append(" 时间分布 ").append(items.get(0).getOrDefault("time", "-"));
            if (items.size() > 1) {
                sb.append(" ~ ").append(items.get(items.size() - 1).getOrDefault("time", "-"));
            }
            sb.append(" 样本: ").append(items.get(0).getOrDefault("message", ""));
            sb.append('\n');
        });
        sb.append("省略 ").append(safe.size() - grouped.size()).append(" 条同类记录，rawRef=").append(rawRef.getQueryId());
        return new SummarizedResult(true, sb.toString(), rawRef, safe.size() - grouped.size(), grouped);
    }

    public SummarizedResult downsampleMetrics(String source, String metricName, String windowStart, String windowEnd,
                                              List<Double> points) {
        List<Double> safe = points == null ? List.of() : points;
        RawRef rawRef = RawRef.builder()
                .queryId("metric-" + UUID.randomUUID().toString().substring(0, 8))
                .source(source)
                .locator(metricName)
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .totalCount(safe.size())
                .build();
        int threshold = properties.getContext().getMetricPointsThreshold();
        if (safe.isEmpty()) {
            return new SummarizedResult(false, "无数据点", rawRef, 0, Map.of());
        }
        if (safe.size() <= threshold) {
            return new SummarizedResult(false, "最新值=" + safe.get(safe.size() - 1) + " 点数=" + safe.size(), rawRef, 0, Map.of());
        }
        double last = safe.get(safe.size() - 1);
        double max = safe.stream().mapToDouble(Double::doubleValue).max().orElse(last);
        double min = safe.stream().mapToDouble(Double::doubleValue).min().orElse(last);
        String text = "指标降采样摘要 " + metricName + " min=" + min + " max=" + max + " last=" + last
                + " 原始点数=" + safe.size() + " rawRef=" + rawRef.getQueryId();
        return new SummarizedResult(true, text, rawRef, safe.size() - 3, Map.of());
    }

    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 3);
    }

    private static String signatureOf(Map<String, String> record) {
        String message = record.getOrDefault("message", record.getOrDefault("content", "unknown"));
        int cut = Math.min(message.length(), 80);
        return record.getOrDefault("level", "INFO") + ":" + message.substring(0, cut);
    }

    private static String formatRecords(List<Map<String, String>> records) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> record : records) {
            sb.append(record.getOrDefault("time", "-")).append(' ')
                    .append(record.getOrDefault("level", "INFO")).append(' ')
                    .append(record.getOrDefault("message", "")).append('\n');
        }
        return sb.toString().trim();
    }

    public record SummarizedResult(boolean summarized, String text, RawRef rawRef, int omitted,
                                   Map<String, List<Map<String, String>>> groups) {
    }
}
