package org.example.checkpoint;

import lombok.Data;

@Data
public class PendingTool {
    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, UNKNOWN }

    private String key;
    private String name;
    private String arguments;
    private Status status = Status.PENDING;
    private int attempts;
    private String result;
    private String error;
    private long updatedAt;

    public PendingTool() {
    }

    public PendingTool(String key, String name, String arguments) {
        this.key = key;
        this.name = name;
        this.arguments = arguments;
        this.updatedAt = System.currentTimeMillis();
    }
}
