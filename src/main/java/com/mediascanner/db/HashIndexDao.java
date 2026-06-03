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
               MEDIA_DATE, CREATED_AT, LAST_PROCESSED_AT)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, record.getFilePath());
            ps.setString(2, record.getFileName());
            ps.setLong(3, record.getFileSizeBytes());
            ps.setString(4, record.getFileModificationTs().toString());
            ps.setString(5, record.getSha256Hash());
            ps.setString(6, record.getMediaDate() != null
                ? record.getMediaDate().format(ISO_FMT) : null);
            ps.setString(7, record.getCreatedAt().toString());
            ps.setString(8, record.getLastProcessedAt().toString());
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
                   MEDIA_DATE = ?, LAST_PROCESSED_AT = ?
             WHERE FILE_PATH = ?
            """;
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setLong(1, record.getFileSizeBytes());
            ps.setString(2, record.getFileModificationTs().toString());
            ps.setString(3, record.getSha256Hash());
            ps.setString(4, record.getMediaDate() != null
                ? record.getMediaDate().format(ISO_FMT) : null);
            ps.setString(5, record.getLastProcessedAt().toString());
            ps.setString(6, record.getFilePath());
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

    private FileHashRecord mapRow(ResultSet rs) throws SQLException {
        FileHashRecord r = new FileHashRecord();
        r.setId(rs.getLong("ID"));
        r.setFilePath(rs.getString("FILE_PATH"));
        r.setFileName(rs.getString("FILE_NAME"));
        r.setFileSizeBytes(rs.getLong("FILE_SIZE"));
        r.setFileModificationTs(Instant.parse(rs.getString("FILE_MODIFICATION_TS")));
        r.setSha256Hash(rs.getString("SHA256_HASH"));
        String mediaDateStr = rs.getString("MEDIA_DATE");
        if (mediaDateStr != null) {
            r.setMediaDate(LocalDateTime.parse(mediaDateStr, ISO_FMT));
        }
        r.setCreatedAt(Instant.parse(rs.getString("CREATED_AT")));
        r.setLastProcessedAt(Instant.parse(rs.getString("LAST_PROCESSED_AT")));
        return r;
    }
}
