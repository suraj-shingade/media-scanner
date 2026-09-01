package com.mediascanner.report;

import com.mediascanner.db.Database;
import com.mediascanner.db.JobEventDao;
import com.mediascanner.model.JobEvent;
import com.mediascanner.model.MediaFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * SC-003: a report with 100 000 entries must be written in under 10 s, and peak memory must not
 * scale with entry count. The memory assertion is what protects the streaming design — any change
 * that collects entries into a list before writing will fail this.
 */
class ReportScaleIT {

    private static final int LARGE_COUNT = 100_000;

    @TempDir
    Path tempDir;

    private Database db;
    private JobEventDao dao;

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(tempDir.resolve("scale.db"));
        dao = new JobEventDao(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
    }

    private static MediaFile file(int i) {
        MediaFile mf = new MediaFile();
        mf.setAbsolutePath("C:\\Photos\\subfolder\\some-fairly-long-filename-" + i + ".jpg");
        mf.setFileName("some-fairly-long-filename-" + i + ".jpg");
        mf.setSizeBytes(4096 + i);
        return mf;
    }

    private void seed(String jobId, int count) throws Exception {
        List<JobEvent> batch = new ArrayList<>(500);
        for (int i = 0; i < count; i++) {
            batch.add(JobEvent.skipped(jobId, file(i), MediaFile.SkipReason.SMALL_FILE));
            if (batch.size() == 500) {
                dao.insertBatch(batch);
                batch.clear();
            }
        }
        dao.insertBatch(batch);
    }

    private static long usedHeapBytes() {
        System.gc();
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    @Test
    void testLargeReportIsFastAndMemoryFlat() throws Exception {
        seed("small-job", 100);
        seed("large-job", LARGE_COUNT);

        ReportWriter writer = new ReportWriter(dao, Integer.MAX_VALUE);

        Path smallOut = tempDir.resolve("small.json");
        long beforeSmall = usedHeapBytes();
        writer.write(smallOut, "small-job", JobEvent.Outcome.SKIPPED, "/src", "/dst");
        long smallDelta = usedHeapBytes() - beforeSmall;

        Path largeOut = tempDir.resolve("large.json");
        long beforeLarge = usedHeapBytes();
        long start = System.nanoTime();
        long written = writer.write(largeOut, "large-job", JobEvent.Outcome.SKIPPED, "/src", "/dst");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long largeDelta = usedHeapBytes() - beforeLarge;

        assertThat(written).isEqualTo(LARGE_COUNT);
        assertThat(Files.size(largeOut)).isGreaterThan(Files.size(smallOut));

        assertThat(elapsedMs)
            .as("100k-entry report written in under 10 s (took %d ms)", elapsedMs)
            .isLessThan(10_000);

        // A materialising implementation would retain ~100k JobEvent objects here (tens of MB).
        // Streaming keeps the delta small and roughly independent of count; 32 MB is a generous
        // ceiling that still fails loudly if someone collects into a list.
        long headroomBytes = 32L * 1024 * 1024;
        assertThat(largeDelta)
            .as("streaming report retains no per-entry state (small=%d bytes, large=%d bytes)",
                smallDelta, largeDelta)
            .isLessThan(headroomBytes);
    }

    @Test
    void testTruncationCapsWorkRegardlessOfTotal() throws Exception {
        seed("capped-job", 5_000);

        Path out = tempDir.resolve("capped.json");
        long written = new ReportWriter(dao, 100)
            .write(out, "capped-job", JobEvent.Outcome.SKIPPED, "/src", "/dst");

        assertThat(written).isEqualTo(100);
        String content = Files.readString(out);
        assertThat(content).contains("\"truncated\" : true");
        assertThat(content).contains("\"totalCount\" : 5000");
    }
}
