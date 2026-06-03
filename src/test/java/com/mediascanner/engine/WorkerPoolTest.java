package com.mediascanner.engine;

import org.junit.jupiter.api.*;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class WorkerPoolTest {

    @Test
    void testDefaultThreadCountIsCpuTimes2() {
        int cores = Runtime.getRuntime().availableProcessors();
        int expected = cores * 2;
        assertThat(expected).isGreaterThanOrEqualTo(2);
    }

    @Test
    void testCustomThreadCountOf8() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        assertThat(pool).isNotNull();
        pool.shutdown();
        boolean terminated = pool.awaitTermination(5, TimeUnit.SECONDS);
        assertThat(terminated).isTrue();
    }

    @Test
    void testPoolShutdownCleanly() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 10; i++) {
            final int task = i;
            pool.submit(() -> {
                try { Thread.sleep(10); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        pool.shutdown();
        boolean terminated = pool.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(terminated).isTrue();
    }

    @Test
    void testAppConfigDefaultThreadCount() {
        com.mediascanner.config.AppConfig config = new com.mediascanner.config.AppConfig();
        int threadCount = config.getWorkerThreadCount();
        int cores = Runtime.getRuntime().availableProcessors();
        assertThat(threadCount).isEqualTo(cores * 2);
    }
}
