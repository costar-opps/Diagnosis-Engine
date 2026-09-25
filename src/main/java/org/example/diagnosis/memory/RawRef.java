package org.example.diagnosis.memory;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RawRef {
    private String queryId;
    private String source;
    private String windowStart;
    private String windowEnd;
    private int totalCount;
    private String locator;
}
