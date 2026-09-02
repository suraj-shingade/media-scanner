package com.mediascanner.model;

import java.nio.file.Path;

/**
 * One file found by a Cleanup analysis, together with what was decided about it.
 *
 * <p>A candidate carries the size and MIME type observed <em>at analysis time</em>. Those are the
 * values shown in the preview, and they are exactly what {@code CleanupEngine.delete} re-checks
 * against the file on disk before removing it (FR-044) — a preview is a snapshot, and a file can
 * change underneath it.
 */
public class CleanupCandidate {

    public enum Outcome {
        /** Classified and shown in the preview. Nothing has been done to it. */
        PREVIEWED,
        /** Permanently removed. */
        DELETED,
        /** Not removed: it no longer matched the group it was previewed under (FR-044). */
        SKIPPED_REVERIFY,
        /** Not removed: the filesystem refused (locked, read-only, permission denied) (FR-046). */
        FAILED
    }

    private final Path path;
    private final long sizeBytes;
    private final String detectedMimeType;
    private final MimeGroup group;

    private Outcome outcome = Outcome.PREVIEWED;
    private String reason;

    public CleanupCandidate(Path path, long sizeBytes, String detectedMimeType, MimeGroup group) {
        this.path = path;
        this.sizeBytes = sizeBytes;
        this.detectedMimeType = detectedMimeType;
        this.group = group;
    }

    public Path getPath() { return path; }
    public long getSizeBytes() { return sizeBytes; }
    public String getDetectedMimeType() { return detectedMimeType; }
    public MimeGroup getGroup() { return group; }
    public Outcome getOutcome() { return outcome; }
    public String getReason() { return reason; }

    public void markDeleted() {
        this.outcome = Outcome.DELETED;
        this.reason = null;
    }

    public void markSkipped(String reason) {
        this.outcome = Outcome.SKIPPED_REVERIFY;
        this.reason = reason;
    }

    public void markFailed(String reason) {
        this.outcome = Outcome.FAILED;
        this.reason = reason;
    }

    @Override
    public String toString() {
        return group + " " + path + " (" + sizeBytes + " bytes, " + detectedMimeType + ")";
    }
}
