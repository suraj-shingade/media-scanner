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
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SQLite access point.
 *
 * <p>A SQLite {@link Connection} is not safe for concurrent use, so every thread gets its own
 * connection via a {@link ThreadLocal}. WAL mode allows many concurrent readers alongside a single
 * writer; {@code busy_timeout} makes writers wait for the lock instead of failing immediately.
 * Worker threads call {@link #releaseCurrentThreadConnection()} when they terminate so connections
 * do not accumulate across jobs.
 */
public class Database implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    /** Migration scripts under {@code /db/migrations}, in apply order. Add new versions here. */
    private static final List<String> MIGRATIONS = List.of(
        "V001__initial_schema.sql",
        "V002__job_reports.sql",
        "V003__resume_destination.sql",
        "V004__disk_throughput.sql"
    );

    private final Path dbPath;
    private final List<Connection> openConnections = Collections.synchronizedList(new ArrayList<>());
    private final ThreadLocal<Connection> threadConnection = ThreadLocal.withInitial(() -> {
        try {
            return openConnection();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not open SQLite connection", e);
        }
    });

    private Connection primary;
    private boolean corruptionWarning = false;

    public Database(Path dbPath) throws SQLException {
        this.dbPath = dbPath;
        ensureParentDir();
        primary = openConnection();
        threadConnection.set(primary);
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

    private Connection openConnection() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL");
            stmt.execute("PRAGMA foreign_keys = ON");
            stmt.execute("PRAGMA synchronous = NORMAL");
            // Workers contend for the single write lock; wait rather than fail with SQLITE_BUSY.
            stmt.execute("PRAGMA busy_timeout = 30000");
        }
        openConnections.add(conn);
        return conn;
    }

    private void runIntegrityCheck() {
        try (Statement stmt = primary.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA integrity_check")) {
            if (rs.next()) {
                String result = rs.getString(1);
                if (!"ok".equalsIgnoreCase(result)) {
                    handleCorruption();
                }
            }
        } catch (SQLException e) {
            log.warn("Integrity check failed - treating DB as corrupt: {}", e.getMessage());
            try { handleCorruption(); } catch (SQLException ex) {
                log.error("Could not recover from corruption: {}", ex.getMessage());
            }
        }
    }

    private void handleCorruption() throws SQLException {
        log.warn("Database corruption detected. Renaming corrupt file and creating fresh DB.");
        closeAllConnections();

        Path corruptPath = dbPath.resolveSibling(
            dbPath.getFileName() + ".corrupt." + Instant.now().toEpochMilli());
        try {
            Files.move(dbPath, corruptPath);
            log.info("Corrupt DB renamed to: {}", corruptPath);
        } catch (IOException e) {
            log.error("Could not rename corrupt DB: {}", e.getMessage());
        }

        primary = openConnection();
        threadConnection.set(primary);
        corruptionWarning = true;
    }

    private void applyMigrations() throws SQLException {
        int currentVersion;
        try (Statement stmt = primary.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
            currentVersion = rs.next() ? rs.getInt(1) : 0;
        }
        log.info("Current DB schema version: {}", currentVersion);

        for (String scriptName : discoverMigrations()) {
            int scriptVersion = parseMigrationVersion(scriptName);
            if (scriptVersion > currentVersion) {
                log.info("Applying migration: {}", scriptName);
                String sql = loadScript("/db/migrations/" + scriptName);
                for (String statement : splitStatements(sql)) {
                    try (Statement stmt = primary.createStatement()) {
                        stmt.execute(statement);
                    }
                }
                log.info("Migration {} applied successfully", scriptName);
            }
        }
    }

    private List<String> discoverMigrations() {
        return MIGRATIONS.stream().sorted().collect(Collectors.toList());
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

    /**
     * Splits a migration script into executable statements.
     *
     * <p>Line comments are stripped <em>before</em> splitting on {@code ;}. Splitting first is
     * unsafe: a comment containing a semicolon is cut in half, and the tail — which no longer
     * starts with {@code --} — is then handed to SQLite as a statement.
     *
     * <p>Statements themselves must still not contain a semicolon inside a string literal or a
     * trigger body; this splitter is deliberately simple, not a SQL parser.
     */
    private List<String> splitStatements(String sql) {
        StringBuilder withoutComments = new StringBuilder(sql.length());
        for (String line : sql.split("\\R")) {
            int comment = line.indexOf("--");
            withoutComments.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }

        List<String> statements = new ArrayList<>();
        for (String stmt : withoutComments.toString().split(";")) {
            String trimmed = stmt.trim();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        return statements;
    }

    /** The calling thread own connection. Never share the returned instance across threads. */
    public Connection getConnection() {
        Connection conn = threadConnection.get();
        try {
            if (conn.isClosed()) {
                threadConnection.remove();
                conn = threadConnection.get();
            }
        } catch (SQLException e) {
            threadConnection.remove();
            conn = threadConnection.get();
        }
        return conn;
    }

    /**
     * Closes and forgets the calling thread connection. Worker threads call this as they die so a
     * long-lived session does not accumulate one connection per thread per job.
     */
    public void releaseCurrentThreadConnection() {
        Connection conn = threadConnection.get();
        threadConnection.remove();
        if (conn == primary) return;
        openConnections.remove(conn);
        try {
            conn.close();
        } catch (SQLException e) {
            log.debug("Error closing worker DB connection: {}", e.getMessage());
        }
    }

    public boolean isCorruptionWarning() {
        return corruptionWarning;
    }

    private void closeAllConnections() {
        synchronized (openConnections) {
            for (Connection conn : openConnections) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    log.warn("Error closing DB connection: {}", e.getMessage());
                }
            }
            openConnections.clear();
        }
        threadConnection.remove();
    }

    @Override
    public void close() {
        closeAllConnections();
    }
}
