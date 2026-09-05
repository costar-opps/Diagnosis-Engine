package org.example.diagnosis.engine;

import org.example.diagnosis.context.DiagnosisContext;
import org.example.diagnosis.context.DiagnosisMode;
import org.example.diagnosis.profile.ProjectProfile;
import org.example.diagnosis.telemetry.DemoTelemetryStore;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class PromptVariableAssembler {

    private final DemoTelemetryStore telemetry;

    public PromptVariableAssembler(DemoTelemetryStore telemetry) {
        this.telemetry = telemetry;
    }

    public String assemble(DiagnosisContext context) {
        if (context.getMode() == DiagnosisMode.PLATFORM) {
            return """
                    【平台级变量】
                    共享资源指标：内存、磁盘
                    受影响项目：%s
                    扫描摘要：%s
                    禁止把单一项目业务日志写成全平台唯一根因。
                    """.formatted(
                    telemetry.allProjects().keySet(),
                    context.getPlatformScanSummary() == null ? "待扫描" : context.getPlatformScanSummary()
            );
        }
        ProjectProfile profile = context.profile();
        String paths = profile.getBusiness().getCriticalPaths().stream()
                .map(p -> p.getName() + ":" + String.join("→", p.getSteps()))
                .collect(Collectors.joining("；"));
        String faults = profile.getBusiness().getKnownFaults().stream()
                .map(f -> f.getPattern() + "/" + f.getHint())
                .collect(Collectors.joining("；"));
        String metrics = profile.getDatasources().getMetrics().stream()
                .map(m -> m.getName())
                .collect(Collectors.joining("，"));
        String hint = profile.getBusiness().getExtraPromptHint() == null ? "" : profile.getBusiness().getExtraPromptHint();
        return """
                【项目级变量】
                项目：%s
                关键链路：%s
                已知故障模式：%s
                可用业务指标：%s
                %s
                """.formatted(context.getProjectId(), paths, faults, metrics, hint);
    }
}
