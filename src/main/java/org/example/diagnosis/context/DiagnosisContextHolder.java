package org.example.diagnosis.context;

public final class DiagnosisContextHolder {

    private static final ThreadLocal<DiagnosisContext> HOLDER = new ThreadLocal<>();

    private DiagnosisContextHolder() {
    }

    public static void set(DiagnosisContext context) {
        HOLDER.set(context);
    }

    public static DiagnosisContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
