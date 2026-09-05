package org.example.diagnosis;

import org.example.diagnosis.profile.ProjectProfile;
import org.example.diagnosis.profile.ProjectProfileValidator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectProfileValidatorTest {

    private final ProjectProfileValidator validator = new ProjectProfileValidator(new DiagnosisProperties());

    @Test
    void missingRequiredFields() {
        ProjectProfile profile = new ProjectProfile();
        var result = validator.validate(profile, Set.of());
        assertFalse(result.valid());
        assertTrue(result.reason().contains("identity.id"));
    }

    @Test
    void danglingDependencyIsWarning() {
        ProjectProfile profile = validProfile("card-service");
        profile.getDependencies().getDownstream().add("ghost-service");
        var result = validator.validate(profile, Set.of("card-service"));
        assertTrue(result.valid());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("ghost-service")));
    }

    @Test
    void duplicateHandledByCallerButIdRequired() {
        ProjectProfile a = validProfile("card-service");
        var result = validator.validate(a, Set.of("card-service", "payment-service"));
        assertTrue(result.valid());
    }

    static ProjectProfile validProfile(String id) {
        ProjectProfile profile = new ProjectProfile();
        profile.getIdentity().setId(id);
        profile.getIdentity().setName(id);
        profile.getDatasources().getLogs().setType("demo");
        profile.getDatasources().getLogs().setLocator("demo://" + id);
        ProjectProfile.MetricDef metric = new ProjectProfile.MetricDef();
        metric.setName("latency");
        metric.setPromQL("latency");
        profile.getDatasources().getMetrics().add(metric);
        profile.getKnowledge().setNamespace(id);
        return profile;
    }
}
