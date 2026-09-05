package org.example.controller;

import org.example.diagnosis.context.DiagnosisMode;
import org.example.diagnosis.engine.DiagnosisOrchestrator;
import org.example.diagnosis.fault.EvalRecordStore;
import org.example.diagnosis.fault.FaultInjector;
import org.example.diagnosis.fault.FaultScenario;
import org.example.diagnosis.fault.ScenarioCatalog;
import org.example.diagnosis.fault.ScenarioEvaluator;
import org.example.diagnosis.report.DiagnosisReport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/faults")
public class FaultScenarioController {

    private final ScenarioCatalog catalog;
    private final FaultInjector injector;
    private final DiagnosisOrchestrator orchestrator;
    private final ScenarioEvaluator evaluator;
    private final EvalRecordStore evalRecords;

    public FaultScenarioController(ScenarioCatalog catalog, FaultInjector injector,
                                   DiagnosisOrchestrator orchestrator, ScenarioEvaluator evaluator,
                                   EvalRecordStore evalRecords) {
        this.catalog = catalog;
        this.injector = injector;
        this.orchestrator = orchestrator;
        this.evaluator = evaluator;
        this.evalRecords = evalRecords;
    }

    @GetMapping
    public List<FaultScenario> list() {
        return List.copyOf(catalog.all());
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return injector.status();
    }

    @PostMapping("/{scenarioId}/inject")
    public ResponseEntity<?> inject(@PathVariable String scenarioId) {
        injector.inject(scenarioId);
        return ResponseEntity.ok(injector.status());
    }

    @PostMapping("/{scenarioId}/revoke")
    public ResponseEntity<?> revoke(@PathVariable String scenarioId) {
        injector.revoke(scenarioId);
        return ResponseEntity.ok(injector.status());
    }

    @PostMapping("/revoke-all")
    public Map<String, Object> revokeAll() {
        injector.revokeAll();
        return injector.status();
    }

    @PostMapping("/{scenarioId}/evaluate")
    public Map<String, Object> evaluateOne(@PathVariable String scenarioId) {
        FaultScenario scenario = catalog.find(scenarioId)
                .orElseThrow(() -> new IllegalArgumentException("未知场景: " + scenarioId));
        injector.inject(scenarioId);
        DiagnosisReport report = orchestrator.diagnose(
                scenario.getExpectedMode(),
                scenario.getTargetProject(),
                "eval-" + scenarioId,
                null);
        ScenarioEvaluator.EvalResult result = evaluator.evaluate(scenario, report);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("scenario_id", scenarioId);
        record.put("passed", result.passed());
        record.put("passed_points", result.passedPoints());
        record.put("total_points", result.totalPoints());
        record.put("details", result.details());
        record.put("report_id", report.getReportId());
        record.put("trace_id", report.getTraceId());
        record.put("tool_calls", report.getToolSummary().size());
        record.put("tokens", report.getToolSummary().stream()
                .mapToInt(e -> (e.getInputTokens() == null ? 0 : e.getInputTokens())
                        + (e.getOutputTokens() == null ? 0 : e.getOutputTokens())).sum());
        evalRecords.add(record);
        injector.revoke(scenarioId);
        return record;
    }

    @PostMapping("/eval/batch")
    public Map<String, Object> batch(@RequestParam(defaultValue = "all") String set) {
        List<FaultScenario> scenarios = "demo".equals(set) ? catalog.demoSet() : List.copyOf(catalog.all());
        List<Map<String, Object>> rows = new ArrayList<>();
        int pass = 0;
        int tools = 0;
        int tokens = 0;
        for (FaultScenario scenario : scenarios) {
            Map<String, Object> row = evaluateOne(scenario.getId());
            rows.add(row);
            if (Boolean.TRUE.equals(row.get("passed"))) {
                pass++;
            }
            tools += (int) row.getOrDefault("tool_calls", 0);
            tokens += (int) row.getOrDefault("tokens", 0);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("set", set);
        summary.put("total", scenarios.size());
        summary.put("passed", pass);
        summary.put("pass_rate", scenarios.isEmpty() ? 0 : (pass * 1.0 / scenarios.size()));
        summary.put("avg_tool_calls", scenarios.isEmpty() ? 0 : tools * 1.0 / scenarios.size());
        summary.put("avg_tokens", scenarios.isEmpty() ? 0 : tokens * 1.0 / scenarios.size());
        summary.put("rows", rows);
        evalRecords.add(Map.of("type", "batch", "summary", summary));
        return summary;
    }

    @GetMapping("/eval/history")
    public List<Map<String, Object>> history() {
        return evalRecords.history();
    }
}
