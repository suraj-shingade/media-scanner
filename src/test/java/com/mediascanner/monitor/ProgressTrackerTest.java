package com.mediascanner.monitor;

import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class ProgressTrackerTest {

    @Test
    void testCountersThreadSafe() throws Exception {
        ProgressTracker tracker = new ProgressTracker();
        int threads = 10;
        int increments = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                for (int j = 0; j < increments; j++) {
                    tracker.incrementProcessed(1024);
                }
            }));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertThat(tracker.getFilesProcessed().get()).isEqualTo((long) threads * increments);
    }

    @Test
    void testFailedAndSkippedCounters() {
        ProgressTracker tracker = new ProgressTracker();
        tracker.incrementFailed();
        tracker.incrementFailed();
        tracker.incrementSkipped();
        assertThat(tracker.getFilesFailed().get()).isEqualTo(2);
        assertThat(tracker.getFilesSkipped().get()).isEqualTo(1);
    }

    @Test
    void testSnapshotReflectsCurrentState() {
        ProgressTracker tracker = new ProgressTracker();
        tracker.incrementProcessed(1_000_000);
        tracker.incrementProcessed(2_000_000);
        tracker.incrementFailed();

        ProgressTracker.Snapshot snap = tracker.snapshot();
        assertThat(snap.filesProcessed).isEqualTo(2);
        assertThat(snap.filesFailed).isEqualTo(1);
        assertThat(snap.bytesProcessed).isEqualTo(3_000_000);
    }

    @Test
    void testDuplicateCounter() {
        ProgressTracker tracker = new ProgressTracker();
        tracker.incrementDuplicate();
        tracker.incrementDuplicate();
        assertThat(tracker.getFilesDuplicate().get()).isEqualTo(2);
    }
}
