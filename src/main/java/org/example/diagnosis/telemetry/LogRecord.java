package org.example.diagnosis.telemetry;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogRecord {
    private String time;
    private String level;
    private String service;
    private String message;
    private String stack;
}
