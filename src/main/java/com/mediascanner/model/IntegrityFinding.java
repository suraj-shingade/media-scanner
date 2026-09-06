package com.mediascanner.model;

import java.nio.file.Path;

/**
 * One file and what verification determined about it (FR-074).
 *
 * <p>A finding carries both sides of every comparison it made — expected and observed — because the
 * report is meant to be actionable without re-running the job (SC-005). "corrupted" on its own tells the
 * user nothing they can do; the recorded hash beside the observed hash tells them which copy to trust.
 *
 * <p>Null observed values are meaningful: a MISSING file has no observed size, and a quick-mode finding
 * has no observed hash because none was computed. They are not defaults, they are the absence of a
 * measurement, and the report distinguishes the two.
 */
public class IntegrityFinding {

    private final Path path;
    private final IntegrityStatus status;
    private final long expectedSize;
    private final Long observedSize;
    private final String expectedHash;
    private final String observedHash;
    private final String reason;

    private IntegrityFinding(Path path, IntegrityStatus status,
                             long expectedSize, Long observedSize,
                             String expectedHash, String observedHash, String reason) {
        this.path = path;
        this.status = status;
        this.expectedSize = expectedSize;
        this.observedSize = observedSize;
        this.expectedHash = expectedHash;
        this.observedHash = observedHash;
        this.reason = reason;
    }

    public static IntegrityFinding intact(Path path, long size, String hash) {
        return new IntegrityFinding(path, IntegrityStatus.INTACT, size, size, hash, hash, null);
    }

    public static IntegrityFinding missing(Path path, long expectedSize, String expectedHash) {
        return new IntegrityFinding(path, IntegrityStatus.MISSING,
            expectedSize, null, expectedHash, null, "No file at the recorded destination");
    }

    public static IntegrityFinding resized(Path path, long expectedSize, long observedSize,
                                           String expectedHash) {
        return new IntegrityFinding(path, IntegrityStatus.RESIZED,
            expectedSize, observedSize, expectedHash, null,
            "Size changed since transfer: expected " + expectedSize + ", found " + observedSize);
    }

    public static IntegrityFinding corrupted(Path path, long size,
                                             String expectedHash, String observedHash) {
        return new IntegrityFinding(path, IntegrityStatus.CORRUPTED,
            size, size, expectedHash, observedHash,
            "Same size, different contents — the file has been altered since transfer");
    }

    public static IntegrityFinding unreadable(Path path, long expectedSize,
                                              String expectedHash, String reason) {
        return new IntegrityFinding(path, IntegrityStatus.UNREADABLE,
            expectedSize, null, expectedHash, null, reason);
    }

    public static IntegrityFinding untracked(Path path, long observedSize) {
        return new IntegrityFinding(path, IntegrityStatus.UNTRACKED,
            0L, observedSize, null, null, "Present in the archive with no transfer record");
    }

    public static IntegrityFinding unverifiable(Path path, String expectedHash) {
        return new IntegrityFinding(path, IntegrityStatus.UNVERIFIABLE,
            0L, null, expectedHash, null,
            "Transferred before destinations were recorded, so there is nothing to check against");
    }

    public Path getPath() { return path; }
    public IntegrityStatus getStatus() { return status; }
    public long getExpectedSize() { return expectedSize; }
    public Long getObservedSize() { return observedSize; }
    public String getExpectedHash() { return expectedHash; }
    public String getObservedHash() { return observedHash; }
    public String getReason() { return reason; }

    public boolean isProblem() { return status.isProblem(); }

    @Override
    public String toString() {
        return status.name() + " " + path;
    }
}
