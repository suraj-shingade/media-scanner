package com.mediascanner.model;

public class CheckpointState {

    private String jobId;
    private String status;
    private String sourcePath;
    private String targetPath;
    private long processedFiles;
    private long failedFiles;
    private long skippedFiles;
    private long emptyFiles;
    private long smallFiles;
    private String checkpointTime;

    public CheckpointState() {}

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }
    public String getTargetPath() { return targetPath; }
    public void setTargetPath(String targetPath) { this.targetPath = targetPath; }
    public long getProcessedFiles() { return processedFiles; }
    public void setProcessedFiles(long processedFiles) { this.processedFiles = processedFiles; }
    public long getFailedFiles() { return failedFiles; }
    public void setFailedFiles(long failedFiles) { this.failedFiles = failedFiles; }
    public long getSkippedFiles() { return skippedFiles; }
    public void setSkippedFiles(long skippedFiles) { this.skippedFiles = skippedFiles; }
    public long getEmptyFiles() { return emptyFiles; }
    public void setEmptyFiles(long emptyFiles) { this.emptyFiles = emptyFiles; }
    public long getSmallFiles() { return smallFiles; }
    public void setSmallFiles(long smallFiles) { this.smallFiles = smallFiles; }
    public String getCheckpointTime() { return checkpointTime; }
    public void setCheckpointTime(String checkpointTime) { this.checkpointTime = checkpointTime; }
}
