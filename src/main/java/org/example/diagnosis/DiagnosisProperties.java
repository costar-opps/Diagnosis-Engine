package org.example.diagnosis;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "diagnosis")
public class DiagnosisProperties {

    private String projectsDir = "config/projects";
    private String defaultMode = "PLATFORM";
    private boolean crossProjectExpansionEnabled = true;
    private boolean crossProjectRetrievalEnabled = false;
    /** 项目业务指标/日志走进程内演示遥测，平台主机指标仍可接真实 Prometheus */
    private boolean demoTelemetryEnabled = true;

    private Context context = new Context();
    private Observability observability = new Observability();
    private Limits limits = new Limits();

    @Data
    public static class Context {
        private int logSummaryThreshold = 20;
        private int metricPointsThreshold = 30;
        private int tokenBudget = 8000;
        private int trimKeepRecentSteps = 3;
        private int extraPromptHintMaxChars = 400;
        private int businessFieldMaxChars = 800;
    }

    @Data
    public static class Observability {
        private boolean enabled = true;
        private boolean persistPromptBody = false;
        private int retainHours = 24;
    }

    @Data
    public static class Limits {
        private int maxDurationSeconds = 120;
        private int maxToolCalls = 20;
        private int maxConcurrent = 2;
    }
}
