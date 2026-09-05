package org.example.monitor;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class HostMetricsCollector {

    private static final Logger logger = LoggerFactory.getLogger(HostMetricsCollector.class);

    private final SystemInfo systemInfo = new SystemInfo();
    private final AtomicReference<HostMetricsSnapshot> latestSnapshot =
            new AtomicReference<>(emptySnapshot());

    private long[] previousCpuTicks;

    public HostMetricsCollector(MeterRegistry meterRegistry) {
        Gauge.builder("host_cpu_usage_percent", this, HostMetricsCollector::getCpuUsagePercent)
                .description("Host CPU usage percentage")
                .register(meterRegistry);
        Gauge.builder("host_memory_usage_percent", this, HostMetricsCollector::getMemoryUsagePercent)
                .description("Host memory usage percentage")
                .register(meterRegistry);
        Gauge.builder("host_disk_usage_percent", this, HostMetricsCollector::getDiskUsagePercent)
                .description("Host disk usage percentage")
                .register(meterRegistry);
    }

    @PostConstruct
    public void init() {
        previousCpuTicks = systemInfo.getHardware().getProcessor().getSystemCpuLoadTicks();
        collect();
        logger.info("本机指标采集已启动，hostname={}", latestSnapshot.get().getHostname());
    }

    @Scheduled(fixedDelayString = "${monitoring.collect-interval-ms:15000}")
    public void collect() {
        try {
            HardwareAbstractionLayer hardware = systemInfo.getHardware();
            OperatingSystem os = systemInfo.getOperatingSystem();
            CentralProcessor processor = hardware.getProcessor();
            GlobalMemory memory = hardware.getMemory();
            FileSystem fileSystem = os.getFileSystem();

            double cpuUsage = readCpuUsage(processor);
            long totalMemory = memory.getTotal();
            long availableMemory = memory.getAvailable();
            long usedMemory = totalMemory - availableMemory;
            double memoryUsage = totalMemory == 0 ? 0 : usedMemory * 100.0 / totalMemory;

            OSFileStore primaryDisk = selectPrimaryDisk(fileSystem);
            long totalDisk = primaryDisk.getTotalSpace();
            long usableDisk = primaryDisk.getUsableSpace();
            long usedDisk = totalDisk - usableDisk;
            double diskUsage = totalDisk == 0 ? 0 : usedDisk * 100.0 / totalDisk;

            String hostname = InetAddress.getLocalHost().getHostName();

            latestSnapshot.set(HostMetricsSnapshot.builder()
                    .cpuUsagePercent(round(cpuUsage))
                    .memoryUsagePercent(round(memoryUsage))
                    .diskUsagePercent(round(diskUsage))
                    .totalMemoryBytes(totalMemory)
                    .usedMemoryBytes(usedMemory)
                    .totalDiskBytes(totalDisk)
                    .usedDiskBytes(usedDisk)
                    .hostname(hostname)
                    .osName(os.toString())
                    .collectedAtEpochMs(System.currentTimeMillis())
                    .build());
        } catch (Exception e) {
            logger.warn("采集本机指标失败: {}", e.getMessage());
        }
    }

    public HostMetricsSnapshot getLatestSnapshot() {
        return latestSnapshot.get();
    }

    public double getCpuUsagePercent() {
        return latestSnapshot.get().getCpuUsagePercent();
    }

    public double getMemoryUsagePercent() {
        return latestSnapshot.get().getMemoryUsagePercent();
    }

    public double getDiskUsagePercent() {
        return latestSnapshot.get().getDiskUsagePercent();
    }

    private double readCpuUsage(CentralProcessor processor) {
        long[] currentTicks = processor.getSystemCpuLoadTicks();
        double load = processor.getSystemCpuLoadBetweenTicks(previousCpuTicks) * 100.0;
        previousCpuTicks = currentTicks;
        if (load < 0) {
            return 0;
        }
        return load;
    }

    private OSFileStore selectPrimaryDisk(FileSystem fileSystem) {
        OSFileStore fallback = null;
        for (OSFileStore store : fileSystem.getFileStores()) {
            if (store.getTotalSpace() <= 0) {
                continue;
            }
            if (fallback == null) {
                fallback = store;
            }
            String mount = store.getMount().toLowerCase();
            if (mount.equals("c:\\") || mount.equals("/") || mount.startsWith("c:")) {
                return store;
            }
        }
        return fallback != null ? fallback : fileSystem.getFileStores().get(0);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static HostMetricsSnapshot emptySnapshot() {
        return HostMetricsSnapshot.builder()
                .cpuUsagePercent(0)
                .memoryUsagePercent(0)
                .diskUsagePercent(0)
                .hostname("unknown")
                .osName("unknown")
                .collectedAtEpochMs(System.currentTimeMillis())
                .build();
    }
}
