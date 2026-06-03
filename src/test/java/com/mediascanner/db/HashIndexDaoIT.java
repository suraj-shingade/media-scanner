package com.mediascanner.db;

import com.mediascanner.model.FileHashRecord;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HashIndexDaoIT {

    static Path testDbPath;
    static Database db;
    static HashIndexDao dao;

    @BeforeAll
    static void setUp() throws Exception {
        testDbPath = Files.createTempDirectory("mediascanner-hashtest").resolve("test.db");
        db = new Database(testDbPath);
        dao = new HashIndexDao(db);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (db != null) db.close();
        Files.deleteIfExists(testDbPath);
        Files.deleteIfExists(testDbPath.getParent());
    }

    private FileHashRecord buildRecord(String path, String hash, long size, Instant mtime) {
        FileHashRecord r = new FileHashRecord();
        r.setFilePath(path);
        r.setFileName(path.substring(path.lastIndexOf('/') + 1));
        r.setFileSizeBytes(size);
        r.setFileModificationTs(mtime);
        r.setSha256Hash(hash);
        r.setMediaDate(LocalDateTime.of(2024, 1, 15, 10, 0));
        r.setCreatedAt(Instant.now());
        r.setLastProcessedAt(Instant.now());
        return r;
    }

    @Test
    @Order(1)
    void testInsertAndFindBySha256() throws Exception {
        Instant mtime = Instant.parse("2024-01-15T10:00:00Z");
        FileHashRecord record = buildRecord("/photos/img001.jpg",
            "abc123def456abc123def456abc123def456abc123def456abc123def456abc1", 102400, mtime);
        dao.insert(record);

        FileHashRecord found = dao.findBySha256(
            "abc123def456abc123def456abc123def456abc123def456abc123def456abc1");
        assertThat(found).isNotNull();
        assertThat(found.getFilePath()).isEqualTo("/photos/img001.jpg");
        assertThat(found.getFileSizeBytes()).isEqualTo(102400);
    }

    @Test
    @Order(2)
    void testFindByFilePath() throws Exception {
        FileHashRecord found = dao.findByFilePath("/photos/img001.jpg");
        assertThat(found).isNotNull();
        assertThat(found.getSha256Hash()).isEqualTo(
            "abc123def456abc123def456abc123def456abc123def456abc123def456abc1");
    }

    @Test
    @Order(3)
    void testCacheInvalidationOnSizeChange() throws Exception {
        Instant mtime = Instant.parse("2024-01-15T10:00:00Z");
        FileHashRecord cached = dao.findAndValidateCache("/photos/img001.jpg", 99999, mtime);
        assertThat(cached).isNull();
    }

    @Test
    @Order(4)
    void testCacheValidWhenSizeAndMtimeMatch() throws Exception {
        Instant mtime = Instant.parse("2024-01-15T10:00:00Z");
        FileHashRecord cached = dao.findAndValidateCache("/photos/img001.jpg", 102400, mtime);
        assertThat(cached).isNotNull();
        assertThat(cached.getSha256Hash()).isEqualTo(
            "abc123def456abc123def456abc123def456abc123def456abc123def456abc1");
    }

    @Test
    @Order(5)
    void testUpdateRecord() throws Exception {
        Instant newMtime = Instant.parse("2024-06-01T12:00:00Z");
        FileHashRecord updated = buildRecord("/photos/img001.jpg",
            "newhashnewhashnewhashnewhashnewhashnewhashnewhashnewhashnewha1234", 204800, newMtime);
        dao.updateRecord(updated);

        FileHashRecord found = dao.findByFilePath("/photos/img001.jpg");
        assertThat(found.getFileSizeBytes()).isEqualTo(204800);
        assertThat(found.getSha256Hash()).isEqualTo(
            "newhashnewhashnewhashnewhashnewhashnewhashnewhashnewhashnewha1234");
    }

    @Test
    @Order(6)
    void testDuplicateDetection() throws Exception {
        Instant mtime = Instant.parse("2024-03-01T09:00:00Z");
        FileHashRecord r2 = buildRecord("/backup/img001_copy.jpg",
            "duphashduphashduphashduphashduphashduphashduphashduphashduphash12", 512000, mtime);
        dao.insert(r2);

        FileHashRecord found = dao.findBySha256(
            "duphashduphashduphashduphashduphashduphashduphashduphashduphash12");
        assertThat(found).isNotNull();
        assertThat(found.getFilePath()).isEqualTo("/backup/img001_copy.jpg");
    }

    @Test
    @Order(7)
    void testDeleteByFilePath() throws Exception {
        dao.deleteByFilePath("/photos/img001.jpg");
        FileHashRecord found = dao.findByFilePath("/photos/img001.jpg");
        assertThat(found).isNull();
    }
}
