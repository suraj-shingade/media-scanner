package com.mediascanner.db;

import com.mediascanner.model.FileHashRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class HashIndexDao {

    private static final Logger log = LoggerFactory.getLogger(HashIndexDao.class);
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final Database database;

    public HashIndexDao(Database database) {
        this.database = database;
    }

    public void insert(FileHashRecord record) throws SQLException {
        String sql = """
            INSERT INTO FILE_HASH_INDEX
              (FILE_PATH, FILE_NAME, FILE_SIZE, FILE_MODIFICATION_TS, SHA256_HASH,
               PARTIAL_HASH, MEDIA_DATE, CREATED_AT, LAST_PROCESSED_AT)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, record.getFilePath());
            ps.setString(2, record.getFileName());
            ps.setLong(3, record.getFileSizeBytes());
            ps.setString(4, record.getFileModificationTs().toString());
            ps.setString(5, record.getSha256Hash());
            ps.setString(6, record.getPartialHash());
            ps.setString(7, record.getMediaDate() != null
                ? record.getMediaDate().format(ISO_FMT) : null);
            ps.setString(8, record.getCreatedAt().toString());
            ps.setString(9, record.getLastProcessedAt().toString());
            ps.executeUpdate();
        }
    }

    public FileHashRecord findBySha256(String sha256Hash) throws SQLException {
        String sql = "SELECT * FROM FILE_HASH_INDEX WHERE SHA256_HASH = ?";
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, sha256Hash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    public FileHashRecord findByFilePath(String filePath) throws SQLException {
        String sql = "SELECT * FROM FILE_HASH_INDEX WHERE FILE_PATH = ?";
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, filePath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    public void updateRecord(FileHashRecord record) throws SQLException {
        String sql = """
            UPDATE FILE_HASH_INDEX
               SET FILE_SIZE = ?, FILE_MODIFICATION_TS = ?, SHA256_HASH = ?,
                   PARTIAL_HASH = ?, MEDIA_DATE = ?, LAST_PROCESSED_AT = ?
             WHERE FILE_PATH = ?
            """;
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setLong(1, record.getFileSizeBytes());
            ps.setString(2, record.getFileModificationTs().toString());
            ps.setString(3, record.getSha256Hash());
            ps.setString(4, record.getPartialHash());
            ps.setString(5, record.getMediaDate() != null
                ? record.getMediaDate().format(ISO_FMT) : null);
            ps.setString(6, record.getLastProcessedAt().toString());
            ps.setString(7, record.getFilePath());
            ps.executeUpdate();
        }
    }

    public void deleteByFilePath(String filePath) throws SQLException {
        String sql = "DELETE FROM FILE_HASH_INDEX WHERE FILE_PATH = ?";
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, filePath);
            ps.executeUpdate();
        }
    }

    /**
     * Returns null if cache is valid (size and mtime match).
     * Returns existing record for cache invalidation check — caller decides whether to re-hash.
     */
    public FileHashRecord findAndValidateCache(String filePath, long currentSize,
                                               Instant currentMtime) throws SQLException {
        FileHashRecord record = findByFilePath(filePath);
        if (record == null) return null;
        boolean cacheValid = record.getFileSizeBytes() == currentSize
            && record.getFileModificationTs().equals(currentMtime);
        return cacheValid ? record : null;
    }

    public void clearAll() throws SQLException {
        try (java.sql.Statement st = database.getConnection().createStatement()) {
            st.executeUpdate("DELETE FROM FILE_HASH_INDEX");
            st.executeUpdate("DELETE FROM HASH_CANONICAL");
        }
    }

    /**
     * Atomically claims {@code sha256Hash} for {@code filePath} in HASH_CANONICAL.
     *
     * <p>This single statement replaces the former check-then-act pair (findBySha256 followed by a
     * path comparison), which was correct only because UNIQUE(SHA256_HASH) on FILE_HASH_INDEX
     * happened to serialise it. The primary key on HASH_CANONICAL now provides that serialisation
     * explicitly, and FILE_HASH_INDEX is free to cache every path.
     *
     * @return true if this path claimed the hash (it is canonical); false if another path already
     *         holds it, meaning this file is a content duplicate
     */
    public boolean claimCanonical(String sha256Hash, String filePath) throws SQLException {
        String sql = """
            INSERT OR IGNORE INTO HASH_CANONICAL (SHA256_HASH, CANONICAL_PATH, FIRST_SEEN_AT)
            VALUES (?, ?, ?)
            """;
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, sha256Hash);
            ps.setString(2, filePath);
            ps.setString(3, Instant.now().toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** The path that currently owns this hash, or null if unclaimed. */
    public String findCanonicalPath(String sha256Hash) throws SQLException {
        String sql = "SELECT CANONICAL_PATH FROM HASH_CANONICAL WHERE SHA256_HASH = ?";
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, sha256Hash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * Records where the canonical copy of this content was actually written, so a later run can
     * tell "already transferred" from "new file, colliding name" (feature 007).
     */
    public void recordCanonicalDestination(String sha256Hash, String destinationPath,
                                           long destinationSize) throws SQLException {
        String sql = """
            UPDATE HASH_CANONICAL
               SET DESTINATION_PATH = ?, DESTINATION_SIZE = ?
             WHERE SHA256_HASH = ?
            """;
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, destinationPath);
            ps.setLong(2, destinationSize);
            ps.setString(3, sha256Hash);
            ps.executeUpdate();
        }
    }

    /** Where this content was written, or null if it was never successfully transferred. */
    public TransferredCopy findCanonicalDestination(String sha256Hash) throws SQLException {
        String sql = "SELECT DESTINATION_PATH, DESTINATION_SIZE FROM HASH_CANONICAL"
                   + " WHERE SHA256_HASH = ?";
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, sha256Hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String path = rs.getString("DESTINATION_PATH");
                if (path == null) return null;
                return new TransferredCopy(path, rs.getLong("DESTINATION_SIZE"));
            }
        }
    }

    /** A completed transfer: where it landed and how big it was. */
    public record TransferredCopy(String path, long size) {}

    private FileHashRecord mapRow(ResultSet rs) throws SQLException {
        FileHashRecord r = new FileHashRecord();
        r.setId(rs.getLong("ID"));
        r.setFilePath(rs.getString("FILE_PATH"));
        r.setFileName(rs.getString("FILE_NAME"));
        r.setFileSizeBytes(rs.getLong("FILE_SIZE"));
        r.setFileModificationTs(Instant.parse(rs.getString("FILE_MODIFICATION_TS")));
        r.setSha256Hash(rs.getString("SHA256_HASH"));
        r.setPartialHash(rs.getString("PARTIAL_HASH"));
        String mediaDateStr = rs.getString("MEDIA_DATE");
        if (mediaDateStr != null) {
            r.setMediaDate(LocalDateTime.parse(mediaDateStr, ISO_FMT));
        }
        r.setCreatedAt(Instant.parse(rs.getString("CREATED_AT")));
        r.setLastProcessedAt(Instant.parse(rs.getString("LAST_PROCESSED_AT")));
        return r;
    }
}
