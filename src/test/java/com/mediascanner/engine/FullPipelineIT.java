package com.mediascanner.engine;

import com.mediascanner.checkpoint.CheckpointManager;
import com.mediascanner.config.AppConfig;
import com.mediascanner.db.Database;
import com.mediascanner.db.HashIndexDao;
import com.mediascanner.db.JobStatisticsDao;
import com.mediascanner.model.*;
import com.mediascanner.monitor.ProgressTracker;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class FullPipelineIT {

    @TempDir Path sourceDir;
    @TempDir Path targetDir;

    Database db;
    Path dbPath;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = Files.createTempDirectory("pipeline-test-db").resolve("test.db");
        db = new Database(dbPath);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) db.close();
        Files.deleteIfExists(dbPath);
        Files.deleteIfExists(dbPath.getParent());
    }

    /**
     * Real JPEGs, not 20 KB of zero bytes. The original fixture wrote zeros and called them valid
     * images; the header-only gate accepted that, so this test asserted for four features that
     * undecodable content was valid media. FR-012 deep validation correctly rejects it.
     */
    private static byte[] realJpeg(long seed) throws Exception {
        java.awt.image.BufferedImage img =
            new java.awt.image.BufferedImage(240, 240, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.util.Random rnd = new java.util.Random(seed);
        for (int x = 0; x < 240; x++) {
            for (int y = 0; y < 240; y++) img.setRGB(x, y, rnd.nextInt(0xFFFFFF));
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    @Test
    void testPipelineWithMixedFiles() throws Exception {
        // Create valid images
        for (int i = 0; i < 5; i++) {
            Files.write(sourceDir.resolve("valid" + i + ".jpg"), realJpeg(i));
        }
        // Empty file
        Files.write(sourceDir.resolve("empty.jpg"), new byte[0]);
        // Small file
        Files.write(sourceDir.resolve("small.jpg"), new byte[1024]);
        // Non-media
        Files.write(sourceDir.resolve("doc.pdf"), "pdf content".getBytes());

        FileScanner scanner = new FileScanner(null);
        FileValidator validator = new FileValidator(10, 100);

        long validCount = 0;
        long skipCount = 0;

        try (java.util.stream.Stream<Path> stream = scanner.walkFileTree(sourceDir)) {
            for (Path p : stream.toList()) {
                MediaFile mf = new MediaFile();
                mf.setExtension(p.getFileName().toString().replaceAll(".*\\.", ""));
                validator.validate(mf, p);
                if (mf.getValidationStatus() == MediaFile.ValidationStatus.VALID) validCount++;
                else skipCount++;
            }
        }

        assertThat(validCount).isEqualTo(5);
        assertThat(skipCount).isEqualTo(2);
    }

    @Test
    void testHashIndexPopulated() throws Exception {
        HashIndexDao dao = new HashIndexDao(db);
        HashEngine engine = new HashEngine(dao);

        Path file = sourceDir.resolve("test.jpg");
        Files.write(file, realJpeg(42));

        MediaFile mf = new MediaFile();
        mf.setAbsolutePath(file.toString());
        mf.setFileName("test.jpg");
        mf.setSizeBytes(20 * 1024);
        mf.setModificationTimestamp(Files.getLastModifiedTime(file).toInstant());

        String hash = engine.computeHash(file, mf);
        assertThat(hash).hasSize(64);

        FileHashRecord record = dao.findByFilePath(file.toString());
        assertThat(record).isNotNull();
        assertThat(record.getSha256Hash()).isEqualTo(hash);
    }

    @Test
    void testJobStatisticsRowCreated() throws Exception {
        JobStatisticsDao dao = new JobStatisticsDao(db);
        JobStatistics stats = new JobStatistics("JOB-20240115-TEST", java.time.LocalDateTime.now());
        stats.setStatus("RUNNING");
        dao.insert(stats);

        stats.setFilesProcessed(100);
        stats.setStatus("COMPLETED");
        dao.updateCounters(stats);
        dao.markCompleted("JOB-20240115-TEST", java.time.LocalDateTime.now());

        JobStatistics loaded = dao.findById("JOB-20240115-TEST");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getFilesProcessed()).isEqualTo(100);
        assertThat(loaded.getStatus()).isEqualTo("COMPLETED");
    }
}
