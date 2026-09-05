package org.example.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断控制台的占位接口。
 *
 * 多项目诊断能力尚未实现，这里只提供固定结构的响应，供前端先把产品形态固定下来。
 * 所有响应都带 {@code placeholder} 标记，前端据此在界面上打「占位」角标；
 * 真正的诊断执行返回 501，避免产出一份与所选项目无关的报告。
 */
@RestController
@RequestMapping("/api/diagnosis")
public class DiagnosisConsoleController {

    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final List<ProjectListItem> PROJECTS = List.of(
            ProjectListItem.builder()
                    .id("cangqiong-waimai")
                    .name("苍穹外卖平台")
                    .diagnosable(true)
                    .reason("")
                    .alertCount(2)
                    .build(),
            ProjectListItem.builder()
                    .id("payment-gateway")
                    .name("支付网关")
                    .diagnosable(true)
                    .reason("")
                    .alertCount(0)
                    .build(),
            ProjectListItem.builder()
                    .id("legacy-report-center")
                    .name("报表中心（历史系统）")
                    .diagnosable(false)
                    .reason("未登记项目档案，缺少日志主题与指标前缀")
                    .alertCount(0)
                    .build()
    );

    private static final Map<String, ProjectSummary> SUMMARIES = buildSummaries();

    @GetMapping("/projects")
    public ResponseEntity<ProjectListResponse> listProjects() {
        return ResponseEntity.ok(ProjectListResponse.builder()
                .placeholder(true)
                .projects(PROJECTS)
                .build());
    }

    @GetMapping("/projects/{projectId}/summary")
    public ResponseEntity<?> projectSummary(@PathVariable String projectId) {
        ProjectSummary summary = SUMMARIES.get(projectId);
        if (summary == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("placeholder", true);
            body.put("project_id", projectId);
            body.put("message", "项目未在诊断引擎中注册，请先登记项目档案");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        return ResponseEntity.ok(summary);
    }

    /**
     * 项目级诊断尚未实现。这里明确返回 501，而不是转发到平台级的 /api/ai_ops，
     * 因为既有诊断链路没有项目概念，转发只会产出一份与所选项目无关的报告。
     */
    @PostMapping("/diagnoses")
    public ResponseEntity<Map<String, Object>> runDiagnosis(@RequestBody(required = false) DiagnosisRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("implemented", false);
        body.put("project_id", request == null ? null : request.getProjectId());
        body.put("mode", request == null ? "PROJECT" : request.getMode());
        body.put("message", "项目级诊断待后端实现；平台级分析可通过顶部「平台级诊断」按钮触发");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(body);
    }

    /**
     * 反馈只回显不落库，等知识自进化闭环实现后再接入真实存储。
     */
    @PostMapping("/reports/{reportId}/feedback")
    public ResponseEntity<Map<String, Object>> submitFeedback(@PathVariable String reportId,
                                                              @RequestBody(required = false) FeedbackRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("placeholder", true);
        body.put("persisted", false);
        body.put("report_id", reportId);
        body.put("decision", request == null ? null : request.getDecision());
        body.put("reason", request == null ? null : request.getReason());
        body.put("feedback_at", LocalDateTime.now().format(DISPLAY_FORMAT));
        return ResponseEntity.ok(body);
    }

    private static Map<String, ProjectSummary> buildSummaries() {
        Map<String, ProjectSummary> summaries = new LinkedHashMap<>();

        summaries.put("cangqiong-waimai", ProjectSummary.builder()
                .placeholder(true)
                .projectId("cangqiong-waimai")
                .projectName("苍穹外卖平台")
                .metrics(List.of(
                        MetricItem.builder()
                                .name("下单接口 P99 耗时")
                                .value(2180d)
                                .unit("ms")
                                .threshold(800d)
                                .thresholdDirection("upper")
                                .meaning("用户提交订单到返回结果的端到端耗时，超阈值意味着下单卡顿")
                                .available(true)
                                .build(),
                        MetricItem.builder()
                                .name("订单支付成功率")
                                .value(91.4d)
                                .unit("%")
                                .threshold(98d)
                                .thresholdDirection("lower")
                                .meaning("支付回调成功占比，下跌通常指向支付网关或回调链路异常")
                                .available(true)
                                .build(),
                        MetricItem.builder()
                                .name("库存扣减失败次数")
                                .value(37d)
                                .unit("次/5min")
                                .threshold(5d)
                                .thresholdDirection("upper")
                                .meaning("分布式锁竞争或库存不足导致的扣减失败，直接影响履约")
                                .available(true)
                                .build(),
                        MetricItem.builder()
                                .name("骑手派单延迟")
                                .unit("s")
                                .threshold(60d)
                                .thresholdDirection("upper")
                                .meaning("订单生成到派单完成的延迟")
                                .available(false)
                                .build()
                ))
                .alerts(List.of(
                        ProjectAlertItem.builder()
                                .alertName("OrderApiSlowResponse")
                                .severity("critical")
                                .activeAtDisplay("2026-08-08 18:42:11")
                                .duration("23m")
                                .build(),
                        ProjectAlertItem.builder()
                                .alertName("PaymentCallbackFailureRate")
                                .severity("warning")
                                .activeAtDisplay("2026-08-08 18:55:03")
                                .duration("10m")
                                .build()
                ))
                .lastChange(LastChange.builder()
                        .available(true)
                        .atDisplay("2026-08-08 18:30:47")
                        .changeId("build-1042")
                        .summary("order-service 发布 v2.7.3，改动订单超时时间与库存扣减重试策略")
                        .build())
                .build());

        summaries.put("payment-gateway", ProjectSummary.builder()
                .placeholder(true)
                .projectId("payment-gateway")
                .projectName("支付网关")
                .metrics(List.of(
                        MetricItem.builder()
                                .name("支付渠道平均响应")
                                .value(210d)
                                .unit("ms")
                                .threshold(500d)
                                .thresholdDirection("upper")
                                .meaning("调用第三方支付渠道的平均耗时")
                                .available(true)
                                .build(),
                        MetricItem.builder()
                                .name("对账差异笔数")
                                .value(0d)
                                .unit("笔/h")
                                .threshold(1d)
                                .thresholdDirection("upper")
                                .meaning("与渠道账单不一致的订单数量")
                                .available(true)
                                .build()
                ))
                .alerts(List.of())
                .lastChange(LastChange.builder()
                        .available(false)
                        .build())
                .build());

        return summaries;
    }

    @Data
    @Builder
    public static class ProjectListResponse {
        private boolean placeholder;
        private List<ProjectListItem> projects;
    }

    @Data
    @Builder
    public static class ProjectListItem {
        private String id;
        private String name;
        private boolean diagnosable;

        /** 不可诊断的原因，可诊断时为空 */
        private String reason;

        @JsonProperty("alert_count")
        private int alertCount;
    }

    @Data
    @Builder
    public static class ProjectSummary {
        private boolean placeholder;

        @JsonProperty("project_id")
        private String projectId;

        @JsonProperty("project_name")
        private String projectName;

        private List<MetricItem> metrics;
        private List<ProjectAlertItem> alerts;

        @JsonProperty("last_change")
        private LastChange lastChange;
    }

    @Data
    @Builder
    public static class MetricItem {
        private String name;
        private Double value;
        private String unit;
        private Double threshold;

        /** upper 表示不高于阈值，lower 表示不低于阈值 */
        @JsonProperty("threshold_direction")
        private String thresholdDirection;

        private String meaning;

        /** 采集不到时为 false，前端据此区分「不可用」与「值为 0」 */
        private boolean available;
    }

    @Data
    @Builder
    public static class ProjectAlertItem {
        @JsonProperty("alert_name")
        private String alertName;

        private String severity;

        @JsonProperty("active_at_display")
        private String activeAtDisplay;

        private String duration;
    }

    @Data
    @Builder
    public static class LastChange {
        /** 项目未接入发布系统时为 false，前端据此区分「不可用」与「近期无变更」 */
        private boolean available;

        @JsonProperty("at_display")
        private String atDisplay;

        @JsonProperty("change_id")
        private String changeId;

        private String summary;
    }

    @Data
    public static class DiagnosisRequest {
        @JsonProperty("project_id")
        private String projectId;

        private String mode;
    }

    @Data
    public static class FeedbackRequest {
        private String decision;
        private String reason;
    }
}
