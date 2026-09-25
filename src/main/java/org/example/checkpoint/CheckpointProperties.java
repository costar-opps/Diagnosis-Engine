package org.example.checkpoint;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "checkpoint")
public class CheckpointProperties {
    private boolean enabled = true;
    private String directory = "./runtime/checkpoints";
    private String hmacSecret = "";
    private int maxFailedRetries = 2;
    private int recentConversationPairs = 6;
}
