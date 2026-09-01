package com.mediascanner.model;

import java.time.LocalDateTime;

public class JobStatistics {

    private String jobId;
    private long filesProcessed;
    private long filesFailed;
    private long filesSkipped;
    private long duplicatesFound;
    private long filesCopied;
    private long filesMoved;
    private long emptyFilesCount;
    private long smallFilesCount;
    private long corruptFilesCount;
    private long totalBytesProcessed;
    private long totalBytesMoved;
    private long totalBytesCopied;
    private long totalBytesSkipped;
    private long duplicateByteSavings;
    private long totalFoldersCreated;
    private double avgMbPerSec;
    private double peakMbPerSec;
    private double avgFilesPerSec;
    private double peakFilesPerSec;
    private double avgCpuPercent;
    private double peakCpuPercent;
    private double avgMemoryGb;
    private double peakMemoryGb;
    private double peakDiskReadMbSec;
    private double peakDiskWriteMbSec;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    public JobStatistics() {}

    public JobStatistics(String jobId, LocalDateTime startTime) {
        this.jobId = jobId;
        this.startTime = startTime;
        this.status = "RUNNING";
    }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public long getFilesProcessed() { return filesProcessed; }
    public void setFilesProcessed(long filesProcessed) { this.filesProcessed = filesProcessed; }
    public long getFilesFailed() { return filesFailed; }
    public void setFilesFailed(long filesFailed) { this.filesFailed = filesFailed; }
    public long getFilesSkipped() { return filesSkipped; }
    public void setFilesSkipped(long filesSkipped) { this.filesSkipped = filesSkipped; }
    public long getDuplicatesFound() { return duplicatesFound; }
    public void setDuplicatesFound(long duplicatesFound) { this.duplicatesFound = duplicatesFound; }
    public long getFilesCopied() { return filesCopied; }
    public void setFilesCopied(long filesCopied) { this.filesCopied = filesCopied; }
    public long getFilesMoved() { return filesMoved; }
    public void setFilesMoved(long filesMoved) { this.filesMoved = filesMoved; }
    public long getEmptyFilesCount() { return emptyFilesCount; }
    public void setEmptyFilesCount(long emptyFilesCount) { this.emptyFilesCount = emptyFilesCount; }
    public long getSmallFilesCount() { return smallFilesCount; }
    public void setSmallFilesCount(long smallFilesCount) { this.smallFilesCount = smallFilesCount; }
    public long getCorruptFilesCount() { return corruptFilesCount; }
    public void setCorruptFilesCount(long corruptFilesCount) { this.corruptFilesCount = corruptFilesCount; }
    public long getTotalBytesProcessed() { return totalBytesProcessed; }
    public void setTotalBytesProcessed(long totalBytesProcessed) { this.totalBytesProcessed = totalBytesProcessed; }
    public long getTotalBytesMoved() { return totalBytesMoved; }
    public void setTotalBytesMoved(long totalBytesMoved) { this.totalBytesMoved = totalBytesMoved; }
    public long getTotalBytesCopied() { return totalBytesCopied; }
    public void setTotalBytesCopied(long totalBytesCopied) { this.totalBytesCopied = totalBytesCopied; }
    public long getTotalBytesSkipped() { return totalBytesSkipped; }
    public void setTotalBytesSkipped(long totalBytesSkipped) { this.totalBytesSkipped = totalBytesSkipped; }
    public long getDuplicateByteSavings() { return duplicateByteSavings; }
    public void setDuplicateByteSavings(long duplicateByteSavings) { this.duplicateByteSavings = duplicateByteSavings; }
    public long getTotalFoldersCreated() { return totalFoldersCreated; }
    public void setTotalFoldersCreated(long totalFoldersCreated) { this.totalFoldersCreated = totalFoldersCreated; }
    public double getAvgMbPerSec() { return avgMbPerSec; }
    public void setAvgMbPerSec(double avgMbPerSec) { this.avgMbPerSec = avgMbPerSec; }
    public double getPeakMbPerSec() { return peakMbPerSec; }
    public void setPeakMbPerSec(double peakMbPerSec) { this.peakMbPerSec = peakMbPerSec; }
    public double getAvgFilesPerSec() { return avgFilesPerSec; }
    public void setAvgFilesPerSec(double avgFilesPerSec) { this.avgFilesPerSec = avgFilesPerSec; }
    public double getPeakFilesPerSec() { return peakFilesPerSec; }
    public void setPeakFilesPerSec(double peakFilesPerSec) { this.peakFilesPerSec = peakFilesPerSec; }
    public double getAvgCpuPercent() { return avgCpuPercent; }
    public void setAvgCpuPercent(double avgCpuPercent) { this.avgCpuPercent = avgCpuPercent; }
    public double getPeakCpuPercent() { return peakCpuPercent; }
    public void setPeakCpuPercent(double peakCpuPercent) { this.peakCpuPercent = peakCpuPercent; }
    public double getAvgMemoryGb() { return avgMemoryGb; }
    public void setAvgMemoryGb(double avgMemoryGb) { this.avgMemoryGb = avgMemoryGb; }
    public double getPeakMemoryGb() { return peakMemoryGb; }
    public void setPeakMemoryGb(double peakMemoryGb) { this.peakMemoryGb = peakMemoryGb; }
    public double getPeakDiskReadMbSec() { return peakDiskReadMbSec; }
    public void setPeakDiskReadMbSec(double v) { this.peakDiskReadMbSec = v; }
    public double getPeakDiskWriteMbSec() { return peakDiskWriteMbSec; }
    public void setPeakDiskWriteMbSec(double v) { this.peakDiskWriteMbSec = v; }

    /**
     * A field-by-field copy. Callers take this under the object's own lock so every counter comes
     * from one instant — reading the live object piecemeal mixes counters mutated microseconds
     * apart by different workers.
     */
    public JobStatistics copy() {
        JobStatistics c = new JobStatistics();
        c.jobId = jobId;
        c.filesProcessed = filesProcessed;
        c.filesFailed = filesFailed;
        c.filesSkipped = filesSkipped;
        c.duplicatesFound = duplicatesFound;
        c.filesCopied = filesCopied;
        c.filesMoved = filesMoved;
        c.emptyFilesCount = emptyFilesCount;
        c.smallFilesCount = smallFilesCount;
        c.corruptFilesCount = corruptFilesCount;
        c.totalBytesProcessed = totalBytesProcessed;
        c.totalBytesMoved = totalBytesMoved;
        c.totalBytesCopied = totalBytesCopied;
        c.totalBytesSkipped = totalBytesSkipped;
        c.duplicateByteSavings = duplicateByteSavings;
        c.totalFoldersCreated = totalFoldersCreated;
        c.avgMbPerSec = avgMbPerSec;
        c.peakMbPerSec = peakMbPerSec;
        c.avgFilesPerSec = avgFilesPerSec;
        c.peakFilesPerSec = peakFilesPerSec;
        c.avgCpuPercent = avgCpuPercent;
        c.peakCpuPercent = peakCpuPercent;
        c.avgMemoryGb = avgMemoryGb;
        c.peakMemoryGb = peakMemoryGb;
        c.peakDiskReadMbSec = peakDiskReadMbSec;
        c.peakDiskWriteMbSec = peakDiskWriteMbSec;
        c.status = status;
        c.startTime = startTime;
        c.endTime = endTime;
        return c;
    }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
}
