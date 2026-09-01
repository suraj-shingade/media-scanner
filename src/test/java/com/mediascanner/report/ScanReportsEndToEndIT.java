package com.mediascanner.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediascanner.config.AppConfig;
import com.mediascanner.db.Database;
import com.mediascanner.engine.ScanEngine;
import com.mediascanner.model.Job;
import com.mediascanner.monitor.ProgressTracker;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * The independent test for US1 and US2, run against the real engine end to end.
 *
 * <p>Builds the exact source set the spec describes, runs a Copy job through {@link ScanEngine},
 * and asserts the three reports land in the archive with the right entries — the behaviour a user
 * actually gets, rather than the pieces in isolation.
 */
class ScanReportsEndToEndIT {

    @TempDir Path root;

    private Path sourceDir;
    private Path targetDir;
    private Database db;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        sourceDir = Files.createDirectories(root.resolve("source"));
        targetDir = Files.createDirectories(root.resolve("archive"));
        db = new Database(root.resolve("db").resolve("test.db"));
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
    }

    /** A genuine JPEG of random noise, large enough to clear the 10 KB small-file threshold. */
    private byte[] realJpeg(long seed) throws Exception {
        BufferedImage img = new BufferedImage(320, 320, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(seed);
        for (int x = 0; x < img.getWidth(); x++) {
            for (int y = 0; y < img.getHeight(); y++) {
                img.setRGB(x, y, rnd.nextInt(0xFFFFFF));
            }
        }
        var out = new java.io.ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private JsonNode readReport(String bucket, String name) throws Exception {
        Path p = targetDir.resolve(bucket).resolve(name);
        assertThat(p).as(bucket + "/" + name + " exists").exists();
        return mapper.readTree(Files.readAllBytes(p));
    }

    private static List<String> reasonsOf(JsonNode report) {
        List<String> reasons = new ArrayList<>();
        report.get("entries").forEach(e -> reasons.add(e.get("reason").asText()));
        return reasons;
    }

    @Test
    void testReportsCoverSkippedFailedAndDuplicateFiles() throws Exception {
        // 10 unique valid photos
        for (int i = 0; i < 10; i++) {
            Files.write(sourceDir.resolve("photo" + i + ".jpg"), realJpeg(i));
        }
        // 3 byte-identical copies of one photo, in different folders under different names
        byte[] triplicate = realJpeg(999);
        Files.createDirectories(sourceDir.resolve("nested/deeper"));
        Files.write(sourceDir.resolve("dupA.jpg"), triplicate);
        Files.write(sourceDir.resolve("nested/dupB.jpg"), triplicate);
        Files.write(sourceDir.resolve("nested/deeper/dupC.jpg"), triplicate);
        // one 0-byte image
        Files.write(sourceDir.resolve("empty.jpg"), new byte[0]);
        // one image below the 10 KB threshold
        Files.write(sourceDir.resolve("tiny.jpg"), new byte[5 * 1024]);
        // A text file wearing a video extension. It must clear the 100 KB video size threshold,
        // because the small-file gate runs before the corrupt-media gate — a short bogus file is
        // reported as SMALL_FILE, not as a failure.
        Files.writeString(sourceDir.resolve("broken.mp4"), "not a video. ".repeat(12_000));
        // a system file matched by the default ignore rules
        Files.writeString(sourceDir.resolve("Thumbs.db"), "system");

        AppConfig config = new AppConfig();
        Job job = Job.create(sourceDir.toString(), targetDir.toString(),
            Job.TransferMode.COPY, Job.FolderPattern.YYYY_MMM, Job.DuplicatePolicy.SKIP,
            10, 100, 4, false, config.getIgnoreRules());

        new ScanEngine(config, db, new ProgressTracker()).start(job);

        // --- skipped report: empty file, small file, ignore rule ---
        JsonNode skipped = readReport("_skipped", "skipped-report.json");
        assertThat(reasonsOf(skipped))
            .contains("EMPTY_FILE", "SMALL_FILE", "IGNORE_RULE_MATCHED");

        // --- failure report: the text file pretending to be an mp4 ---
        JsonNode failures = readReport("_failures", "failure-report.json");
        assertThat(failures.get("entries")).hasSize(1);
        assertThat(failures.get("entries").get(0).get("fileName").asText()).isEqualTo("broken.mp4");
        assertThat(failures.get("entries").get(0).get("reason").asText()).isNotBlank();

        // --- duplicate report: two of the three identical copies ---
        JsonNode duplicates = readReport("_duplicates", "duplicate-report.json");
        assertThat(duplicates.get("totalCount").asLong()).isEqualTo(2);
        assertThat(duplicates.get("totalBytesSaved").asLong())
            .isEqualTo(2L * triplicate.length);

        JsonNode dupEntry = duplicates.get("entries").get(0);
        assertThat(dupEntry.get("sha256Hash").asText()).isNotBlank();
        assertThat(dupEntry.get("matchedPath").asText()).isNotBlank();
        // All duplicates share one hash and point at the same retained original.
        String hash = dupEntry.get("sha256Hash").asText();
        String matched = dupEntry.get("matchedPath").asText();
        duplicates.get("entries").forEach(e -> {
            assertThat(e.get("sha256Hash").asText()).isEqualTo(hash);
            assertThat(e.get("matchedPath").asText()).isEqualTo(matched);
        });

        // --- the archive itself: 10 unique + 1 canonical of the triplicate ---
        long transferred;
        try (var walk = Files.walk(targetDir)) {
            transferred = walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".jpg"))
                .filter(p -> !p.toString().contains("_skipped")
                          && !p.toString().contains("_failures")
                          && !p.toString().contains("_duplicates"))
                .count();
        }
        assertThat(transferred).as("10 unique photos plus one canonical copy").isEqualTo(11);

        // Copy mode leaves every original in place.
        assertThat(sourceDir.resolve("dupA.jpg")).exists();
        assertThat(sourceDir.resolve("photo0.jpg")).exists();
    }

    /** US1 AS-3 / US2 AS-5: a clean job leaves no empty buckets behind. */
    @Test
    void testCleanJobWritesNoBuckets() throws Exception {
        for (int i = 0; i < 3; i++) {
            Files.write(sourceDir.resolve("clean" + i + ".jpg"), realJpeg(100 + i));
        }

        AppConfig config = new AppConfig();
        Job job = Job.create(sourceDir.toString(), targetDir.toString(),
            Job.TransferMode.COPY, Job.FolderPattern.YYYY_MMM, Job.DuplicatePolicy.SKIP,
            10, 100, 4, false, config.getIgnoreRules());

        new ScanEngine(config, db, new ProgressTracker()).start(job);

        assertThat(targetDir.resolve("_skipped")).doesNotExist();
        assertThat(targetDir.resolve("_failures")).doesNotExist();
        assertThat(targetDir.resolve("_duplicates")).doesNotExist();
    }

    /** FR-026 / FR-029: the dashboard needs a real denominator, which was always 0 before. */
    @Test
    void testTotalFileCountIsPublishedForEta() throws Exception {
        for (int i = 0; i < 6; i++) {
            Files.write(sourceDir.resolve("p" + i + ".jpg"), realJpeg(200 + i));
        }

        AppConfig config = new AppConfig();
        ProgressTracker tracker = new ProgressTracker();
        Job job = Job.create(sourceDir.toString(), targetDir.toString(),
            Job.TransferMode.COPY, Job.FolderPattern.YYYY_MMM, Job.DuplicatePolicy.SKIP,
            10, 100, 4, false, config.getIgnoreRules());

        new ScanEngine(config, db, tracker).start(job);

        assertThat(tracker.snapshot().filesTotal)
            .as("total files found is published for percent-complete and ETA")
            .isEqualTo(6);
    }
}
