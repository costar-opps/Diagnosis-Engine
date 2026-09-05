package org.example.diagnosis.knowledge;

import org.example.diagnosis.context.DiagnosisMode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ScopedKnowledgeStore {

    public record Doc(String id, String projectId, String scope, String title, String body, String sourceReportId,
                      boolean published) {
    }

    private final List<Doc> docs = new CopyOnWriteArrayList<>();

    public ScopedKnowledgeStore() {
        docs.add(new Doc("plat-cpu", null, "platform", "主机内存高压处置",
                "内存打满时先确认占用主体，避免误杀业务进程；受影响项目做限流与扩容。", null, true));
        docs.add(new Doc("plat-disk", null, "platform", "磁盘将满与日志落盘",
                "磁盘超 90% 时优先清理日志与临时文件，防止 CLS/本地落盘失败。", null, true));
        docs.add(new Doc("card-timeout", "card-service", "project", "出卡超时处置",
                "出卡 P99 升高时检查风控同步调用，改为异步或增加超时预算。", null, true));
        docs.add(new Doc("card-inventory", "card-service", "project", "卡号预占失败",
                "库存预占失败优先检查卡号池水位与分布式锁。", null, true));
        docs.add(new Doc("pay-timeout", "payment-service", "project", "支付渠道超时",
                "渠道超时先看渠道侧 SLA，再决定是否熔断回退。", null, true));
    }

    public List<Doc> search(DiagnosisMode mode, String projectId, boolean crossProject, String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<Doc> hits = new ArrayList<>();
        for (Doc doc : docs) {
            if (!doc.published) {
                continue;
            }
            if (!matchScope(mode, projectId, crossProject, doc)) {
                continue;
            }
            if (q.isBlank() || doc.title.toLowerCase(Locale.ROOT).contains(q)
                    || doc.body.toLowerCase(Locale.ROOT).contains(q)) {
                hits.add(doc);
            }
        }
        return hits;
    }

    public void publish(Doc doc) {
        docs.removeIf(existing -> existing.id.equals(doc.id));
        docs.add(doc);
    }

    public List<Doc> allPublished() {
        return docs.stream().filter(Doc::published).toList();
    }

    private static boolean matchScope(DiagnosisMode mode, String projectId, boolean crossProject, Doc doc) {
        if ("platform".equals(doc.scope)) {
            return true;
        }
        if (mode == DiagnosisMode.PLATFORM) {
            return false;
        }
        if (projectId != null && projectId.equals(doc.projectId)) {
            return true;
        }
        return crossProject;
    }
}
