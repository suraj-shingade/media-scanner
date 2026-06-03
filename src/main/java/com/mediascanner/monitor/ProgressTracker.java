package com.mediascanner.monitor;

import java.util.concurrent.atomic.AtomicLong;

public class ProgressTracker {

    private static final int ROLLING_SHORT = 5;
    private static final int ROLLING_LONG = 150;

    private final AtomicLong filesProcessed = new AtomicLong();
    private final AtomicLong filesFailed = new AtomicLong();
    private final AtomicLong filesSkipped = new AtomicLong();
    private final AtomicLong filesDuplicate = new AtomicLong();
    private final AtomicLong filesTotal = new AtomicLong();
    private final AtomicLong bytesProcessed = new AtomicLong();
    private final AtomicLong bytesFailed = new AtomicLong();
    private final AtomicLong bytesSkipped = new AtomicLong();

    // Circular buffers for rolling averages (files/sec and MB/sec)
    private final double[] shortFilesBuffer = new double[ROLLING_SHORT];
    private final double[] longFilesBuffer = new double[ROLLING_LONG];
    private int shortIdx = 0;
    private int longIdx = 0;
    private int shortCount = 0;
    private int longCount = 0;

    private volatile long lastSampleTime = System.currentTimeMillis();
    private volatile long lastSampleFiles = 0;
    private volatile long lastSampleBytes = 0;

    private volatile double avgFilesPerSec5s = 0;
    private volatile double avgFilesPerSec30s = 0;
    private volatile double avgFilesPerSecJob = 0;
    private volatile double avgMbPerSec5s = 0;
    private volatile double etaSeconds = 0;

    private final long startTime = System.currentTimeMillis();

    public void tick() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastSampleTime;
        if (elapsed < 100) return;

        long currentFiles = filesProcessed.get();
        long currentBytes = bytesProcessed.get();
        double deltaFiles = currentFiles - lastSampleFiles;
        double deltaBytes = currentBytes - lastSampleBytes;
        double elapsedSec = elapsed / 1000.0;

        double filesPerSec = deltaFiles / elapsedSec;
        double mbPerSec = (deltaBytes / 1024.0 / 1024.0) / elapsedSec;

        synchronized (this) {
            shortFilesBuffer[shortIdx % ROLLING_SHORT] = filesPerSec;
            shortIdx++;
            shortCount = Math.min(shortCount + 1, ROLLING_SHORT);

            longFilesBuffer[longIdx % ROLLING_LONG] = filesPerSec;
            longIdx++;
            longCount = Math.min(longCount + 1, ROLLING_LONG);

            avgFilesPerSec5s = average(shortFilesBuffer, shortCount);
            avgFilesPerSec30s = average(longFilesBuffer, longCount);
        }

        long jobElapsedSec = (now - startTime) / 1000;
        avgFilesPerSecJob = jobElapsedSec > 0 ? (double) currentFiles / jobElapsedSec : 0;
        avgMbPerSec5s = mbPerSec;

        long remaining = filesTotal.get() - currentFiles;
        long remainingBytes = 0;
        if (avgFilesPerSec5s > 0 && remaining > 0) {
            etaSeconds = remaining / avgFilesPerSec5s;
        }

        lastSampleTime = now;
        lastSampleFiles = currentFiles;
        lastSampleBytes = currentBytes;
    }

    private double average(double[] buf, int count) {
        if (count == 0) return 0;
        double sum = 0;
        for (int i = 0; i < count; i++) sum += buf[i];
        return sum / count;
    }

    public Snapshot snapshot() {
        return new Snapshot(
            filesProcessed.get(), filesFailed.get(), filesSkipped.get(),
            filesDuplicate.get(), filesTotal.get(), bytesProcessed.get(),
            avgFilesPerSec5s, avgFilesPerSec30s, avgFilesPerSecJob,
            avgMbPerSec5s, etaSeconds
        );
    }

    public void incrementProcessed(long bytes) {
        filesProcessed.incrementAndGet();
        bytesProcessed.addAndGet(bytes);
    }
    public void incrementFailed() { filesFailed.incrementAndGet(); }
    public void incrementSkipped() { filesSkipped.incrementAndGet(); }
    public void incrementDuplicate() { filesDuplicate.incrementAndGet(); }
    public void setFilesTotal(long total) { filesTotal.set(total); }

    public AtomicLong getFilesProcessed() { return filesProcessed; }
    public AtomicLong getFilesFailed() { return filesFailed; }
    public AtomicLong getFilesSkipped() { return filesSkipped; }
    public AtomicLong getFilesDuplicate() { return filesDuplicate; }

    public static class Snapshot {
        public final long filesProcessed, filesFailed, filesSkipped, filesDuplicate, filesTotal;
        public final long bytesProcessed;
        public final double avgFilesPerSec5s, avgFilesPerSec30s, avgFilesPerSecJob;
        public final double avgMbPerSec5s;
        public final double etaSeconds;

        Snapshot(long filesProcessed, long filesFailed, long filesSkipped,
                 long filesDuplicate, long filesTotal, long bytesProcessed,
                 double avgFilesPerSec5s, double avgFilesPerSec30s, double avgFilesPerSecJob,
                 double avgMbPerSec5s, double etaSeconds) {
            this.filesProcessed = filesProcessed;
            this.filesFailed = filesFailed;
            this.filesSkipped = filesSkipped;
            this.filesDuplicate = filesDuplicate;
            this.filesTotal = filesTotal;
            this.bytesProcessed = bytesProcessed;
            this.avgFilesPerSec5s = avgFilesPerSec5s;
            this.avgFilesPerSec30s = avgFilesPerSec30s;
            this.avgFilesPerSecJob = avgFilesPerSecJob;
            this.avgMbPerSec5s = avgMbPerSec5s;
            this.etaSeconds = etaSeconds;
        }
    }
}
