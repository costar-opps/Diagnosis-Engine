package org.example.monitor;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class AiOpsDashboardData {

    @JsonProperty("generated_at")
    private String generatedAt;

    private String hostname;

    @JsonProperty("current_metrics")
    private CurrentMetrics currentMetrics;

    private Thresholds thresholds;

    @Builder.Default
    private List<AlertItem> alerts = new ArrayList<>();

    private Series series;

    @JsonProperty("range_minutes")
    private int rangeMinutes;

    @Data
    @Builder
    public static class CurrentMetrics {
        private double cpu;
        private double memory;
        private double disk;

        @JsonProperty("collected_at")
        private String collectedAt;
    }

    @Data
    @Builder
    public static class Thresholds {
        private double cpu;
        private double memory;
        private double disk;
    }

    @Data
    @Builder
    public static class AlertItem {
        @JsonProperty("alert_name")
        private String alertName;

        private String state;
        private String severity;
        private String service;
        private String summary;
        private String description;

        @JsonProperty("active_at")
        private String activeAt;

        @JsonProperty("active_at_display")
        private String activeAtDisplay;

        private String duration;
    }

    @Data
    @Builder
    public static class Series {
        private List<DataPoint> cpu = new ArrayList<>();
        private List<DataPoint> memory = new ArrayList<>();
        private List<DataPoint> disk = new ArrayList<>();
    }

    @Data
    @Builder
    public static class DataPoint {
        private String time;

        @JsonProperty("epoch_ms")
        private long epochMs;

        private double value;
    }
}
