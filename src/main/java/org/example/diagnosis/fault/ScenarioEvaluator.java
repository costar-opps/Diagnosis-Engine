package org.example.diagnosis.fault;

import org.example.diagnosis.report.DiagnosisReport;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ScenarioEvaluator {

    public EvalResult evaluate(FaultScenario scenario, DiagnosisReport report) {
        String haystack = (report.getMarkdown() + " " + report.getRootCause() + " "
                + report.getOverview() + " " + String.join(" ", report.getExpansion()))
                .toLowerCase(Locale.ROOT);
        List<Map<String, Object>> details = new ArrayList<>();
        int passed = 0;
        for (String point : scenario.getConclusionPoints()) {
            boolean ok = containsAny(haystack, point);
            if (ok) {
                passed++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("point", point);
            row.put("passed", ok);
            details.add(row);
        }
        boolean modeOk = report.getMode() == scenario.getExpectedMode();
        Map<String, Object> modeRow = new LinkedHashMap<>();
        modeRow.put("point", "形态=" + scenario.getExpectedMode());
        modeRow.put("passed", modeOk);
        details.add(modeRow);
        if (modeOk) {
            passed++;
        }
        int required = scenario.getPassThreshold();
        boolean success = passed >= required && modeOk;
        return new EvalResult(scenario.getId(), success, passed, required + 1, details);
    }

    private static boolean containsAny(String haystack, String point) {
        String normalized = point.toLowerCase(Locale.ROOT);
        if (haystack.contains(normalized)) {
            return true;
        }
        return switch (normalized) {
            case "内存瓶颈" -> haystack.contains("内存");
            case "磁盘瓶颈", "落盘风险" -> haystack.contains("磁盘");
            case "占用主体" -> haystack.contains("占用") || haystack.contains("order-service");
            case "受影响项目" -> haystack.contains("受影响") || haystack.contains("card-service");
            case "出卡延迟", "慢环节" -> haystack.contains("超时") || haystack.contains("延迟") || haystack.contains("p99");
            case "关键链路", "失败环节" -> haystack.contains("成功率") || haystack.contains("预占") || haystack.contains("链路");
            case "处置建议" -> haystack.contains("建议");
            case "变更关联", "错误尖刺" -> haystack.contains("变更") || haystack.contains("build-");
            case "代码线索" -> haystack.contains("issuecardservice") || haystack.contains("代码");
            case "无告警", "静默失败", "业务指标" -> haystack.contains("静默") || haystack.contains("无告警") || haystack.contains("成功率");
            case "下游依赖", "payment-service", "一跳扩展" -> haystack.contains("payment") || haystack.contains("扩展");
            case "资源争用" -> haystack.contains("争用") || haystack.contains("内存");
            default -> false;
        };
    }

    public record EvalResult(String scenarioId, boolean passed, int passedPoints, int totalPoints,
                             List<Map<String, Object>> details) {
    }
}
