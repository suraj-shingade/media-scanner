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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ReportWriterTest {

    @TempDir
    Path tempDir;

    private Database db;
    private JobEventDao dao;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(tempDir.resolve("report.db"));
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

    private JsonNode writeAndRead(ReportWriter writer, JobEvent.Outcome outcome) throws Exception {
        Path out = tempDir.resolve("out").resolve("report.json");
        writer.write(out, "job-1", outcome, "C:\\Source", "C:\\Target");
        return mapper.readTree(Files.readAllBytes(out));
    }

    @Test
    void testEnvelopeFields() throws Exception {
        dao.insertBatch(List.of(
            JobEvent.skipped("job-1", file("/a.jpg", "a.jpg", 100), MediaFile.SkipReason.EMPTY_FILE)));

        JsonNode root = writeAndRead(new ReportWriter(dao), JobEvent.Outcome.SKIPPED);

        assertThat(root.get("jobId").asText()).isEqualTo("job-1");
        assertThat(root.get("outcome").asText()).isEqualTo("SKIPPED");
        assertThat(root.get("sourcePath").asText()).isEqualTo("C:\\Source");
        assertThat(root.get("targetPath").asText()).isEqualTo("C:\\Target");
        assertThat(root.get("totalCount").asLong()).isEqualTo(1);
        assertThat(root.get("truncated").asBoolean()).isFalse();
        assertThat(root.get("generatedAt").asText()).isNotBlank();
        assertThat(root.get("entries")).hasSize(1);
    }

    @Test
    void testEntryShape() throws Exception {
        dao.insertBatch(List.of(
            JobEvent.skipped("job-1", file("/photos/a.jpg", "a.jpg", 4096),
                MediaFile.SkipReason.SMALL_FILE)));

        JsonNode entry = writeAndRead(new ReportWriter(dao), JobEvent.Outcome.SKIPPED)
            .get("entries").get(0);

        assertThat(entry.get("filePath").asText()).isEqualTo("/photos/a.jpg");
        assertThat(entry.get("fileName").asText()).isEqualTo("a.jpg");
        assertThat(entry.get("fileSize").asLong()).isEqualTo(4096);
        assertThat(entry.get("reason").asText()).isEqualTo("SMALL_FILE");
        assertThat(entry.get("recordedAt").asText()).isNotBlank();
    }

    @Test
    void testDuplicateReportCarriesHashAndBytesSaved() throws Exception {
        dao.insertBatch(List.of(
            JobEvent.duplicate("job-1", file("/copy1.jpg", "copy1.jpg", 1000),
                "hash-abc", "/original.jpg", null),
            JobEvent.duplicate("job-1", file("/copy2.jpg", "copy2.jpg", 1500),
                "hash-abc", "/original.jpg", null)));

        JsonNode root = writeAndRead(new ReportWriter(dao), JobEvent.Outcome.DUPLICATE);

        assertThat(root.get("totalBytesSaved").asLong()).isEqualTo(2500);
        JsonNode entry = root.get("entries").get(0);
        assertThat(entry.get("sha256Hash").asText()).isEqualTo("hash-abc");
        assertThat(entry.get("matchedPath").asText()).isEqualTo("/original.jpg");
    }

    /** FR-005-013: Windows separators, non-ASCII names, quotes and newlines must survive. */
    @Test
    void testAwkwardPathsAreEscaped() throws Exception {
        String nasty = "C:\\Photos\\2024\\he said \"hi\"\\naïve_café_日本.jpg";
        dao.insertBatch(List.of(
            JobEvent.skipped("job-1", file(nasty, "naïve_café_日本.jpg", 1),
                MediaFile.SkipReason.SMALL_FILE)));

        JsonNode entry = writeAndRead(new ReportWriter(dao), JobEvent.Outcome.SKIPPED)
            .get("entries").get(0);

        assertThat(entry.get("filePath").asText()).isEqualTo(nasty);
        assertThat(entry.get("fileName").asText()).isEqualTo("naïve_café_日本.jpg");
    }

    @Test
    void testEmbeddedNewlineInFailureReasonSurvives() throws Exception {
        String reason = "Tika failed:\nline two\tand a tab";
        dao.insertBatch(List.of(JobEvent.failed("job-1", file("/a", "a", 1), reason)));

        JsonNode entry = writeAndRead(new ReportWriter(dao), JobEvent.Outcome.FAILED)
            .get("entries").get(0);

        assertThat(entry.get("reason").asText()).isEqualTo(reason);
    }

    /** Research D7: the cap must be visible in the file, never silent. */
    @Test
    void testTruncationIsAnnouncedWithTrueTotal() throws Exception {
        List<JobEvent> batch = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            batch.add(JobEvent.skipped("job-1", file("/f" + i, "f" + i, 1),
                MediaFile.SkipReason.SMALL_FILE));
        }
        dao.insertBatch(batch);

        JsonNode root = writeAndRead(new ReportWriter(dao, 10), JobEvent.Outcome.SKIPPED);

        assertThat(root.get("truncated").asBoolean()).isTrue();
        assertThat(root.get("totalCount").asLong()).isEqualTo(25);
        assertThat(root.get("entriesOmitted").asLong()).isEqualTo(15);
        assertThat(root.get("truncationNotice").asText()).contains("10");
        assertThat(root.get("entries")).hasSize(10);
    }

    @Test
    void testReportWithNoEntriesIsStillValidJson() throws Exception {
        JsonNode root = writeAndRead(new ReportWriter(dao), JobEvent.Outcome.FAILED);

        assertThat(root.get("totalCount").asLong()).isZero();
        assertThat(root.get("entries")).isEmpty();
    }

    @Test
    void testWriteCreatesMissingParentDirectories() throws Exception {
        dao.insertBatch(List.of(
            JobEvent.skipped("job-1", file("/a", "a", 1), MediaFile.SkipReason.EMPTY_FILE)));

        Path deep = tempDir.resolve("does").resolve("not").resolve("exist").resolve("r.json");
        new ReportWriter(dao).write(deep, "job-1", JobEvent.Outcome.SKIPPED, "s", "t");

        assertThat(Files.exists(deep)).isTrue();
    }
}
