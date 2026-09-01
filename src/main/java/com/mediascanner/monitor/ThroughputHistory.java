package com.mediascanner.monitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Rolling in-memory window of throughput readings, backing the live dashboard chart (FR-031).
 *
 * <p>Backed by {@link ArrayDeque} rather than {@code ArrayList}: the previous implementation
 * evicted with {@code list.remove(0)}, shifting up to {@value #MAX_SAMPLES} elements once per
 * second per series. Eviction here is O(1).
 *
 * <p>This is the live window only. The durable record lives in {@code JOB_THROUGHPUT_SAMPLE} and is
 * read back through {@code ThroughputSampleDao} for past jobs.
 */
public class ThroughputHistory {

    private static final int MAX_SAMPLES = 3_600;

    private final Deque<Double> filesPerSec = new ArrayDeque<>(MAX_SAMPLES);
    private final Deque<Double> mbPerSec = new ArrayDeque<>(MAX_SAMPLES);
    private final Deque<Double> cpuPercent = new ArrayDeque<>(MAX_SAMPLES);
    private final Deque<Double> memoryPercent = new ArrayDeque<>(MAX_SAMPLES);

    public synchronized void addSample(double fps, double mbps, double cpu, double memPct) {
        addCapped(filesPerSec, fps);
        addCapped(mbPerSec, mbps);
        addCapped(cpuPercent, cpu);
        addCapped(memoryPercent, memPct);
    }

    private void addCapped(Deque<Double> series, double value) {
        if (series.size() >= MAX_SAMPLES) series.pollFirst();
        series.addLast(value);
    }

    public synchronized List<Double> getFilesPerSec() { return new ArrayList<>(filesPerSec); }
    public synchronized List<Double> getMbPerSec() { return new ArrayList<>(mbPerSec); }
    public synchronized List<Double> getCpuPercent() { return new ArrayList<>(cpuPercent); }
    public synchronized List<Double> getMemoryPercent() { return new ArrayList<>(memoryPercent); }

    public synchronized int getSampleCount() { return filesPerSec.size(); }

    public synchronized void clear() {
        filesPerSec.clear();
        mbPerSec.clear();
        cpuPercent.clear();
        memoryPercent.clear();
    }
}
