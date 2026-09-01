package com.mediascanner.model;

import java.time.Instant;

/**
 * One point-in-time throughput reading for a job (FR-031).
 *
 * <p>{@code elapsedSeconds} is stored rather than derived from {@code sampleAt} so the chart does
 * not need the job start time and downsampling is a plain integer division in SQL.
 */
public class ThroughputSample {

    private String jobId;
    private Instant sampleAt;
    private long elapsedSeconds;
    private double filesPerSec;
    private double mbPerSec;
    private double cpuPercent;
    private double memoryGb;

    public ThroughputSample() {}

    public ThroughputSample(String jobId, Instant sampleAt, long elapsedSeconds,
                            double filesPerSec, double mbPerSec,
                            double cpuPercent, double memoryGb) {
        this.jobId = jobId;
        this.sampleAt = sampleAt;
        this.elapsedSeconds = elapsedSeconds;
        this.filesPerSec = filesPerSec;
        this.mbPerSec = mbPerSec;
        this.cpuPercent = cpuPercent;
        this.memoryGb = memoryGb;
    }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public Instant getSampleAt() { return sampleAt; }
    public void setSampleAt(Instant sampleAt) { this.sampleAt = sampleAt; }
    public long getElapsedSeconds() { return elapsedSeconds; }
    public void setElapsedSeconds(long elapsedSeconds) { this.elapsedSeconds = elapsedSeconds; }
    public double getFilesPerSec() { return filesPerSec; }
    public void setFilesPerSec(double filesPerSec) { this.filesPerSec = filesPerSec; }
    public double getMbPerSec() { return mbPerSec; }
    public void setMbPerSec(double mbPerSec) { this.mbPerSec = mbPerSec; }
    public double getCpuPercent() { return cpuPercent; }
    public void setCpuPercent(double cpuPercent) { this.cpuPercent = cpuPercent; }
    public double getMemoryGb() { return memoryGb; }
    public void setMemoryGb(double memoryGb) { this.memoryGb = memoryGb; }
}
