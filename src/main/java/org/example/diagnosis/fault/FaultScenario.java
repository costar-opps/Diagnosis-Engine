package org.example.diagnosis.fault;

import lombok.Builder;
import lombok.Data;
import org.example.diagnosis.context.DiagnosisMode;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class FaultScenario {
    private String id;
    private String name;
    /** platform_resource | project_business | change_regression | silent | cross_project */
    private String category;
    private String injectMethod;
    private DiagnosisMode expectedMode;
    @Builder.Default
    private List<String> expectedEvidenceTypes = new ArrayList<>();
    @Builder.Default
    private List<String> conclusionPoints = new ArrayList<>();
    private int passThreshold;
    /** demo | holdout */
    private String set;
    private String targetProject;
}
