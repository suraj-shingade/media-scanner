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
    private static final int PARTIAL_READ_BYTES = 64 * 1024;     // 64 KB
    private static final int CHUNK_SIZE = 8 * 1024 * 1024;       // 8 MB

    private final HashIndexDao hashIndexDao;

    public HashEngine(HashIndexDao hashIndexDao) {
        this.hashIndexDao = hashIndexDao;
    }

    /**
     * Tiered hashing (FR-025):
     * Stage 1: index hit by path (size + mtime unchanged) - return the stored hash, no read
     * Stage 2: full SHA-256, with the leading-block digest captured in the same pass
     *
     * <p>The PARTIAL_HASH column now exists, so the leading-block digest is stored for a future
     * Stage 2 short-circuit. It is computed from the first chunk of the full-hash read rather than
     * by opening the file a second time, so it costs no extra I/O - unlike the earlier version,
     * which read 64 KB separately and then discarded the result.
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

        // Stage 2: full SHA-256 (+ leading-block digest, same pass)
        Digests digests = computeDigests(path);

        // Persist to index
        persistHash(pathStr, path.getFileName().toString(), size, mtime,
                    digests.full, digests.partial,
                    mediaFile.getExtractedDate() != null ? mediaFile.getExtractedDate() : null);

        return digests.full;
    }

    /** Full-file digest plus a digest of the leading {@value #PARTIAL_READ_BYTES} bytes. */
    private record Digests(String full, String partial) {}

    private Digests computeDigests(Path path) throws Exception {
        MessageDigest full = MessageDigest.getInstance("SHA-256");
        MessageDigest partial = MessageDigest.getInstance("SHA-256");
        long partialRemaining = PARTIAL_READ_BYTES;

        try (InputStream is = Files.newInputStream(path)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int read;
            while ((read = is.read(buffer)) != -1) {
                full.update(buffer, 0, read);
                if (partialRemaining > 0) {
                    int take = (int) Math.min(partialRemaining, read);
                    partial.update(buffer, 0, take);
                    partialRemaining -= take;
                }
            }
        }
        return new Digests(bytesToHex(full.digest()), bytesToHex(partial.digest()));
    }

    private void persistHash(String filePath, String fileName, long size, Instant mtime,
                              String hash, String partialHash,
                              java.time.LocalDateTime mediaDate) {
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
                record.setPartialHash(partialHash);
                record.setMediaDate(mediaDate);
                record.setCreatedAt(now);
                record.setLastProcessedAt(now);
                hashIndexDao.insert(record);
            } else {
                existing.setFileSizeBytes(size);
                existing.setFileModificationTs(mtime);
                existing.setSha256Hash(hash);
                existing.setPartialHash(partialHash);
                existing.setLastProcessedAt(now);
                if (mediaDate != null) existing.setMediaDate(mediaDate);
                hashIndexDao.updateRecord(existing);
            }
        } catch (Exception e) {
            // Since V002 the index is keyed by path, not by content, so every path caches its own
            // hash - including duplicates, which used to be rejected here and fully re-read on
            // every subsequent run. A failure now is a genuine problem worth surfacing.
            log.warn("Could not cache hash for {}: {}", filePath, e.getMessage());
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
