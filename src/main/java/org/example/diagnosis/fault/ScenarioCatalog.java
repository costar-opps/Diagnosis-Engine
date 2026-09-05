package org.example.diagnosis.fault;

import org.example.diagnosis.context.DiagnosisMode;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ScenarioCatalog {

    private final Map<String, FaultScenario> scenarios = new LinkedHashMap<>();

    public ScenarioCatalog() {
        add(FaultScenario.builder()
                .id("platform-memory-pressure")
                .name("主机内存打满")
                .category("platform_resource")
                .injectMethod("in_process")
                .expectedMode(DiagnosisMode.PLATFORM)
                .expectedEvidenceTypes(List.of("metric", "platform"))
                .conclusionPoints(List.of("内存瓶颈", "占用主体", "受影响项目"))
                .passThreshold(3)
                .set("demo")
                .build());
        add(FaultScenario.builder()
                .id("platform-disk-pressure")
                .name("磁盘将满")
                .category("platform_resource")
                .injectMethod("in_process")
                .expectedMode(DiagnosisMode.PLATFORM)
                .expectedEvidenceTypes(List.of("metric", "platform"))
                .conclusionPoints(List.of("磁盘瓶颈", "落盘风险"))
                .passThreshold(2)
                .set("demo")
                .build());
        add(FaultScenario.builder()
                .id("card-api-timeout")
                .name("出卡接口超时")
                .category("project_business")
                .injectMethod("in_process")
                .expectedMode(DiagnosisMode.PROJECT)
                .targetProject("card-service")
                .expectedEvidenceTypes(List.of("metric", "log"))
                .conclusionPoints(List.of("出卡延迟", "慢环节", "处置建议"))
                .passThreshold(3)
                .set("demo")
                .build());
        add(FaultScenario.builder()
                .id("card-success-drop")
                .name("出卡成功率骤降")
                .category("project_business")
                .injectMethod("in_process")
                .expectedMode(DiagnosisMode.PROJECT)
                .targetProject("card-service")
                .expectedEvidenceTypes(List.of("metric", "log"))
                .conclusionPoints(List.of("关键链路", "失败环节", "处置建议"))
                .passThreshold(3)
                .set("demo")
                .build());
        add(FaultScenario.builder()
                .id("card-change-regression")
                .name("变更后错误尖刺")
                .category("change_regression")
                .injectMethod("in_process")
                .expectedMode(DiagnosisMode.PROJECT)
                .targetProject("card-service")
                .expectedEvidenceTypes(List.of("change", "log", "code"))
                .conclusionPoints(List.of("变更关联", "错误尖刺", "代码线索"))
                .passThreshold(3)
                .set("holdout")
                .build());
        add(FaultScenario.builder()
                .id("card-silent-failure")
                .name("无告警静默故障")
                .category("silent")
                .injectMethod("in_process")
                .expectedMode(DiagnosisMode.PROJECT)
                .targetProject("card-service")
                .expectedEvidenceTypes(List.of("metric", "log"))
                .conclusionPoints(List.of("无告警", "静默失败", "业务指标"))
                .passThreshold(2)
                .set("holdout")
                .build());
        add(FaultScenario.builder()
                .id("card-downstream-payment")
                .name("下游支付依赖故障")
                .category("cross_project")
                .injectMethod("in_process")
                .expectedMode(DiagnosisMode.PROJECT)
                .targetProject("card-service")
                .expectedEvidenceTypes(List.of("log", "metric"))
                .conclusionPoints(List.of("下游依赖", "payment-service", "一跳扩展"))
                .passThreshold(3)
                .set("demo")
                .build());
        add(FaultScenario.builder()
                .id("order-resource-contention")
                .name("同机资源争用")
                .category("cross_project")
                .injectMethod("in_process")
                .expectedMode(DiagnosisMode.PLATFORM)
                .expectedEvidenceTypes(List.of("metric", "platform"))
                .conclusionPoints(List.of("资源争用", "order-service", "受影响项目"))
                .passThreshold(3)
                .set("holdout")
                .build());
    }

    private void add(FaultScenario scenario) {
        scenarios.put(scenario.getId(), scenario);
    }

    public Optional<FaultScenario> find(String id) {
        return Optional.ofNullable(scenarios.get(id));
    }

    public Collection<FaultScenario> all() {
        return scenarios.values();
    }

    public List<FaultScenario> demoSet() {
        return scenarios.values().stream().filter(s -> "demo".equals(s.getSet())).toList();
    }
}
