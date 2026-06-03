package com.mediascanner.model;

public class SkippedRecord {

    private String filePath;
    private MediaFile.SkipReason reason;

    public SkippedRecord() {}

    public SkippedRecord(String filePath, MediaFile.SkipReason reason) {
        this.filePath = filePath;
        this.reason = reason;
    }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public MediaFile.SkipReason getReason() { return reason; }
    public void setReason(MediaFile.SkipReason reason) { this.reason = reason; }
}
