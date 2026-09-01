package com.mediascanner.db;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/**
 * Verifies the V001 to V002 upgrade against a database built as V001 — the path every existing
 * install takes. The property that matters most: cached hashes must survive, or the first run
 * after upgrading re-reads the entire archive.
 */
class MigrationV002IT {

    @TempDir
    Path tempDir;

    private Path dbPath;

    @BeforeEach
    void buildV001Database() throws Exception {
        dbPath = tempDir.resolve("legacy.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE FILE_HASH_INDEX (
                    ID                   INTEGER PRIMARY KEY AUTOINCREMENT,
                    FILE_PATH            TEXT    NOT NULL,
                    FILE_NAME            TEXT    NOT NULL,
                    FILE_SIZE            INTEGER NOT NULL,
                    FILE_MODIFICATION_TS TEXT    NOT NULL,
                    SHA256_HASH          TEXT    NOT NULL,
                    MEDIA_DATE           TEXT,
                    CREATED_AT           TEXT    NOT NULL,
                    LAST_PROCESSED_AT    TEXT    NOT NULL)
                """);
            stmt.execute("CREATE UNIQUE INDEX idx_hash_sha256 ON FILE_HASH_INDEX(SHA256_HASH)");
            stmt.execute("CREATE INDEX idx_hash_filepath ON FILE_HASH_INDEX(FILE_PATH)");
            stmt.execute("""
                CREATE TABLE JOB_STATISTICS (
                    JOB_ID TEXT PRIMARY KEY,
                    STATUS TEXT NOT NULL DEFAULT 'RUNNING',
                    START_TIME TEXT NOT NULL,
                    END_TIME TEXT,
                    FILES_PROCESSED INTEGER NOT NULL DEFAULT 0,
                    FILES_FAILED INTEGER NOT NULL DEFAULT 0,
                    FILES_SKIPPED INTEGER NOT NULL DEFAULT 0,
                    DUPLICATES_FOUND INTEGER NOT NULL DEFAULT 0,
                    FILES_COPIED INTEGER NOT NULL DEFAULT 0,
                    FILES_MOVED INTEGER NOT NULL DEFAULT 0,
                    EMPTY_FILES_COUNT INTEGER NOT NULL DEFAULT 0,
                    SMALL_FILES_COUNT INTEGER NOT NULL DEFAULT 0,
                    CORRUPT_FILES_COUNT INTEGER NOT NULL DEFAULT 0,
                    TOTAL_BYTES_PROCESSED INTEGER NOT NULL DEFAULT 0,
                    TOTAL_BYTES_MOVED INTEGER NOT NULL DEFAULT 0,
                    TOTAL_BYTES_COPIED INTEGER NOT NULL DEFAULT 0,
                    TOTAL_BYTES_SKIPPED INTEGER NOT NULL DEFAULT 0,
                    DUPLICATE_BYTE_SAVINGS INTEGER NOT NULL DEFAULT 0,
                    TOTAL_FOLDERS_CREATED INTEGER NOT NULL DEFAULT 0,
                    AVG_MB_PER_SEC REAL NOT NULL DEFAULT 0,
                    PEAK_MB_PER_SEC REAL NOT NULL DEFAULT 0,
                    AVG_FILES_PER_SEC REAL NOT NULL DEFAULT 0,
                    PEAK_FILES_PER_SEC REAL NOT NULL DEFAULT 0,
                    AVG_CPU_PERCENT REAL NOT NULL DEFAULT 0,
                    PEAK_CPU_PERCENT REAL NOT NULL DEFAULT 0,
                    AVG_MEMORY_GB REAL NOT NULL DEFAULT 0,
                    PEAK_MEMORY_GB REAL NOT NULL DEFAULT 0)
                """);

            String now = Instant.now().toString();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO FILE_HASH_INDEX
                      (FILE_PATH, FILE_NAME, FILE_SIZE, FILE_MODIFICATION_TS, SHA256_HASH,
                       MEDIA_DATE, CREATED_AT, LAST_PROCESSED_AT)
                    VALUES (?, ?, ?, ?, ?, NULL, ?, ?)
                    """)) {
                for (int i = 1; i <= 3; i++) {
                    ps.setString(1, "C:\\Photos\\img" + i + ".jpg");
                    ps.setString(2, "img" + i + ".jpg");
                    ps.setLong(3, 1000L * i);
                    ps.setString(4, now);
                    ps.setString(5, "hash-" + i);
                    ps.setString(6, now);
                    ps.setString(7, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            stmt.execute("PRAGMA user_version = 1");
        }
    }

    @Test
    void testUpgradePreservesCachedHashesAndBackfillsCanonical() throws Exception {
        try (Database db = new Database(dbPath)) {
            HashIndexDao dao = new HashIndexDao(db);

            // Every cached hash must survive, or the next run re-reads the whole archive.
            for (int i = 1; i <= 3; i++) {
                var record = dao.findByFilePath("C:\\Photos\\img" + i + ".jpg");
                assertThat(record).as("cached row " + i + " survived the rebuild").isNotNull();
                assertThat(record.getSha256Hash()).isEqualTo("hash-" + i);
                assertThat(record.getFileSizeBytes()).isEqualTo(1000L * i);
                assertThat(record.getPartialHash()).as("backfilled rows have no partial hash yet")
                    .isNull();
            }

            // The duplicate gate must be pre-populated, or every already-indexed file would be
            // treated as new content on the next run.
            for (int i = 1; i <= 3; i++) {
                assertThat(dao.findCanonicalPath("hash-" + i))
                    .isEqualTo("C:\\Photos\\img" + i + ".jpg");
            }

            try (Statement stmt = db.getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).as("all migrations applied, latest is V004").isEqualTo(4);
            }
        }
    }

    @Test
    void testAfterUpgradeDuplicatePathsCanCacheTheirOwnHash() throws Exception {
        try (Database db = new Database(dbPath)) {
            HashIndexDao dao = new HashIndexDao(db);

            // A second path holding content already in the index. Under V001 this insert was
            // rejected by UNIQUE(SHA256_HASH) and the path was re-hashed on every run (SC-007).
            var duplicate = new com.mediascanner.model.FileHashRecord();
            duplicate.setFilePath("C:\\Backup\\img1-copy.jpg");
            duplicate.setFileName("img1-copy.jpg");
            duplicate.setFileSizeBytes(1000);
            duplicate.setFileModificationTs(Instant.now());
            duplicate.setSha256Hash("hash-1");
            duplicate.setCreatedAt(Instant.now());
            duplicate.setLastProcessedAt(Instant.now());

            dao.insert(duplicate);

            assertThat(dao.findByFilePath("C:\\Backup\\img1-copy.jpg")).isNotNull();
            // ...and it is still correctly identified as a duplicate of the original.
            assertThat(dao.claimCanonical("hash-1", "C:\\Backup\\img1-copy.jpg")).isFalse();
            assertThat(dao.findCanonicalPath("hash-1")).isEqualTo("C:\\Photos\\img1.jpg");
        }
    }

    @Test
    void testUpgradeIsNotReappliedOnReopen() throws Exception {
        try (Database db = new Database(dbPath)) {
            assertThat(db.isCorruptionWarning()).isFalse();
        }
        // Reopening must be a no-op, not a second rebuild that would drop the data.
        try (Database db = new Database(dbPath)) {
            HashIndexDao dao = new HashIndexDao(db);
            assertThat(dao.findByFilePath("C:\\Photos\\img2.jpg")).isNotNull();
            assertThat(dao.findCanonicalPath("hash-2")).isEqualTo("C:\\Photos\\img2.jpg");
        }
    }
}
