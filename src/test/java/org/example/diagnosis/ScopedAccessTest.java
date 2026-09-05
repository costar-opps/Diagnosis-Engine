package org.example.diagnosis;

import org.example.diagnosis.access.ScopedDataAccess;
import org.example.diagnosis.context.DiagnosisContext;
import org.example.diagnosis.context.DiagnosisContextHolder;
import org.example.diagnosis.context.DiagnosisMode;
import org.example.diagnosis.memory.ToolResultSummarizer;
import org.example.diagnosis.profile.RegisteredProject;
import org.example.diagnosis.telemetry.DemoTelemetryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopedAccessTest {

    @AfterEach
    void cleanup() {
        DiagnosisContextHolder.clear();
    }

    @Test
    void rejectUnregisteredOrMismatchedProject() {
        ScopedDataAccess access = new ScopedDataAccess(new DemoTelemetryStore(), new ToolResultSummarizer(new DiagnosisProperties()));
        DiagnosisContextHolder.set(DiagnosisContext.builder()
                .mode(DiagnosisMode.PROJECT)
                .projectId("card-service")
                .project(RegisteredProject.builder().diagnosable(true).profile(ProjectProfileValidatorTest.validProfile("card-service")).build())
                .build());
        assertThrows(IllegalArgumentException.class, () -> access.assertProject("payment-service"));
    }

    @Test
    void rejectUndiagnosableProject() {
        ScopedDataAccess access = new ScopedDataAccess(new DemoTelemetryStore(), new ToolResultSummarizer(new DiagnosisProperties()));
        DiagnosisContextHolder.set(DiagnosisContext.builder()
                .mode(DiagnosisMode.PROJECT)
                .projectId("legacy-report-center")
                .project(RegisteredProject.builder().diagnosable(false).reason("缺少日志").build())
                .build());
        var ex = assertThrows(IllegalArgumentException.class, () -> access.assertProject("legacy-report-center"));
        assertTrue(ex.getMessage().contains("不可诊断"));
    }
}
