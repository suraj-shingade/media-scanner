package com.mediascanner.model;

import java.time.Instant;
import java.time.LocalDateTime;

public class FileHashRecord {

    private long id;
    private String filePath;
    private String fileName;
    private long fileSizeBytes;
    private Instant fileModificationTs;
    private String sha256Hash;
    private LocalDateTime mediaDate;
    private Instant createdAt;
    private Instant lastProcessedAt;

    public FileHashRecord() {}

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }
    public Instant getFileModificationTs() { return fileModificationTs; }
    public void setFileModificationTs(Instant fileModificationTs) { this.fileModificationTs = fileModificationTs; }
    public String getSha256Hash() { return sha256Hash; }
    public void setSha256Hash(String sha256Hash) { this.sha256Hash = sha256Hash; }
    public LocalDateTime getMediaDate() { return mediaDate; }
    public void setMediaDate(LocalDateTime mediaDate) { this.mediaDate = mediaDate; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getLastProcessedAt() { return lastProcessedAt; }
    public void setLastProcessedAt(Instant lastProcessedAt) { this.lastProcessedAt = lastProcessedAt; }
}
