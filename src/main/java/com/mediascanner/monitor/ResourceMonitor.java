package com.mediascanner.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ResourceMonitor {

    private static final Logger log = LoggerFactory.getLogger(ResourceMonitor.class);

    private volatile double cpuPercent = 0.0;
    private volatile double memoryGb = 0.0;
    private volatile double diskReadMbSec = 0.0;
    private volatile double diskWriteMbSec = 0.0;
    private volatile int activeThreads = 0;

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

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "resource-monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::sample, 0, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    private void sample() {
        try {
            if (osMxBean != null) {
                double cpu = osMxBean.getSystemCpuLoad();
                if (cpu >= 0) cpuPercent = cpu * 100.0;
            }

            Runtime rt = Runtime.getRuntime();
            long usedBytes = rt.totalMemory() - rt.freeMemory();
            memoryGb = usedBytes / (1024.0 * 1024.0 * 1024.0);

            activeThreads = Thread.activeCount();
        } catch (Exception e) {
            log.debug("Resource sample error: {}", e.getMessage());
        }
    }

    public double getCpuPercent() { return cpuPercent; }
    public double getMemoryGb() { return memoryGb; }
    public double getDiskReadMbSec() { return diskReadMbSec; }
    public double getDiskWriteMbSec() { return diskWriteMbSec; }
    public int getActiveThreads() { return activeThreads; }
}
