package org.example.diagnosis.telemetry;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetricSnapshot {
    private String name;
    private double value;
    private String unit;
    private Double threshold;
    private String thresholdDirection;
    private String meaning;
    private boolean available = true;
    private List<Double> series = new ArrayList<>();
}
