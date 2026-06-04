package com.mediascanner.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class Job {

    public enum TransferMode { COPY, MOVE }
    public enum FolderPattern { YYYY_MM, YYYY_MMM, YYYY_MMM_DD, YYYY_MM_DD }
    public enum DuplicatePolicy { SKIP, MOVE_TO_BUCKET, KEEP_BOTH }
    public enum Status { RUNNING, PAUSED, COMPLETED, FAILED, STOPPED }

    private static final AtomicInteger dailyCounter = new AtomicInteger(1);
    private static final DateTimeFormatter JOB_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private String jobId;
    private String sourcePath;
    private String targetPath;
    private TransferMode transferMode;
    private FolderPattern folderPattern;
    private DuplicatePolicy duplicatePolicy;
    private Status status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private int imageSizeThresholdKb;
    private int videoSizeThresholdKb;
    private int workerThreadCount;
    private boolean highPriorityMode;
    private List<IgnoreRule> ignoreRules;

    private Job() {}

    public static Job create(String sourcePath, String targetPath,
                             TransferMode transferMode, FolderPattern folderPattern,
                             DuplicatePolicy duplicatePolicy,
                             int imageSizeThresholdKb, int videoSizeThresholdKb,
                             int workerThreadCount, boolean highPriorityMode,
                             List<IgnoreRule> ignoreRules) {
        if (sourcePath == null || targetPath == null) {
            throw new IllegalArgumentException("Source and target paths must not be null");
        }
        if (sourcePath.equals(targetPath)) {
            throw new IllegalArgumentException("Source and target paths must differ");
        }
        java.nio.file.Path src = java.nio.file.Paths.get(sourcePath).toAbsolutePath().normalize();
        java.nio.file.Path tgt = java.nio.file.Paths.get(targetPath).toAbsolutePath().normalize();
        if (tgt.startsWith(src)) {
            throw new IllegalArgumentException("""
                Target directory is inside the source directory.
                The scanner would pick up files it just copied, causing an infinite loop.
                Choose a target outside the source folder.""");
        }
        if (src.startsWith(tgt)) {
            throw new IllegalArgumentException("""
                Source directory is inside the target directory.
                The scanner would read from within its own output area.
                Choose a source outside the target folder.""");
        }
        Job job = new Job();
        job.jobId = "JOB-" + LocalDateTime.now().format(JOB_DATE_FMT)
                  + "-" + String.format("%03d", dailyCounter.getAndIncrement());
        job.sourcePath = sourcePath;
        job.targetPath = targetPath;
        job.transferMode = transferMode;
        job.folderPattern = folderPattern;
        job.duplicatePolicy = duplicatePolicy;
        job.status = Status.RUNNING;
        job.startTime = LocalDateTime.now();
        job.imageSizeThresholdKb = imageSizeThresholdKb;
        job.videoSizeThresholdKb = videoSizeThresholdKb;
        job.workerThreadCount = workerThreadCount;
        job.highPriorityMode = highPriorityMode;
        job.ignoreRules = ignoreRules != null ? ignoreRules : new ArrayList<>();
        return job;
    }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }
    public String getTargetPath() { return targetPath; }
    public void setTargetPath(String targetPath) { this.targetPath = targetPath; }
    public TransferMode getTransferMode() { return transferMode; }
    public void setTransferMode(TransferMode transferMode) { this.transferMode = transferMode; }
    public FolderPattern getFolderPattern() { return folderPattern; }
    public void setFolderPattern(FolderPattern folderPattern) { this.folderPattern = folderPattern; }
    public DuplicatePolicy getDuplicatePolicy() { return duplicatePolicy; }
    public void setDuplicatePolicy(DuplicatePolicy duplicatePolicy) { this.duplicatePolicy = duplicatePolicy; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public int getImageSizeThresholdKb() { return imageSizeThresholdKb; }
    public void setImageSizeThresholdKb(int imageSizeThresholdKb) { this.imageSizeThresholdKb = imageSizeThresholdKb; }
    public int getVideoSizeThresholdKb() { return videoSizeThresholdKb; }
    public void setVideoSizeThresholdKb(int videoSizeThresholdKb) { this.videoSizeThresholdKb = videoSizeThresholdKb; }
    public int getWorkerThreadCount() { return workerThreadCount; }
    public void setWorkerThreadCount(int workerThreadCount) { this.workerThreadCount = workerThreadCount; }
    public boolean isHighPriorityMode() { return highPriorityMode; }
    public void setHighPriorityMode(boolean highPriorityMode) { this.highPriorityMode = highPriorityMode; }
    public List<IgnoreRule> getIgnoreRules() { return ignoreRules; }
    public void setIgnoreRules(List<IgnoreRule> ignoreRules) { this.ignoreRules = ignoreRules; }
}
