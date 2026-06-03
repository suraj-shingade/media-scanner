package com.mediascanner.model;

public class FailureRecord {

    private String filePath;
    private String reason;
    private String timestamp;

    public FailureRecord() {}

    public FailureRecord(String filePath, String reason, String timestamp) {
        this.filePath = filePath;
        this.reason = reason;
        this.timestamp = timestamp;
    }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
