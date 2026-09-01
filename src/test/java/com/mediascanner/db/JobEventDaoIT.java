package com.mediascanner.db;

import com.mediascanner.model.JobEvent;
import com.mediascanner.model.MediaFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class JobEventDaoIT {

    @TempDir
    Path tempDir;

    private Database db;
    private JobEventDao dao;

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(tempDir.resolve("events.db"));
        dao = new JobEventDao(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
    }

    private static MediaFile file(String path, String name, long size) {
        MediaFile mf = new MediaFile();
        mf.setAbsolutePath(path);
        mf.setFileName(name);
        mf.setSizeBytes(size);
        return mf;
    }

    @Test
    void testInsertBatchAndCount() throws Exception {
        List<JobEvent> batch = List.of(
            JobEvent.skipped("job-1", file("C:\\a\\1.jpg", "1.jpg", 0), MediaFile.SkipReason.EMPTY_FILE),
            JobEvent.skipped("job-1", file("C:\\a\\2.jpg", "2.jpg", 4096), MediaFile.SkipReason.SMALL_FILE),
            JobEvent.failed("job-1", file("C:\\a\\3.mp4", "3.mp4", 900), "Unreadable media"));

        dao.insertBatch(batch);

        assertThat(dao.countByOutcome("job-1", JobEvent.Outcome.SKIPPED)).isEqualTo(2);
        assertThat(dao.countByOutcome("job-1", JobEvent.Outcome.FAILED)).isEqualTo(1);
        assertThat(dao.countByOutcome("job-1", JobEvent.Outcome.DUPLICATE)).isZero();
    }

    @Test
    void testEmptyBatchIsNoOp() throws Exception {
        dao.insertBatch(List.of());
        dao.insertBatch(null);
        assertThat(dao.countByOutcome("job-1", JobEvent.Outcome.SKIPPED)).isZero();
    }

    @Test
    void testStreamByOutcomePreservesFieldsAndOrder() throws Exception {
        dao.insertBatch(List.of(
            JobEvent.skipped("job-1", file("/src/a.jpg", "a.jpg", 10), MediaFile.SkipReason.EMPTY_FILE),
            JobEvent.skipped("job-1", file("/src/b.jpg", "b.jpg", 20), MediaFile.SkipReason.SMALL_FILE)));

        List<JobEvent> seen = new ArrayList<>();
        long delivered = dao.streamByOutcome("job-1", JobEvent.Outcome.SKIPPED,
            Integer.MAX_VALUE, seen::add);

        assertThat(delivered).isEqualTo(2);
        assertThat(seen).extracting(JobEvent::getFileName).containsExactly("a.jpg", "b.jpg");
        assertThat(seen.get(0).getReason()).isEqualTo("EMPTY_FILE");
        assertThat(seen.get(0).getFileSize()).isEqualTo(10);
        assertThat(seen.get(0).getRecordedAt()).isNotNull();
    }

    @Test
    void testStreamRespectsLimit() throws Exception {
        List<JobEvent> batch = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            batch.add(JobEvent.skipped("job-1", file("/src/f" + i, "f" + i, 1),
                MediaFile.SkipReason.SMALL_FILE));
        }
        dao.insertBatch(batch);

        List<JobEvent> seen = new ArrayList<>();
        long delivered = dao.streamByOutcome("job-1", JobEvent.Outcome.SKIPPED, 10, seen::add);

        assertThat(delivered).isEqualTo(10);
        assertThat(seen).hasSize(10);
        assertThat(dao.countByOutcome("job-1", JobEvent.Outcome.SKIPPED)).isEqualTo(50);
    }

    @Test
    void testDuplicateFieldsRoundTrip() throws Exception {
        dao.insertBatch(List.of(JobEvent.duplicate("job-1",
            file("/src/copy.jpg", "copy.jpg", 2048), "abc123", "/src/original.jpg", "/dst/_duplicates/copy.jpg")));

        List<JobEvent> seen = new ArrayList<>();
        dao.streamByOutcome("job-1", JobEvent.Outcome.DUPLICATE, Integer.MAX_VALUE, seen::add);

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).getSha256Hash()).isEqualTo("abc123");
        assertThat(seen.get(0).getMatchedPath()).isEqualTo("/src/original.jpg");
        assertThat(seen.get(0).getDestinationPath()).isEqualTo("/dst/_duplicates/copy.jpg");
    }

    @Test
    void testSumBytesByOutcome() throws Exception {
        dao.insertBatch(List.of(
            JobEvent.duplicate("job-1", file("/a", "a", 1000), "h", "/orig", null),
            JobEvent.duplicate("job-1", file("/b", "b", 2500), "h", "/orig", null)));

        assertThat(dao.sumBytesByOutcome("job-1", JobEvent.Outcome.DUPLICATE)).isEqualTo(3500);
        assertThat(dao.sumBytesByOutcome("job-1", JobEvent.Outcome.SKIPPED)).isZero();
    }

    @Test
    void testEventsAreScopedByJob() throws Exception {
        dao.insertBatch(List.of(
            JobEvent.skipped("job-1", file("/a", "a", 1), MediaFile.SkipReason.EMPTY_FILE),
            JobEvent.skipped("job-2", file("/b", "b", 1), MediaFile.SkipReason.EMPTY_FILE)));

        assertThat(dao.countByOutcome("job-1", JobEvent.Outcome.SKIPPED)).isEqualTo(1);
        assertThat(dao.countByOutcome("job-2", JobEvent.Outcome.SKIPPED)).isEqualTo(1);
    }

    @Test
    void testDeleteByJobIdRemovesOnlyThatJob() throws Exception {
        dao.insertBatch(List.of(
            JobEvent.skipped("job-1", file("/a", "a", 1), MediaFile.SkipReason.EMPTY_FILE),
            JobEvent.failed("job-1", file("/b", "b", 1), "bad"),
            JobEvent.skipped("job-2", file("/c", "c", 1), MediaFile.SkipReason.EMPTY_FILE)));

        int removed = dao.deleteByJobId("job-1");

        assertThat(removed).isEqualTo(2);
        assertThat(dao.countByOutcome("job-1", JobEvent.Outcome.SKIPPED)).isZero();
        assertThat(dao.countByOutcome("job-1", JobEvent.Outcome.FAILED)).isZero();
        assertThat(dao.countByOutcome("job-2", JobEvent.Outcome.SKIPPED)).isEqualTo(1);
    }
}
