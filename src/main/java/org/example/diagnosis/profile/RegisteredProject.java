package org.example.diagnosis.profile;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class RegisteredProject {
    private ProjectProfile profile;
    private boolean diagnosable;
    private String reason;
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    public String getId() {
        return profile == null || profile.getIdentity() == null ? null : profile.getIdentity().getId();
    }

    public String getName() {
        return profile == null || profile.getIdentity() == null ? null : profile.getIdentity().getName();
    }
}
