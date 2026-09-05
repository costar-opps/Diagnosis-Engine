package org.example.diagnosis.knowledge;

import org.example.diagnosis.report.DiagnosisReport;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CandidateKnowledgeStore {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final double SIMILARITY_THRESHOLD = 0.72;

    public record Candidate(String id, String projectId, String feature, String evidencePattern, String rootCause,
                            String action, String sourceReportId, String sourceConfidence, String status,
                            String rejectReason, String updatedFrom, String updatedReason, String createdAt) {
        public Candidate withStatus(String status, String rejectReason) {
            return new Candidate(id, projectId, feature, evidencePattern, rootCause, action, sourceReportId,
                    sourceConfidence, status, rejectReason, updatedFrom, updatedReason, createdAt);
        }
    }

    private final Map<String, Candidate> candidates = new ConcurrentHashMap<>();
    private final List<Map<String, String>> rejections = new ArrayList<>();
    private final ScopedKnowledgeStore knowledgeStore;

    public CandidateKnowledgeStore(ScopedKnowledgeStore knowledgeStore) {
        this.knowledgeStore = knowledgeStore;
    }

    public Optional<Candidate> onFeedback(DiagnosisReport report, String decision, String reason) {
        if (!"accept".equalsIgnoreCase(decision) || report == null) {
            if ("reject".equalsIgnoreCase(decision) && report != null) {
                rejections.add(Map.of(
                        "report_id", report.getReportId(),
                        "reason", reason == null ? "" : reason,
                        "at", LocalDateTime.now().format(FMT),
                        "project_id", report.getProjectId() == null ? "" : report.getProjectId()
                ));
            }
            return Optional.empty();
        }
        Candidate incoming = new Candidate(
                "cand-" + UUID.randomUUID().toString().substring(0, 8),
                report.getProjectId(),
                report.getOverview(),
                report.getEvidenceChain().isEmpty() ? "" : report.getEvidenceChain().get(0).getTitle(),
                report.getRootCause(),
                report.getSuggestions().isEmpty() ? "" : report.getSuggestions().get(0).getText(),
                report.getReportId(),
                report.getConfidence(),
                "pending",
                null, null, null,
                LocalDateTime.now().format(FMT)
        );
        for (Candidate existing : candidates.values()) {
            if (similar(existing, incoming)) {
                Candidate updated = new Candidate(existing.id(), incoming.projectId(), incoming.feature(),
                        incoming.evidencePattern(), incoming.rootCause(), incoming.action(), incoming.sourceReportId(),
                        incoming.sourceConfidence(), "pending", null, existing.id(),
                        "相似候选走更新，保留历史版本 " + existing.id(), incoming.createdAt());
                candidates.put(existing.id(), updated);
                return Optional.of(updated);
            }
        }
        candidates.put(incoming.id(), incoming);
        return Optional.of(incoming);
    }

    public Candidate confirm(String id, String feature, String rootCause, String action) {
        Candidate current = require(id);
        Candidate published = new Candidate(current.id(), current.projectId(),
                feature == null ? current.feature() : feature,
                current.evidencePattern(),
                rootCause == null ? current.rootCause() : rootCause,
                action == null ? current.action() : action,
                current.sourceReportId(), current.sourceConfidence(), "published",
                null, current.updatedFrom(), current.updatedReason(), current.createdAt());
        candidates.put(id, published);
        knowledgeStore.publish(new ScopedKnowledgeStore.Doc(
                "pub-" + id,
                published.projectId(),
                published.projectId() == null ? "platform" : "project",
                published.feature(),
                published.rootCause() + " 处置：" + published.action(),
                published.sourceReportId(),
                true
        ));
        return published;
    }

    public Candidate reject(String id, String reason) {
        Candidate current = require(id);
        Candidate rejected = current.withStatus("rejected", reason);
        candidates.put(id, rejected);
        return rejected;
    }

    public List<Candidate> list() {
        return new ArrayList<>(candidates.values());
    }

    public List<Map<String, String>> rejections() {
        return List.copyOf(rejections);
    }

    private Candidate require(String id) {
        Candidate current = candidates.get(id);
        if (current == null) {
            throw new IllegalArgumentException("候选不存在: " + id);
        }
        return current;
    }

    private static boolean similar(Candidate a, Candidate b) {
        String left = safe(a.rootCause()) + safe(a.feature());
        String right = safe(b.rootCause()) + safe(b.feature());
        if (left.isBlank() || right.isBlank()) {
            return false;
        }
        int shared = 0;
        String[] tokens = right.toLowerCase(Locale.ROOT).split("\\s+");
        for (String token : tokens) {
            if (token.length() >= 2 && left.toLowerCase(Locale.ROOT).contains(token)) {
                shared++;
            }
        }
        return tokens.length > 0 && (shared * 1.0 / tokens.length) >= SIMILARITY_THRESHOLD;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
