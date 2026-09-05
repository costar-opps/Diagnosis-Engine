package org.example.diagnosis.report;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ReportStore {

    private final Map<String, DiagnosisReport> reports = new ConcurrentHashMap<>();

    public DiagnosisReport save(DiagnosisReport report) {
        report.setImmutable(true);
        reports.put(report.getReportId(), report);
        return report;
    }

    public Optional<DiagnosisReport> find(String reportId) {
        return Optional.ofNullable(reports.get(reportId));
    }

    public DiagnosisReport supersede(String oldReportId, DiagnosisReport fresh) {
        find(oldReportId).ifPresent(old -> fresh.setRelatedReportId(old.getReportId()));
        return save(fresh);
    }

    public List<DiagnosisReport> list() {
        return new ArrayList<>(reports.values());
    }
}
