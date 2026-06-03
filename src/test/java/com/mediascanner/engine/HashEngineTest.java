package com.mediascanner.engine;

import com.mediascanner.db.Database;
import com.mediascanner.db.HashIndexDao;
import com.mediascanner.model.FileHashRecord;
import com.mediascanner.model.MediaFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class HashEngineTest {

    @TempDir
    Path tempDir;

    static Path dbPath;
    static Database db;
    static HashIndexDao hashIndexDao;
    static HashEngine hashEngine;

    @BeforeAll
    static void setUpDb() throws Exception {
        dbPath = Files.createTempDirectory("hash-engine-test-db").resolve("test.db");
        db = new Database(dbPath);
        hashIndexDao = new HashIndexDao(db);
        hashEngine = new HashEngine(hashIndexDao);
    }

    @AfterAll
    static void tearDownDb() throws Exception {
        if (db != null) db.close();
        Files.deleteIfExists(dbPath);
        Files.deleteIfExists(dbPath.getParent());
    }

    private MediaFile buildMediaFile(Path path) throws Exception {
        MediaFile mf = new MediaFile();
        mf.setAbsolutePath(path.toString());
        mf.setFileName(path.getFileName().toString());
        mf.setSizeBytes(Files.size(path));
        mf.setModificationTimestamp(Files.getLastModifiedTime(path).toInstant());
        return mf;
    }

    @Test
    void testCachedHashUsedWhenSizeAndMtimeMatch() throws Exception {
        Path file = tempDir.resolve("cached.jpg");
        Files.write(file, "cached content for test".getBytes());

        MediaFile mf = buildMediaFile(file);
        String hash1 = hashEngine.computeHash(file, mf);
        // Second call should hit cache
        String hash2 = hashEngine.computeHash(file, mf);
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64);
    }

    @Test
    void testReHashTriggeredWhenCacheMiss() throws Exception {
        Path file = tempDir.resolve("rehash.jpg");
        Files.write(file, "fresh content for rehash test".getBytes());

        MediaFile mf = buildMediaFile(file);
        String hash = hashEngine.computeHash(file, mf);
        assertThat(hash).hasSize(64);

        FileHashRecord record = hashIndexDao.findByFilePath(file.toString());
        assertThat(record).isNotNull();
        assertThat(record.getSha256Hash()).isEqualTo(hash);
    }

    @Test
    void testDifferentContentProducesDifferentHash() throws Exception {
        Path file1 = tempDir.resolve("diff_a.jpg");
        Path file2 = tempDir.resolve("diff_b.jpg");
        Files.write(file1, "content ALPHA unique string 12345".getBytes());
        Files.write(file2, "content BETA  unique string 67890".getBytes());

        MediaFile mf1 = buildMediaFile(file1);
        MediaFile mf2 = buildMediaFile(file2);
        String hash1 = hashEngine.computeHash(file1, mf1);
        String hash2 = hashEngine.computeHash(file2, mf2);
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void testSameContentProducesSameHash() throws Exception {
        Path file1 = tempDir.resolve("same1.jpg");
        Path file2 = tempDir.resolve("same2.jpg");
        byte[] content = "identical content for both files 999".getBytes();
        Files.write(file1, content);
        Files.write(file2, content);

        MediaFile mf1 = buildMediaFile(file1);
        MediaFile mf2 = buildMediaFile(file2);
        String hash1 = hashEngine.computeHash(file1, mf1);
        String hash2 = hashEngine.computeHash(file2, mf2);
        assertThat(hash1).isEqualTo(hash2);
    }
}
