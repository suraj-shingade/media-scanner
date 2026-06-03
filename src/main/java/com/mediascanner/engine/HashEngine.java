package com.mediascanner.engine;

import com.mediascanner.db.HashIndexDao;
import com.mediascanner.model.FileHashRecord;
import com.mediascanner.model.MediaFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;

public class HashEngine {

    private static final Logger log = LoggerFactory.getLogger(HashEngine.class);
    private static final int PARTIAL_READ_BYTES = 64 * 1024;    // 64 KB
    private static final int CHUNK_SIZE = 8 * 1024 * 1024;       // 8 MB

    private final HashIndexDao hashIndexDao;

    public HashEngine(HashIndexDao hashIndexDao) {
        this.hashIndexDao = hashIndexDao;
    }

    /**
     * Three-stage tiered hashing:
     * Stage 1: cache hit by path (size + mtime) → return cached hash
     * Stage 2: partial 64 KB SHA-256 → check if any match
     * Stage 3: full SHA-256
     */
    public String computeHash(Path path, MediaFile mediaFile) throws Exception {
        long size = mediaFile.getSizeBytes();
        Instant mtime = mediaFile.getModificationTimestamp();
        String pathStr = path.toAbsolutePath().toString();

        // Stage 1: check cache
        FileHashRecord cached = hashIndexDao.findAndValidateCache(pathStr, size, mtime);
        if (cached != null) {
            log.debug("Stage 1 cache hit for {}", path.getFileName());
            return cached.getSha256Hash();
        }

        // Stage 2: partial hash (64 KB)
        String partialHash = computePartialHash(path);

        // Stage 3: full SHA-256
        String fullHash = computeFullHash(path);

        // Persist to index
        persistHash(pathStr, path.getFileName().toString(), size, mtime, fullHash,
                    mediaFile.getExtractedDate() != null ? mediaFile.getExtractedDate() : null);

        return fullHash;
    }

    private String computePartialHash(Path path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(path)) {
            byte[] buffer = new byte[PARTIAL_READ_BYTES];
            int read = is.read(buffer);
            if (read > 0) md.update(buffer, 0, read);
        }
        return bytesToHex(md.digest());
    }

    private String computeFullHash(Path path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(path)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int read;
            while ((read = is.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
        }
        return bytesToHex(md.digest());
    }

    private void persistHash(String filePath, String fileName, long size, Instant mtime,
                              String hash, java.time.LocalDateTime mediaDate) {
        try {
            FileHashRecord existing = hashIndexDao.findByFilePath(filePath);
            Instant now = Instant.now();
            if (existing == null) {
                FileHashRecord record = new FileHashRecord();
                record.setFilePath(filePath);
                record.setFileName(fileName);
                record.setFileSizeBytes(size);
                record.setFileModificationTs(mtime);
                record.setSha256Hash(hash);
                record.setMediaDate(mediaDate);
                record.setCreatedAt(now);
                record.setLastProcessedAt(now);
                hashIndexDao.insert(record);
            } else {
                existing.setFileSizeBytes(size);
                existing.setFileModificationTs(mtime);
                existing.setSha256Hash(hash);
                existing.setLastProcessedAt(now);
                if (mediaDate != null) existing.setMediaDate(mediaDate);
                hashIndexDao.updateRecord(existing);
            }
        } catch (Exception e) {
            log.warn("Could not persist hash for {}: {}", filePath, e.getMessage());
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
