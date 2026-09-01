package com.mediascanner.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediascanner.db.Database;
import com.mediascanner.db.JobEventDao;
import com.mediascanner.model.JobEvent;
import com.mediascanner.model.MediaFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class JobReportServiceIT {

    @TempDir
    Path tempDir;

    private Database db;
    private JobEventDao dao;
    private JobReportService service;
    private Path target;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(tempDir.resolve("svc.db"));
        dao = new JobEventDao(db);
        service = new JobReportService(db);
        target = Files.createDirectories(tempDir.resolve("archive"));
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
    void testAllThreeReportsAreWritten() throws Exception {
        dao.insertBatch(List.of(
            JobEvent.skipped("job-1", file("/a.jpg", "a.jpg", 0), MediaFile.SkipReason.EMPTY_FILE),
            JobEvent.failed("job-1", file("/b.mp4", "b.mp4", 900), "Unreadable media"),
            JobEvent.duplicate("job-1", file("/c.jpg", "c.jpg", 1000), "h1", "/orig.jpg", null)));

        Map<JobEvent.Outcome, Path> written =
            service.writeAll("job-1", target.toString(), "/source");

        assertThat(written).containsOnlyKeys(JobEvent.Outcome.SKIPPED,
            JobEvent.Outcome.FAILED, JobEvent.Outcome.DUPLICATE);
        assertThat(target.resolve("_skipped/skipped-report.json")).exists();
        assertThat(target.resolve("_failures/failure-report.json")).exists();
        assertThat(target.resolve("_duplicates/duplicate-report.json")).exists();
        assertThat(target.resolve("_skipped/skipped-report-job-1.json")).exists();
    }

    /** US1 AS-3 / US2 AS-5: no entries means no file and no empty bucket directory. */
    @Test
    void testNoReportOrBucketWhenOutcomeIsEmpty() throws Exception {
        dao.insertBatch(List.of(
            JobEvent.skipped("job-1", file("/a.jpg", "a.jpg", 0), MediaFile.SkipReason.EMPTY_FILE)));

        service.writeAll("job-1", target.toString(), "/source");

        assertThat(target.resolve("_skipped")).exists();
        assertThat(target.resolve("_failures")).doesNotExist();
        assertThat(target.resolve("_duplicates")).doesNotExist();
    }

    @Test
    void testCleanJobWritesNothingAtAll() throws Exception {
        Map<JobEvent.Outcome, Path> written =
            service.writeAll("job-clean", target.toString(), "/source");

        assertThat(written).isEmpty();
        try (var entries = Files.list(target)) {
            assertThat(entries).isEmpty();
        }
    }

    /** FR-005-006: a second job against the same archive must not destroy the first one's report. */
    @Test
    void testPerJobReportsDoNotOverwriteEachOther() throws Exception {
        dao.insertBatch(List.of(
            JobEvent.skipped("job-1", file("/one.jpg", "one.jpg", 1), MediaFile.SkipReason.EMPTY_FILE)));
        service.writeAll("job-1", target.toString(), "/source");

        dao.insertBatch(List.of(
            JobEvent.skipped("job-2", file("/two.jpg", "two.jpg", 1), MediaFile.SkipReason.SMALL_FILE),
            JobEvent.skipped("job-2", file("/three.jpg", "three.jpg", 1), MediaFile.SkipReason.SMALL_FILE)));
        service.writeAll("job-2", target.toString(), "/source");

        JsonNode first = mapper.readTree(
            Files.readAllBytes(target.resolve("_skipped/skipped-report-job-1.json")));
        JsonNode second = mapper.readTree(
            Files.readAllBytes(target.resolve("_skipped/skipped-report-job-2.json")));
        JsonNode latest = mapper.readTree(
            Files.readAllBytes(target.resolve("_skipped/skipped-report.json")));

        assertThat(first.get("totalCount").asLong()).isEqualTo(1);
        assertThat(second.get("totalCount").asLong()).isEqualTo(2);
        // The plain name tracks the most recent job.
        assertThat(latest.get("jobId").asText()).isEqualTo("job-2");
    }

    @Test
    void testReportsAreScopedToTheRequestedJob() throws Exception {
        dao.insertBatch(List.of(
            JobEvent.failed("job-1", file("/a", "a", 1), "reason a"),
            JobEvent.failed("job-2", file("/b", "b", 1), "reason b")));

        service.writeAll("job-1", target.toString(), "/source");

        JsonNode report = mapper.readTree(
            Files.readAllBytes(target.resolve("_failures/failure-report-job-1.json")));
        assertThat(report.get("entries")).hasSize(1);
        assertThat(report.get("entries").get(0).get("fileName").asText()).isEqualTo("a");
    }

    /** Research D8: reports are generatable long after the engine is gone. */
    @Test
    void testReportsCanBeGeneratedLaterFromStoredEventsAlone() throws Exception {
        dao.insertBatch(List.of(
            JobEvent.skipped("old-job", file("/x", "x", 5), MediaFile.SkipReason.METADATA_MISSING)));
        db.close();

        db = new Database(tempDir.resolve("svc.db"));
        JobReportService reopened = new JobReportService(db);
        reopened.writeAll("old-job", target.toString(), "/source");

        JsonNode report = mapper.readTree(
            Files.readAllBytes(target.resolve("_skipped/skipped-report-old-job.json")));
        assertThat(report.get("entries")).hasSize(1);
        assertThat(report.get("entries").get(0).get("reason").asText()).isEqualTo("METADATA_MISSING");
    }
}
