package com.mediascanner.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediascanner.config.AppConfig;
import com.mediascanner.db.Database;
import com.mediascanner.model.Job;
import com.mediascanner.monitor.ProgressTracker;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Running the same job twice against the same target, and resuming an interrupted job.
 *
 * <p>Both were broken: the second run reported every file as a duplicate of itself, and a resumed
 * job re-copied everything it had already transferred under {@code IMG(1).jpg} collision names.
 * Feature 007 fixes both; these are the tests that pin the behaviour down.
 */
class RerunAndResumeIT {

    @TempDir Path root;

    private Path sourceDir;
    private Path targetDir;
    private Path dbDir;
    private Database db;
    private AppConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        sourceDir = Files.createDirectories(root.resolve("source"));
        targetDir = Files.createDirectories(root.resolve("archive"));
        dbDir = createDbDir();
        db = new Database(dbDir.resolve("test.db"));
        config = new AppConfig();
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
        deleteQuietly(dbDir);
    }

    /**
     * The SQLite database deliberately lives outside the {@code @TempDir}. On Windows the WAL and
     * SHM files can linger for a moment after close, and JUnit deletes a TempDir immediately after
     * the teardown method — which made this class fail intermittently under full-suite load while
     * passing in isolation. Cleaning it up ourselves, tolerantly, removes the race.
     */
    private static Path createDbDir() throws Exception {
        return Files.createTempDirectory("ms-test-db");
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }


    private byte[] jpeg(long seed) throws Exception {
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

    private Job newJob(Job.TransferMode mode) {
        return Job.create(sourceDir.toString(), targetDir.toString(),
            mode, Job.FolderPattern.YYYY_MMM, Job.DuplicatePolicy.SKIP,
            10, 100, 4, false, config.getIgnoreRules());
    }

    private long archivedFiles() throws Exception {
        try (Stream<Path> walk = Files.walk(targetDir)) {
            return walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".jpg"))
                .filter(p -> !p.toString().contains("_skipped")
                          && !p.toString().contains("_failures")
                          && !p.toString().contains("_duplicates"))
                .count();
        }
    }

    private List<String> archivedNames() throws Exception {
        try (Stream<Path> walk = Files.walk(targetDir)) {
            return walk.filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .filter(n -> n.endsWith(".jpg"))
                .sorted()
                .toList();
        }
    }

    /**
     * Re-running an identical job must be a no-op, not a flood of self-duplicates. The archive must
     * not grow, and no file may be reported as a duplicate of itself.
     */
    @Test
    void testSecondRunOverSameSourceIsIdempotent() throws Exception {
        for (int i = 0; i < 8; i++) {
            Files.write(sourceDir.resolve("photo" + i + ".jpg"), jpeg(i));
        }

        new ScanEngine(config, db, new ProgressTracker()).start(newJob(Job.TransferMode.COPY));
        long afterFirst = archivedFiles();
        assertThat(afterFirst).isEqualTo(8);

        new ScanEngine(config, db, new ProgressTracker()).start(newJob(Job.TransferMode.COPY));

        assertThat(archivedFiles())
            .as("a second identical run must not add files to the archive")
            .isEqualTo(afterFirst);
        assertThat(archivedNames())
            .as("no collision-suffixed copies of already-archived files")
            .noneMatch(n -> n.contains("(1)"));

        Path dupReport = targetDir.resolve("_duplicates/duplicate-report.json");
        if (Files.exists(dupReport)) {
            JsonNode report = mapper.readTree(Files.readAllBytes(dupReport));
            for (JsonNode e : report.get("entries")) {
                assertThat(e.get("filePath").asText())
                    .as("a file must never be reported as a duplicate of itself")
                    .isNotEqualTo(e.get("matchedPath").asText());
            }
        }
    }

    /**
     * The resume case: a prior run transferred some files and died. Re-running must complete the
     * remainder without re-copying what is already in the archive.
     */
    @Test
    void testResumeDoesNotDuplicateAlreadyTransferredFiles() throws Exception {
        for (int i = 0; i < 10; i++) {
            Files.write(sourceDir.resolve("img" + i + ".jpg"), jpeg(100 + i));
        }

        // Simulate an interrupted run: process only the first half.
        Path half = Files.createDirectories(root.resolve("half"));
        for (int i = 0; i < 5; i++) {
            Files.copy(sourceDir.resolve("img" + i + ".jpg"), half.resolve("img" + i + ".jpg"));
        }
        Job firstLeg = Job.create(half.toString(), targetDir.toString(),
            Job.TransferMode.COPY, Job.FolderPattern.YYYY_MMM, Job.DuplicatePolicy.SKIP,
            10, 100, 4, false, config.getIgnoreRules());
        new ScanEngine(config, db, new ProgressTracker()).start(firstLeg);
        assertThat(archivedFiles()).isEqualTo(5);

        // Now the "resume": the full source, same target.
        new ScanEngine(config, db, new ProgressTracker()).start(newJob(Job.TransferMode.COPY));

        assertThat(archivedFiles())
            .as("resume completes the remaining 5 without re-copying the first 5")
            .isEqualTo(10);
        assertThat(archivedNames())
            .as("no IMG(1).jpg duplication of already-transferred files")
            .noneMatch(n -> n.contains("(1)"));
    }

    /** Genuine filename collisions between different content must still be renamed (FR-009). */
    @Test
    void testGenuineCollisionBetweenDifferentContentStillRenames() throws Exception {
        Files.write(sourceDir.resolve("a.jpg"), jpeg(1));
        new ScanEngine(config, db, new ProgressTracker()).start(newJob(Job.TransferMode.COPY));
        assertThat(archivedFiles()).isEqualTo(1);

        // A different file that will land on the same destination name.
        Path nested = Files.createDirectories(sourceDir.resolve("other"));
        Files.write(nested.resolve("a.jpg"), jpeg(2));
        // Keep mtimes aligned so both resolve to the same date folder.
        Files.setLastModifiedTime(nested.resolve("a.jpg"),
            Files.getLastModifiedTime(sourceDir.resolve("a.jpg")));

        new ScanEngine(config, db, new ProgressTracker()).start(newJob(Job.TransferMode.COPY));

        assertThat(archivedNames())
            .as("different content sharing a name must still get a collision suffix")
            .anyMatch(n -> n.contains("(1)"));
        assertThat(archivedFiles()).isEqualTo(2);
    }

    /** Move mode resumes naturally: what is left in the source is exactly what is left to do. */
    @Test
    void testMoveModeResumeLeavesNoDuplicates() throws Exception {
        for (int i = 0; i < 6; i++) {
            Files.write(sourceDir.resolve("m" + i + ".jpg"), jpeg(200 + i));
        }

        new ScanEngine(config, db, new ProgressTracker()).start(newJob(Job.TransferMode.MOVE));
        assertThat(archivedFiles()).isEqualTo(6);

        // Source is now empty; a second Move run must be a clean no-op.
        new ScanEngine(config, db, new ProgressTracker()).start(newJob(Job.TransferMode.MOVE));

        assertThat(archivedFiles()).isEqualTo(6);
        assertThat(archivedNames()).noneMatch(n -> n.contains("(1)"));
    }
}
