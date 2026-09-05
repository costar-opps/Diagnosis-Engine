package org.example.diagnosis.context;

import lombok.Builder;
import lombok.Data;
import org.example.diagnosis.profile.ProjectProfile;
import org.example.diagnosis.profile.RegisteredProject;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class DiagnosisContext {

    private String traceId;
    private String sessionId;
    private DiagnosisMode mode;
    private String projectId;
    private RegisteredProject project;
    private Instant windowStart;
    private Instant windowEnd;
    @Builder.Default
    private ZoneId zoneId = ZoneId.of("Asia/Shanghai");
    @Builder.Default
    private List<String> expansionProjectIds = new ArrayList<>();
    @Builder.Default
    private List<String> expansionNotes = new ArrayList<>();
    private String routingReason;
    private String platformScanSummary;

    public ProjectProfile profile() {
        return project == null ? null : project.getProfile();
    }

    public boolean isProjectMode() {
        return mode == DiagnosisMode.PROJECT;
    }
}
