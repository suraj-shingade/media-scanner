package com.mediascanner.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One verification pass over an archive (FR-075).
 *
 * <p><b>Intact files are counted, not kept.</b> That is FR-073 and it is the reason this class exists
 * rather than a plain {@code List<IntegrityFinding>}. A healthy 10M-file archive produces 10M intact
 * findings, and holding them would exhaust the heap on exactly the archives the product is designed for —
 * while carrying no information, since "everything else was fine" is fully described by a count. Only
 * findings worth acting on are retained.
 */
public class IntegrityRun {

    /** How thoroughly the run checked (FR-066). Recorded on every result so it cannot be assumed. */
    public enum Mode {
        /** Existence and size. Fast, and blind to content change. */
        QUICK("Quick — existence and size"),
        /** Full re-hash of every file. The only mode that detects silent corruption. */
        DEEP("Deep — full re-hash");

        private final String displayName;

        Mode(String displayName) { this.displayName = displayName; }

        public String getDisplayName() { return displayName; }
    }

    private final String runId;
    private final Path archiveRoot;
    private final Mode mode;
    private final Instant startedAt;

    private final Map<IntegrityStatus, Integer> counts = new EnumMap<>(IntegrityStatus.class);
    private final List<IntegrityFinding> findings = new ArrayList<>();

    private long bytesRead;
    private boolean cancelled;
    private Instant finishedAt;

    public IntegrityRun(String runId, Path archiveRoot, Mode mode, Instant startedAt) {
        this.runId = runId;
        this.archiveRoot = archiveRoot;
        this.mode = mode;
        this.startedAt = startedAt;
        for (IntegrityStatus status : IntegrityStatus.values()) {
            counts.put(status, 0);
        }
    }

    /** Counts every finding; retains only the ones worth reporting (FR-073). */
    public void record(IntegrityFinding finding) {
        counts.merge(finding.getStatus(), 1, Integer::sum);
        if (finding.getStatus() != IntegrityStatus.INTACT) {
            findings.add(finding);
        }
    }

    public void addBytesRead(long bytes) { this.bytesRead += bytes; }

    public void markCancelled() { this.cancelled = true; }

    public void markFinished(Instant at) { this.finishedAt = at; }

    public String getRunId() { return runId; }
    public Path getArchiveRoot() { return archiveRoot; }
    public Mode getMode() { return mode; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public long getBytesRead() { return bytesRead; }

    /** True when the run was stopped early, so absence of findings proves nothing (FR-071). */
    public boolean wasCancelled() { return cancelled; }

    public int countOf(IntegrityStatus status) { return counts.getOrDefault(status, 0); }

    /** Non-intact findings only, in the order they were established. */
    public List<IntegrityFinding> getFindings() { return Collections.unmodifiableList(findings); }

    /** Files that were actually checked against a record — excludes untracked and unverifiable. */
    public int verifiedCount() {
        return countOf(IntegrityStatus.INTACT)
             + countOf(IntegrityStatus.MISSING)
             + countOf(IntegrityStatus.RESIZED)
             + countOf(IntegrityStatus.CORRUPTED)
             + countOf(IntegrityStatus.UNREADABLE);
    }

    /** Count of findings that mean something is wrong. Untracked files are not problems. */
    public int problemCount() {
        int problems = 0;
        for (Map.Entry<IntegrityStatus, Integer> entry : counts.entrySet()) {
            if (entry.getKey().isProblem()) problems += entry.getValue();
        }
        return problems;
    }

    /**
     * True only when the run completed and every checked file was intact.
     *
     * <p>A cancelled run is never clean, however few problems it found — it did not look at everything,
     * and reporting a partial pass as clean would be the most damaging thing this feature could do.
     */
    public boolean isClean() {
        return !cancelled && problemCount() == 0;
    }

    public long elapsedMillis() {
        Instant end = finishedAt != null ? finishedAt : Instant.now();
        return end.toEpochMilli() - startedAt.toEpochMilli();
    }
}
