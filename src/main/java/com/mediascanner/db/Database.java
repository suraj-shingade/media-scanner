package com.mediascanner.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class Database implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    private final Path dbPath;
    private Connection connection;
    private boolean corruptionWarning = false;

    public Database(Path dbPath) throws SQLException {
        this.dbPath = dbPath;
        ensureParentDir();
        open();
        runIntegrityCheck();
        applyMigrations();
    }

    private void ensureParentDir() {
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (IOException e) {
            log.warn("Could not create DB parent directory: {}", e.getMessage());
        }
    }

    private void open() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL");
            stmt.execute("PRAGMA foreign_keys = ON");
            stmt.execute("PRAGMA synchronous = NORMAL");
        }
    }

    private void runIntegrityCheck() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA integrity_check")) {
            if (rs.next()) {
                String result = rs.getString(1);
                if (!"ok".equalsIgnoreCase(result)) {
                    handleCorruption();
                }
            }
        } catch (SQLException e) {
            log.warn("Integrity check failed — treating DB as corrupt: {}", e.getMessage());
            try { handleCorruption(); } catch (SQLException ex) {
                log.error("Could not recover from corruption: {}", ex.getMessage());
            }
        }
    }

    private void handleCorruption() throws SQLException {
        log.warn("Database corruption detected. Renaming corrupt file and creating fresh DB.");
        try {
            connection.close();
        } catch (SQLException ignored) {}

        Path corruptPath = dbPath.resolveSibling(
            dbPath.getFileName() + ".corrupt." + Instant.now().toEpochMilli());
        try {
            Files.move(dbPath, corruptPath);
            log.info("Corrupt DB renamed to: {}", corruptPath);
        } catch (IOException e) {
            log.error("Could not rename corrupt DB: {}", e.getMessage());
        }

        open();
        corruptionWarning = true;
    }

    private void applyMigrations() throws SQLException {
        int currentVersion;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
            currentVersion = rs.next() ? rs.getInt(1) : 0;
        }
        log.info("Current DB schema version: {}", currentVersion);

        List<String> migrationScripts = discoverMigrations();
        for (String scriptName : migrationScripts) {
            int scriptVersion = parseMigrationVersion(scriptName);
            if (scriptVersion > currentVersion) {
                log.info("Applying migration: {}", scriptName);
                String sql = loadScript("/db/migrations/" + scriptName);
                for (String statement : splitStatements(sql)) {
                    try (Statement stmt = connection.createStatement()) {
                        stmt.execute(statement);
                    }
                }
                log.info("Migration {} applied successfully", scriptName);
            }
        }
    }

    private List<String> discoverMigrations() {
        List<String> scripts = new ArrayList<>();
        scripts.add("V001__initial_schema.sql");
        return scripts.stream().sorted().collect(Collectors.toList());
    }

    private int parseMigrationVersion(String scriptName) {
        try {
            String versionPart = scriptName.substring(1, scriptName.indexOf("__"));
            return Integer.parseInt(versionPart);
        } catch (Exception e) {
            return 0;
        }
    }

    private String loadScript(String classpathResource) throws SQLException {
        try (InputStream is = getClass().getResourceAsStream(classpathResource)) {
            if (is == null) {
                throw new SQLException("Migration script not found on classpath: " + classpathResource);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            throw new SQLException("Failed to read migration script: " + classpathResource, e);
        }
    }

    private List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        for (String stmt : sql.split(";")) {
            String trimmed = stmt.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                statements.add(trimmed);
            }
        }
        return statements;
    }

    public Connection getConnection() {
        return connection;
    }

    public boolean isCorruptionWarning() {
        return corruptionWarning;
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("Error closing DB connection: {}", e.getMessage());
            }
        }
    }
}
