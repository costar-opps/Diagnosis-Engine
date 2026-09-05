package org.example.diagnosis.context;

public enum DiagnosisMode {
    PLATFORM,
    PROJECT;

    public static DiagnosisMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return PLATFORM;
        }
        return DiagnosisMode.valueOf(raw.trim().toUpperCase());
    }
}
