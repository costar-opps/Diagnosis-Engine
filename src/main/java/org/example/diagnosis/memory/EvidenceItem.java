package org.example.diagnosis.memory;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EvidenceItem {
    /** metric | log | change | code | knowledge | platform */
    private String category;
    private String title;
    private String summary;
    private String projectScope;
    /** range | root_cause | historical_clue */
    private String role;
    private RawRef rawRef;
}
