package com.mediascanner.engine;

import com.mediascanner.model.CleanupCandidate;
import com.mediascanner.model.CleanupRun;
import com.mediascanner.model.MimeGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The Cleanup tool's engine: analyse, delete, prune.
 *
 * <p>Deliberately independent of {@link ScanEngine}. It runs no worker pool, writes no job state,
 * touches neither the hash index nor the checkpoint, and takes no SQLite connection. A destructive
 * operation should have the smallest blast radius the design allows, and coupling it to the concurrent
 * transfer pipeline would give a scan bug a route to deleting files.
 *
 * <p>Every rule of Constitution Principle IX that can be enforced in code rather than in the UI is
 * enforced here, so that the invariants hold no matter which caller is driving.
 */
public class CleanupEngine {

    private static final Logger log = LoggerFactory.getLogger(CleanupEngine.class);

    private static final DateTimeFormatter RUN_ID_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ContentClassifier classifier;
    private final CleanupScanner scanner;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public CleanupEngine() {
        this(new ContentClassifier(), new CleanupScanner());
    }

    public CleanupEngine(ContentClassifier classifier, CleanupScanner scanner) {
        this.classifier = classifier;
        this.scanner = scanner;
    }

    /** Aborts an in-flight {@link #analyze} or {@link #delete} (FR-037, FR-049). */
    public void cancel() {
        cancelled.set(true);
    }

    public void resetCancel() {
        cancelled.set(false);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    // ---------------------------------------------------------------- analyse

    /**
     * Classifies every file beneath {@code root}. Reads only; nothing on disk is modified (FR-032).
     *
     * @throws IllegalArgumentException if the directory is refused as a dangerous root (FR-048)
     */
    public CleanupRun analyze(Path root, Consumer<CleanupCandidate> onCandidate) {
        DangerousRoots.Refusal refusal = DangerousRoots.check(root);
        if (refusal != null) {
            throw new IllegalArgumentException(refusal.getReason());
        }
        resetCancel();

        CleanupRun run = new CleanupRun(
            "CLEAN-" + LocalDateTime.now().format(RUN_ID_FORMAT), root, Instant.now());

        scanner.walkFiles(root, path -> {
            CleanupCandidate candidate = classifyOne(path);
            run.add(candidate);
            if (onCandidate != null) {
                onCandidate.accept(candidate);
            }
        }, cancelled::get);

        if (cancelled.get()) {
            run.markCancelled();
            log.info("Cleanup analysis of {} cancelled after {} files", root, run.totalFiles());
        } else {
            log.info("Cleanup analysis of {} found {} files ({} bytes)",
                root, run.totalFiles(), run.totalBytes());
        }
        return run;
    }

    private CleanupCandidate classifyOne(Path path) {
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            size = 0L;
        }
        String mime = classifier.detectMimeType(path);
        return new CleanupCandidate(path, size, mime, MimeGroup.forMimeType(mime));
    }

    // ----------------------------------------------------------------- delete

    /** What a deletion pass actually did. */
    public static class DeleteResult {
        private final List<CleanupCandidate> deleted = new ArrayList<>();
        private final List<CleanupCandidate> skipped = new ArrayList<>();
        private final List<CleanupCandidate> failed = new ArrayList<>();

        public List<CleanupCandidate> getDeleted() { return deleted; }
        public List<CleanupCandidate> getSkipped() { return skipped; }
        public List<CleanupCandidate> getFailed() { return failed; }

        public int deletedCount() { return deleted.size(); }

        public long bytesDeleted() {
            long total = 0;
            for (CleanupCandidate c : deleted) total += c.getSizeBytes();
            return total;
        }
    }

    /**
     * Permanently deletes every candidate in {@code run} belonging to one of {@code groups}
     * (FR-043).
     *
     * <p>The candidates come from a {@link CleanupRun}, which only {@link #analyze} produces. That is
     * the structural half of FR-040: there is no way to delete a file that was not previewed. The
     * caller is responsible for the other half — obtaining confirmation.
     *
     * @throws IllegalArgumentException if a non-deletable group is requested (FR-045)
     */
    public DeleteResult delete(CleanupRun run, Set<MimeGroup> groups) {
        for (MimeGroup group : groups) {
            if (!group.isDeletable()) {
                throw new IllegalArgumentException(
                    "Group " + group + " can never be deleted (Constitution IX)");
            }
        }
        resetCancel();

        Set<MimeGroup> selected = groups.isEmpty()
            ? EnumSet.noneOf(MimeGroup.class)
            : EnumSet.copyOf(groups);

        DeleteResult result = new DeleteResult();
        for (CleanupCandidate candidate : run.getCandidates()) {
            if (cancelled.get()) {
                log.info("Cleanup deletion stopped by user after {} files", result.deletedCount());
                break;
            }
            if (!selected.contains(candidate.getGroup())) {
                continue;
            }
            deleteOne(candidate, result);
        }

        log.info("Cleanup deleted {} files ({} bytes); {} skipped, {} failed",
            result.deletedCount(), result.bytesDeleted(),
            result.getSkipped().size(), result.getFailed().size());
        return result;
    }

    private void deleteOne(CleanupCandidate candidate, DeleteResult result) {
        Path path = candidate.getPath();

        // Belt and braces: the group was checked above, but this is the assertion that actually
        // stands between a coding mistake and a deleted photo, so it is made per file (FR-045).
        if (!candidate.getGroup().isDeletable()) {
            candidate.markSkipped("Protected media is never deleted");
            result.getSkipped().add(candidate);
            return;
        }

        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            candidate.markSkipped("File no longer exists");
            result.getSkipped().add(candidate);
            return;
        }

        // Re-verify: the preview is a snapshot and the file may have been replaced since (FR-044).
        MimeGroup current = classifier.classify(path);
        if (current != candidate.getGroup()) {
            candidate.markSkipped(
                "Contents changed since preview: now " + current.getDisplayName());
            result.getSkipped().add(candidate);
            return;
        }

        try {
            Files.delete(path);
            candidate.markDeleted();
            result.getDeleted().add(candidate);
        } catch (IOException | SecurityException e) {
            // One undeletable file must not end the run (FR-046).
            candidate.markFailed(describeFailure(e));
            result.getFailed().add(candidate);
            log.debug("Could not delete {}: {}", path, e.toString());
        }
    }

    private String describeFailure(Exception e) {
        String message = e.getMessage();
        String type = e.getClass().getSimpleName();
        return (message == null || message.isBlank()) ? type : type + ": " + message;
    }

    // ------------------------------------------------------------------ prune

    /** What a prune pass actually did. */
    public static class PruneResult {
        private final List<Path> removed = new ArrayList<>();
        private final List<Path> failed = new ArrayList<>();

        public List<Path> getRemoved() { return removed; }
        public List<Path> getFailed() { return failed; }
        public int removedCount() { return removed.size(); }
    }

    /**
     * Lists the directories a prune would remove, without removing anything.
     *
     * <p>Evaluated post-order, and a parent counts as prunable when everything it contains is itself
     * prunable — so the preview shown to the user matches what {@link #pruneEmptyDirectories} will
     * actually do, rather than showing only the deepest level (FR-052).
     */
    public List<Path> findEmptyDirectories(Path root) {
        List<Path> candidates = scanner.directoriesDepthFirst(root);
        List<Path> prunable = new ArrayList<>();
        Set<Path> alreadyPrunable = new java.util.HashSet<>();

        for (Path dir : candidates) {
            if (wouldBeEmpty(dir, alreadyPrunable)) {
                prunable.add(dir);
                alreadyPrunable.add(dir);
            }
        }
        return prunable;
    }

    /**
     * True when {@code dir} holds nothing except directories already known to be prunable. Any file
     * at all — including a zero-byte or hidden one — makes it non-empty (FR-054).
     */
    private boolean wouldBeEmpty(Path dir, Set<Path> alreadyPrunable) {
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(child)
                        && alreadyPrunable.contains(child)) {
                    continue;
                }
                return false;
            }
            return true;
        } catch (IOException e) {
            log.debug("Could not inspect {}: {}", dir, e.getMessage());
            return false;
        }
    }

    /**
     * Removes empty directories beneath {@code root}, deepest first (FR-051).
     *
     * <p>The root itself is preserved even when it ends up empty (FR-053).
     */
    public PruneResult pruneEmptyDirectories(Path root) {
        DangerousRoots.Refusal refusal = DangerousRoots.check(root);
        if (refusal != null) {
            throw new IllegalArgumentException(refusal.getReason());
        }

        PruneResult result = new PruneResult();
        // Re-derived in post-order at removal time, so a directory emptied by this pass is seen.
        for (Path dir : scanner.directoriesDepthFirst(root)) {
            if (cancelled.get()) break;
            if (dir.equals(root)) continue;
            if (!scanner.isEmptyDirectory(dir)) continue;

            try {
                Files.delete(dir);
                result.getRemoved().add(dir);
            } catch (IOException | SecurityException e) {
                result.getFailed().add(dir);
                log.debug("Could not remove directory {}: {}", dir, e.toString());
            }
        }
        log.info("Cleanup pruned {} empty directories beneath {}", result.removedCount(), root);
        return result;
    }
}
