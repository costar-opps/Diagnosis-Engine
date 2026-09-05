package org.example.monitor;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class HostMetricsSnapshot {
    double cpuUsagePercent;
    double memoryUsagePercent;
    double diskUsagePercent;
    long totalMemoryBytes;
    long usedMemoryBytes;
    long totalDiskBytes;
    long usedDiskBytes;
    String hostname;
    String osName;
    long collectedAtEpochMs;
}
