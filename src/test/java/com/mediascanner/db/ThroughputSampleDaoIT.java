package com.mediascanner.db;

import com.mediascanner.model.ThroughputSample;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ThroughputSampleDaoIT {

    @TempDir
    Path tempDir;

    private Database db;
    private ThroughputSampleDao dao;

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(tempDir.resolve("throughput.db"));
        dao = new ThroughputSampleDao(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
    }

    private void seed(String jobId, int seconds) throws Exception {
        List<ThroughputSample> batch = new ArrayList<>();
        Instant base = Instant.parse("2026-09-01T10:00:00Z");
        for (int i = 0; i < seconds; i++) {
            batch.add(new ThroughputSample(jobId, base.plusSeconds(i), i,
                100 + (i % 50), 40 + (i % 20), 50 + (i % 40), 2.0));
            if (batch.size() == 1000) {
                dao.insertBatch(batch);
                batch.clear();
            }
        }
        dao.insertBatch(batch);
    }

    @Test
    void testRoundTrip() throws Exception {
        seed("job-1", 10);
        assertThat(dao.countByJobId("job-1")).isEqualTo(10);

        List<ThroughputSample> samples = dao.findDownsampled("job-1", 600);
        assertThat(samples).hasSize(10);
        assertThat(samples.get(0).getElapsedSeconds()).isZero();
        assertThat(samples.get(9).getElapsedSeconds()).isEqualTo(9);
        assertThat(samples.get(0).getSampleAt()).isNotNull();
    }

    /** US5 AS-4: an 8-hour job must render fast, so it downsamples rather than returning 28 800 rows. */
    @Test
    void testEightHourJobDownsamplesAndIsFast() throws Exception {
        int eightHours = 8 * 60 * 60;
        seed("long-job", eightHours);

        long start = System.nanoTime();
        List<ThroughputSample> samples = dao.findDownsampled("long-job", 600);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(dao.countByJobId("long-job")).isEqualTo(eightHours);
        assertThat(samples.size())
            .as("downsampled to roughly the target point count, not %d raw rows", eightHours)
            .isLessThanOrEqualTo(601)
            .isGreaterThan(500);
        assertThat(elapsedMs).as("downsampled read in under 2 s (took %d ms)", elapsedMs)
            .isLessThan(2_000);

        // Ordering must survive the bucketing, or the chart draws backwards.
        for (int i = 1; i < samples.size(); i++) {
            assertThat(samples.get(i).getElapsedSeconds())
                .isGreaterThan(samples.get(i - 1).getElapsedSeconds());
        }
    }

    @Test
    void testDownsamplingAveragesRatherThanDropping() throws Exception {
        List<ThroughputSample> batch = new ArrayList<>();
        Instant base = Instant.now();
        // Two buckets of four samples: first averages 10, second averages 20.
        for (int i = 0; i < 4; i++) {
            batch.add(new ThroughputSample("avg-job", base, i, 10, 10, 0, 0));
        }
        for (int i = 4; i < 8; i++) {
            batch.add(new ThroughputSample("avg-job", base, i, 20, 20, 0, 0));
        }
        dao.insertBatch(batch);

        List<ThroughputSample> samples = dao.findDownsampled("avg-job", 2);

        assertThat(samples).hasSize(2);
        assertThat(samples.get(0).getFilesPerSec()).isEqualTo(10.0);
        assertThat(samples.get(1).getFilesPerSec()).isEqualTo(20.0);
    }

    @Test
    void testEmptyJobReturnsEmptyListNotError() throws Exception {
        assertThat(dao.findDownsampled("no-such-job", 600)).isEmpty();
        assertThat(dao.countByJobId("no-such-job")).isZero();
    }

    @Test
    void testTargetPointsOfZeroIsClampedNotDivideByZero() throws Exception {
        seed("job-1", 20);
        assertThat(dao.findDownsampled("job-1", 0)).isNotEmpty();
    }

    @Test
    void testDeleteByJobIdIsScoped() throws Exception {
        seed("job-1", 5);
        seed("job-2", 5);

        assertThat(dao.deleteByJobId("job-1")).isEqualTo(5);
        assertThat(dao.countByJobId("job-1")).isZero();
        assertThat(dao.countByJobId("job-2")).isEqualTo(5);
    }
}
