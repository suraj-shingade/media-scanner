package com.mediascanner.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The result of one Cleanup analysis: every file found beneath the selected root, grouped.
 *
 * <p>This object is what stands between the user and an irreversible action. {@code delete()} accepts
 * only a run produced by {@code analyze()}, which is the mechanism behind FR-040 — there is no code
 * path that removes a file the user was not shown.
 */
public class CleanupRun {

    private final String runId;
    private final Path root;
    private final Instant startedAt;
    private final List<CleanupCandidate> candidates = new ArrayList<>();
    private boolean cancelled;

    public CleanupRun(String runId, Path root, Instant startedAt) {
        this.runId = runId;
        this.root = root;
        this.startedAt = startedAt;
    }

    public String getRunId() { return runId; }
    public Path getRoot() { return root; }
    public Instant getStartedAt() { return startedAt; }
    public List<CleanupCandidate> getCandidates() { return candidates; }

    public boolean isCancelled() { return cancelled; }
    public void markCancelled() { this.cancelled = true; }

    public void add(CleanupCandidate candidate) {
        candidates.add(candidate);
    }

    /** Candidates in one group, in discovery order. */
    public List<CleanupCandidate> inGroup(MimeGroup group) {
        List<CleanupCandidate> out = new ArrayList<>();
        for (CleanupCandidate c : candidates) {
            if (c.getGroup() == group) out.add(c);
        }
        return out;
    }

    public int countIn(MimeGroup group) {
        int n = 0;
        for (CleanupCandidate c : candidates) {
            if (c.getGroup() == group) n++;
        }
        return n;
    }

    public long bytesIn(MimeGroup group) {
        long total = 0;
        for (CleanupCandidate c : candidates) {
            if (c.getGroup() == group) total += c.getSizeBytes();
        }
        return total;
    }

    /** Every group that actually has candidates, in enum order. */
    public Map<MimeGroup, Integer> groupCounts() {
        Map<MimeGroup, Integer> counts = new EnumMap<>(MimeGroup.class);
        for (CleanupCandidate c : candidates) {
            counts.merge(c.getGroup(), 1, Integer::sum);
        }
        return counts;
    }

    public int totalFiles() { return candidates.size(); }

    public long totalBytes() {
        long total = 0;
        for (CleanupCandidate c : candidates) total += c.getSizeBytes();
        return total;
    }
}
