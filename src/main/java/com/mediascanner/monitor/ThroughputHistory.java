package com.mediascanner.monitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ThroughputHistory {

    private static final int MAX_SAMPLES = 3_600;

    private final List<Double> filesPerSec = new ArrayList<>();
    private final List<Double> mbPerSec = new ArrayList<>();
    private final List<Double> cpuPercent = new ArrayList<>();
    private final List<Double> memoryPercent = new ArrayList<>();

    public synchronized void addSample(double fps, double mbps, double cpu, double memPct) {
        addCapped(filesPerSec, fps);
        addCapped(mbPerSec, mbps);
        addCapped(cpuPercent, cpu);
        addCapped(memoryPercent, memPct);
    }

    private void addCapped(List<Double> list, double value) {
        if (list.size() >= MAX_SAMPLES) list.remove(0);
        list.add(value);
    }

    public synchronized List<Double> getFilesPerSec() {
        return Collections.unmodifiableList(filesPerSec);
    }
    public synchronized List<Double> getMbPerSec() {
        return Collections.unmodifiableList(mbPerSec);
    }
    public synchronized List<Double> getCpuPercent() {
        return Collections.unmodifiableList(cpuPercent);
    }
    public synchronized List<Double> getMemoryPercent() {
        return Collections.unmodifiableList(memoryPercent);
    }

    public synchronized int getSampleCount() { return filesPerSec.size(); }
}
