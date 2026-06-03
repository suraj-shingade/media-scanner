package com.mediascanner.model;

import java.time.Instant;
import java.time.LocalDateTime;

public class MediaFile {

    public enum FileType { IMAGE, VIDEO }

    public enum ValidationStatus { PENDING, VALID, SKIPPED, FAILED }

    public enum SkipReason {
        EMPTY_FILE, SMALL_FILE, UNSUPPORTED_FORMAT, IGNORE_RULE_MATCHED, METADATA_MISSING
    }

    public enum DateSource { EMBEDDED_CAPTURE, FILE_CREATION, FILE_MODIFIED }

    public enum Outcome { PENDING, TRANSFERRED, SKIPPED, FAILED, DUPLICATE }

    private String absolutePath;
    private String fileName;
    private String extension;
    private FileType fileType;
    private long sizeBytes;
    private Instant modificationTimestamp;
    private ValidationStatus validationStatus = ValidationStatus.PENDING;
    private SkipReason skipReason;
    private String failureReason;
    private LocalDateTime extractedDate;
    private DateSource dateSource;
    private String destinationPath;
    private Outcome outcome = Outcome.PENDING;
    private FileHashRecord hashRecord;

    public MediaFile() {}

    public String getAbsolutePath() { return absolutePath; }
    public void setAbsolutePath(String absolutePath) { this.absolutePath = absolutePath; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getExtension() { return extension; }
    public void setExtension(String extension) { this.extension = extension; }
    public FileType getFileType() { return fileType; }
    public void setFileType(FileType fileType) { this.fileType = fileType; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Instant getModificationTimestamp() { return modificationTimestamp; }
    public void setModificationTimestamp(Instant modificationTimestamp) { this.modificationTimestamp = modificationTimestamp; }
    public ValidationStatus getValidationStatus() { return validationStatus; }
    public void setValidationStatus(ValidationStatus validationStatus) { this.validationStatus = validationStatus; }
    public SkipReason getSkipReason() { return skipReason; }
    public void setSkipReason(SkipReason skipReason) { this.skipReason = skipReason; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public LocalDateTime getExtractedDate() { return extractedDate; }
    public void setExtractedDate(LocalDateTime extractedDate) { this.extractedDate = extractedDate; }
    public DateSource getDateSource() { return dateSource; }
    public void setDateSource(DateSource dateSource) { this.dateSource = dateSource; }
    public String getDestinationPath() { return destinationPath; }
    public void setDestinationPath(String destinationPath) { this.destinationPath = destinationPath; }
    public Outcome getOutcome() { return outcome; }
    public void setOutcome(Outcome outcome) { this.outcome = outcome; }
    public FileHashRecord getHashRecord() { return hashRecord; }
    public void setHashRecord(FileHashRecord hashRecord) { this.hashRecord = hashRecord; }
}
