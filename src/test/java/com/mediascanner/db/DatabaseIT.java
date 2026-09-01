package com.mediascanner.db;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseIT {

    static Path testDbPath;
    static Database db;

    @BeforeAll
    static void setUp() throws Exception {
        testDbPath = Files.createTempDirectory("mediascanner-test").resolve("test.db");
        db = new Database(testDbPath);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (db != null) db.close();
        Files.deleteIfExists(testDbPath);
        Files.deleteIfExists(testDbPath.getParent());
    }

    @Test
    @Order(1)
    void testDatabaseOpens() {
        assertThat(db.getConnection()).isNotNull();
    }

    @Test
    @Order(2)
    void testFileHashIndexTableExists() throws SQLException {
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT name FROM sqlite_master WHERE type='table' AND name='FILE_HASH_INDEX'")) {
            assertThat(rs.next()).isTrue();
        }
    }

    @Test
    @Order(3)
    void testJobStatisticsTableExists() throws SQLException {
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT name FROM sqlite_master WHERE type='table' AND name='JOB_STATISTICS'")) {
            assertThat(rs.next()).isTrue();
        }
    }

    @Test
    @Order(4)
    void testWalModeEnabled() throws SQLException {
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA journal_mode")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualToIgnoringCase("wal");
        }
    }

    @Test
    @Order(5)
    void testIntegrityCheckPasses() throws SQLException {
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA integrity_check")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualToIgnoringCase("ok");
        }
    }

    @Test
    @Order(6)
    void testSchemaVersion() throws SQLException {
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(4);
        }
    }

    /** V003: the resume ledger — where each canonical file was actually written. */
    @Test
    @Order(11)
    void testCanonicalDestinationColumnsExist() throws SQLException {
        java.util.Set<String> cols = new java.util.HashSet<>();
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info('HASH_CANONICAL')")) {
            while (rs.next()) cols.add(rs.getString("name"));
        }
        assertThat(cols).contains("DESTINATION_PATH", "DESTINATION_SIZE");
    }

    /**
     * V002 moved the duplicate gate out of FILE_HASH_INDEX. The UNIQUE constraint on SHA256_HASH
     * was doing two conflicting jobs: serialising duplicate decisions, and — as a side effect —
     * rejecting the cache row for every duplicate path, so duplicates were re-hashed on every run.
     * The gate now lives in HASH_CANONICAL and the index keeps only a plain lookup index.
     */
    @Test
    @Order(7)
    void testDuplicateGateMovedToHashCanonical() throws SQLException {
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT name FROM sqlite_master WHERE type='table' AND name='HASH_CANONICAL'")) {
            assertThat(rs.next()).as("HASH_CANONICAL table exists").isTrue();
        }
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT name FROM sqlite_master WHERE type='index'"
                 + " AND name='idx_hash_sha256_lookup'")) {
            assertThat(rs.next()).as("non-unique SHA-256 lookup index exists").isTrue();
        }
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA index_list('FILE_HASH_INDEX')")) {
            while (rs.next()) {
                if (rs.getInt("unique") == 1) {
                    // The only surviving unique index must be the one on FILE_PATH.
                    String indexName = rs.getString("name");
                    try (Statement inner = db.getConnection().createStatement();
                         ResultSet cols = inner.executeQuery(
                             "PRAGMA index_info('" + indexName + "')")) {
                        assertThat(cols.next()).isTrue();
                        assertThat(cols.getString("name"))
                            .as("unique index on FILE_HASH_INDEX must be FILE_PATH, not SHA256_HASH")
                            .isEqualTo("FILE_PATH");
                    }
                }
            }
        }
    }

    @Test
    @Order(9)
    void testPartialHashColumnExists() throws SQLException {
        boolean found = false;
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info('FILE_HASH_INDEX')")) {
            while (rs.next()) {
                if ("PARTIAL_HASH".equals(rs.getString("name"))) found = true;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    @Order(10)
    void testJobReportTablesExist() throws SQLException {
        for (String table : new String[] {"JOB_EVENT", "JOB_THROUGHPUT_SAMPLE"}) {
            try (Statement stmt = db.getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
                assertThat(rs.next()).as(table + " exists").isTrue();
            }
        }
    }

    @Test
    @Order(8)
    void testFilePathIndexExists() throws SQLException {
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_hash_filepath'")) {
            assertThat(rs.next()).isTrue();
        }
    }
}
