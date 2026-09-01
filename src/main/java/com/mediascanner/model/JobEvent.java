package com.mediascanner.model;

import java.time.Instant;

/**
 * One non-transferred file outcome within a job — the record behind the skipped, failure and
 * duplicate reports (FR-019, FR-020, FR-023).
 *
 * <p>Successfully transferred files are deliberately not recorded: at 10M files that would be a
 * 10M-row table per job with no reader, and {@link JobStatistics} already carries the aggregate.
 *
 * <p>Replaces the former {@code SkippedRecord} and {@code FailureRecord}, which modelled the same
 * shape with gratuitously different fields and were written and read by nothing.
 */
public class JobEvent {

    public enum Outcome { SKIPPED, FAILED, DUPLICATE }

    private long id;
    private String jobId;
    private Outcome outcome;
    private String filePath;
    private String fileName;
    private long fileSize;
    /** A {@link MediaFile.SkipReason} name for SKIPPED, or the failure message for FAILED. */
    private String reason;
    /** Set for DUPLICATE only. */
    private String sha256Hash;
    /** The canonical path this duplicate matched. Set for DUPLICATE only. */
    private String matchedPath;
    /** Where the file was actually written under the Move-to-bucket and Keep-Both policies. */
    private String destinationPath;
    private Instant recordedAt;

    public JobEvent() {}

    private JobEvent(String jobId, Outcome outcome, MediaFile file, String reason) {
        this.jobId = jobId;
        this.outcome = outcome;
        this.filePath = file.getAbsolutePath();
        this.fileName = file.getFileName();
        this.fileSize = file.getSizeBytes();
        this.reason = reason;
        this.recordedAt = Instant.now();
    }

    public static JobEvent skipped(String jobId, MediaFile file, MediaFile.SkipReason reason) {
        return new JobEvent(jobId, Outcome.SKIPPED, file,
            reason != null ? reason.name() : "UNKNOWN");
    }

    public static JobEvent failed(String jobId, MediaFile file, String reason) {
        return new JobEvent(jobId, Outcome.FAILED, file,
            reason != null && !reason.isBlank() ? reason : "Unspecified failure");
    }

    public static JobEvent duplicate(String jobId, MediaFile file, String sha256Hash,
                                     String matchedPath, String destinationPath) {
        JobEvent event = new JobEvent(jobId, Outcome.DUPLICATE, file, "CONTENT_DUPLICATE");
        event.sha256Hash = sha256Hash;
        event.matchedPath = matchedPath;
        event.destinationPath = destinationPath;
        return event;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public Outcome getOutcome() { return outcome; }
    public void setOutcome(Outcome outcome) { this.outcome = outcome; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getSha256Hash() { return sha256Hash; }
    public void setSha256Hash(String sha256Hash) { this.sha256Hash = sha256Hash; }
    public String getMatchedPath() { return matchedPath; }
    public void setMatchedPath(String matchedPath) { this.matchedPath = matchedPath; }
    public String getDestinationPath() { return destinationPath; }
    public void setDestinationPath(String destinationPath) { this.destinationPath = destinationPath; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
}
