package org.example.diagnosis.profile;

import org.example.diagnosis.DiagnosisProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class ProjectProfileValidator {

    private static final Set<String> LOG_TYPES = Set.of("cls", "file", "es", "demo");
    private static final Set<String> CHANGE_TYPES = Set.of("demo", "git", "none");

    private final DiagnosisProperties properties;

    public ProjectProfileValidator(DiagnosisProperties properties) {
        this.properties = properties;
    }

    public ValidationResult validate(ProjectProfile profile, Set<String> knownIds) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (profile == null || profile.getIdentity() == null) {
            errors.add("缺少 identity 分组");
            return new ValidationResult(false, String.join("；", errors), warnings);
        }
        ProjectProfile.Identity identity = profile.getIdentity();
        if (isBlank(identity.getId())) {
            errors.add("identity.id 必填");
        }
        if (isBlank(identity.getName())) {
            errors.add("identity.name 必填");
        }

        ProjectProfile.Datasources datasources = profile.getDatasources();
        if (datasources == null) {
            errors.add("缺少 datasources 分组");
        } else {
            ProjectProfile.LogSource logs = datasources.getLogs();
            if (logs == null || isBlank(logs.getType()) || isBlank(logs.getLocator())) {
                errors.add("datasources.logs.type 与 locator 必填");
            } else if (!LOG_TYPES.contains(logs.getType().toLowerCase(Locale.ROOT))) {
                errors.add("不支持的日志源类型: " + logs.getType() + "，允许 cls/file/es/demo");
            }
            if (datasources.getMetrics() == null || datasources.getMetrics().isEmpty()) {
                errors.add("datasources.metrics 至少声明一条业务指标");
            } else {
                for (ProjectProfile.MetricDef metric : datasources.getMetrics()) {
                    if (isBlank(metric.getName()) || isBlank(metric.getPromQL())) {
                        errors.add("指标 name 与 promQL 均不可为空");
                    }
                }
            }
            ProjectProfile.ChangeSource change = datasources.getChange();
            if (change != null && !isBlank(change.getType())
                    && !CHANGE_TYPES.contains(change.getType().toLowerCase(Locale.ROOT))) {
                errors.add("不支持的变更源类型: " + change.getType());
            }
        }

        if (profile.getKnowledge() == null || isBlank(profile.getKnowledge().getNamespace())) {
            errors.add("knowledge.namespace 必填");
        }
        if (profile.getBusiness() == null) {
            errors.add("缺少 business 分组");
        } else {
            int max = properties.getContext().getBusinessFieldMaxChars();
            if (overLimit(profile.getBusiness().getExtraPromptHint(), properties.getContext().getExtraPromptHintMaxChars())) {
                errors.add("business.extraPromptHint 超出摘要长度上限，正文应放入知识库");
            }
            if (profile.getBusiness().getKnownFaults() != null) {
                for (ProjectProfile.KnownFault fault : profile.getBusiness().getKnownFaults()) {
                    if (overLimit(fault.getHint(), max) || overLimit(fault.getPattern(), max)) {
                        errors.add("业务语义字段包含超出摘要长度上限的文档正文");
                    }
                }
            }
        }
        if (profile.getDependencies() != null && knownIds != null) {
            for (String dep : concat(profile.getDependencies().getUpstream(), profile.getDependencies().getDownstream())) {
                if (!isBlank(dep) && !knownIds.contains(dep)) {
                    warnings.add("依赖引用了未注册项目: " + dep + "，跨项目扩展将跳过该依赖");
                }
            }
        }
        if (profile.getOps() == null) {
            errors.add("缺少 ops 分组");
        }
        boolean ok = errors.isEmpty();
        return new ValidationResult(ok, ok ? "" : String.join("；", errors), warnings);
    }

    private static boolean overLimit(String text, int max) {
        return text != null && text.length() > max;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>();
        if (a != null) all.addAll(a);
        if (b != null) all.addAll(b);
        return all;
    }

    public record ValidationResult(boolean valid, String reason, List<String> warnings) {
    }
}
