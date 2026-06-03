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
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @Order(7)
    void testSha256IndexExists() throws SQLException {
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_hash_sha256'")) {
            assertThat(rs.next()).isTrue();
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
