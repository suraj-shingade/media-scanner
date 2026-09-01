package com.mediascanner.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Samples CPU, memory, disk throughput and worker count once a second (FR-030).
 *
 * <p>The disk figures describe <em>this job's</em> I/O, derived from the bytes the engine reports
 * reading and writing. They are not a system-wide disk monitor: measuring that portably needs a
 * native library, and the technology stack is locked by the constitution. What a user watching their
 * own transfer wants to know is how fast that transfer is moving, which is what this reports.
 */
public class ResourceMonitor {

    private static final Logger log = LoggerFactory.getLogger(ResourceMonitor.class);

    private volatile double cpuPercent = 0.0;
    private volatile double memoryGb = 0.0;
    private volatile double diskReadMbSec = 0.0;
    private volatile double diskWriteMbSec = 0.0;
    private volatile double peakDiskReadMbSec = 0.0;
    private volatile double peakDiskWriteMbSec = 0.0;
    private volatile int activeThreads = 0;

    /** Cumulative bytes the job has read / written. Supplied by the engine; zero when idle. */
    private volatile LongSupplier bytesReadSupplier = () -> 0L;
    private volatile LongSupplier bytesWrittenSupplier = () -> 0L;
    private volatile IntSupplier activeWorkerSupplier = () -> 0;

    private long lastBytesRead = 0;
    private long lastBytesWritten = 0;
    private long lastSampleMillis = 0;

    private ScheduledExecutorService scheduler;
    private com.sun.management.OperatingSystemMXBean osMxBean;

    public ResourceMonitor() {
        try {
            osMxBean = (com.sun.management.OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean();
        } catch (ClassCastException e) {
            log.warn("Extended OS MXBean not available: {}", e.getMessage());
        }
    }

    /**
     * Wires the monitor to the running job, so disk throughput and worker count are measured rather
     * than reported as hardcoded zeros.
     */
    public void bindJobSources(LongSupplier bytesRead, LongSupplier bytesWritten,
                               IntSupplier activeWorkers) {
        this.bytesReadSupplier = bytesRead != null ? bytesRead : () -> 0L;
        this.bytesWrittenSupplier = bytesWritten != null ? bytesWritten : () -> 0L;
        this.activeWorkerSupplier = activeWorkers != null ? activeWorkers : () -> 0;
    }

    public void start() {
        lastSampleMillis = System.currentTimeMillis();
        lastBytesRead = bytesReadSupplier.getAsLong();
        lastBytesWritten = bytesWrittenSupplier.getAsLong();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "resource-monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::sample, 1, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        diskReadMbSec = 0;
        diskWriteMbSec = 0;
        activeThreads = 0;
    }

    private void sample() {
        try {
            if (osMxBean != null) {
                // getSystemCpuLoad() is deprecated since Java 14 in favour of getCpuLoad().
                double cpu = osMxBean.getCpuLoad();
                if (cpu >= 0) cpuPercent = cpu * 100.0;
            }

            Runtime rt = Runtime.getRuntime();
            long usedBytes = rt.totalMemory() - rt.freeMemory();
            memoryGb = usedBytes / (1024.0 * 1024.0 * 1024.0);

            long now = System.currentTimeMillis();
            double elapsedSec = Math.max(0.001, (now - lastSampleMillis) / 1000.0);

            long read = bytesReadSupplier.getAsLong();
            long written = bytesWrittenSupplier.getAsLong();
            diskReadMbSec = toMbPerSec(read - lastBytesRead, elapsedSec);
            diskWriteMbSec = toMbPerSec(written - lastBytesWritten, elapsedSec);
            peakDiskReadMbSec = Math.max(peakDiskReadMbSec, diskReadMbSec);
            peakDiskWriteMbSec = Math.max(peakDiskWriteMbSec, diskWriteMbSec);

            lastBytesRead = read;
            lastBytesWritten = written;
            lastSampleMillis = now;

            activeThreads = activeWorkerSupplier.getAsInt();
        } catch (Exception e) {
            log.debug("Resource sample error: {}", e.getMessage());
        }
    }

    private static double toMbPerSec(long deltaBytes, double elapsedSec) {
        if (deltaBytes <= 0) return 0.0;
        return (deltaBytes / 1024.0 / 1024.0) / elapsedSec;
    }

    public double getCpuPercent() { return cpuPercent; }
    public double getMemoryGb() { return memoryGb; }
    public double getDiskReadMbSec() { return diskReadMbSec; }
    public double getDiskWriteMbSec() { return diskWriteMbSec; }
    public double getPeakDiskReadMbSec() { return peakDiskReadMbSec; }
    public double getPeakDiskWriteMbSec() { return peakDiskWriteMbSec; }

    /** Scan workers currently executing a file, not the JVM-wide thread count. */
    public int getActiveThreads() { return activeThreads; }
}
