package org.example.diagnosis.engine;

import org.example.diagnosis.context.DiagnosisMode;
import org.example.diagnosis.profile.ProjectRegistry;
import org.example.diagnosis.telemetry.DemoTelemetryStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ModeRouter {

    private final ProjectRegistry registry;
    private final DemoTelemetryStore telemetry;

    public ModeRouter(ProjectRegistry registry, DemoTelemetryStore telemetry) {
        this.registry = registry;
        this.telemetry = telemetry;
    }

    public Route decide(DiagnosisMode requested, String projectId, String alertName) {
        if (requested == DiagnosisMode.PROJECT) {
            if (projectId == null || projectId.isBlank()) {
                throw new IllegalArgumentException("项目级诊断必须指定 projectId");
            }
            registry.requireDiagnosable(projectId);
            return new Route(DiagnosisMode.PROJECT, projectId, "请求指定项目级");
        }
        String inferred = inferProjectFromAlert(alertName);
        if (inferred != null) {
            return new Route(DiagnosisMode.PROJECT, inferred, "告警带项目标签，走项目级: " + alertName);
        }
        List<String> noisy = projectsWithAlerts();
        if (noisy.size() >= 2) {
            return new Route(DiagnosisMode.PLATFORM, null, "多项目同时异常，先做平台资源扫描");
        }
        return new Route(DiagnosisMode.PLATFORM, null, "主机/共享资源告警或归属模糊，走平台级");
    }

    private String inferProjectFromAlert(String alertName) {
        if (alertName == null) {
            return null;
        }
        String lower = alertName.toLowerCase(Locale.ROOT);
        if (lower.contains("card")) {
            return "card-service";
        }
        if (lower.contains("pay")) {
            return "payment-service";
        }
        if (lower.contains("order")) {
            return "order-service";
        }
        return telemetry.allProjects().entrySet().stream()
                .filter(e -> e.getValue().alerts.stream().anyMatch(a -> a.equalsIgnoreCase(alertName)))
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private List<String> projectsWithAlerts() {
        List<String> ids = new ArrayList<>();
        telemetry.allProjects().forEach((id, state) -> {
            if (!state.alerts.isEmpty()) {
                ids.add(id);
            }
        });
        return ids;
    }

    public record Route(DiagnosisMode mode, String projectId, String reason) {
    }
}
