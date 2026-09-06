package com.mediascanner.engine;

import com.mediascanner.db.HashIndexDao;
import com.mediascanner.model.IntegrityFinding;
import com.mediascanner.model.IntegrityRun;
import com.mediascanner.model.IntegrityStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Checks that an archive still contains what MediaScanner recorded writing into it.
 *
 * <p>This engine is the mirror of {@link CleanupEngine}: that one exists to remove files and is
 * hedged about with confirmations, and this one must be structurally incapable of changing anything
 * (FR-069, FR-070). It opens files for reading only, never writes to the database, and holds no
 * mutable state that outlives a run.
 *
 * <p><b>It deliberately does not use {@link HashEngine}.</b> That class is cache-aware: it consults
 * {@code FILE_HASH_INDEX} and returns the stored digest when size and mtime are unchanged, then writes
 * its result back. Both halves are wrong here. Reading the cache would return the value recorded at
 * transfer time — the very number being tested — and report every file intact while verifying nothing;
 * writing back would overwrite the evidence. Verification computes its own digest from the bytes on
 * disk, every time (FR-067).
 */
public class IntegrityEngine {

    private static final Logger log = LoggerFactory.getLogger(IntegrityEngine.class);

    private static final DateTimeFormatter RUN_ID_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final int CHUNK_SIZE = 8 * 1024 * 1024;   // 8 MB, matching HashEngine

    private final HashIndexDao hashIndexDao;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public IntegrityEngine(HashIndexDao hashIndexDao) {
        this.hashIndexDao = hashIndexDao;
    }

    /** Progress of a verification pass, reported as it happens (FR-071). */
    public record Progress(int checked, int total, long bytesRead,
                           IntegrityStatus last, Path current) {}

    public void cancel() { cancelled.set(true); }

    public void resetCancel() { cancelled.set(false); }

    public boolean isCancelled() { return cancelled.get(); }

    /**
     * Verifies every recorded transfer against {@code archiveRoot}, then reports archive files that
     * have no record (FR-064, FR-068).
     *
     * @param mode       {@link IntegrityRun.Mode#QUICK} checks existence and size only;
     *                   {@link IntegrityRun.Mode#DEEP} re-hashes every file
     * @param onProgress may be null
     */
    public IntegrityRun verify(Path archiveRoot, IntegrityRun.Mode mode,
                               Consumer<Progress> onProgress) throws SQLException {
        resetCancel();

        IntegrityRun run = new IntegrityRun(
            "VERIFY-" + LocalDateTime.now().format(RUN_ID_FORMAT),
            archiveRoot, mode, Instant.now());

        // Destinations we accounted for, so the untracked sweep can subtract them. This is the one
        // structure that grows with the archive; it holds paths only, not findings.
        Set<Path> accountedFor = new HashSet<>();
        int[] checked = {0};

        int total = hashIndexDao.forEachTransferred(entry -> {
            if (cancelled.get()) return;

            if (entry.destinationPath() == null) {
                run.record(IntegrityFinding.unverifiable(
                    Paths.get(entry.canonicalPath()), entry.sha256Hash()));
                return;
            }

            Path destination = Paths.get(entry.destinationPath());
            accountedFor.add(destination.toAbsolutePath().normalize());

            IntegrityFinding finding = checkOne(destination, entry.destinationSize(),
                                                entry.sha256Hash(), mode, run);
            run.record(finding);
            checked[0]++;

            if (onProgress != null) {
                onProgress.accept(new Progress(
                    checked[0], -1, run.getBytesRead(), finding.getStatus(), destination));
            }
        });

        if (!cancelled.get()) {
            sweepForUntracked(archiveRoot, accountedFor, run, onProgress);
        }

        if (cancelled.get()) {
            run.markCancelled();
        }
        run.markFinished(Instant.now());

        log.info("Integrity {} of {}: {} verified, {} problems, {} untracked, {} bytes read{}",
            mode, archiveRoot, run.verifiedCount(), run.problemCount(),
            run.countOf(IntegrityStatus.UNTRACKED), run.getBytesRead(),
            run.wasCancelled() ? " (cancelled)" : "");
        return run;
    }

    /**
     * The verdict for one recorded file.
     *
     * <p>Ordering matters. Existence, then size, then contents — each check is cheaper than the next
     * and each makes the next meaningful. Size is also the reason quick mode is worth having at all:
     * a truncated file is caught without reading a byte.
     */
    private IntegrityFinding checkOne(Path path, long expectedSize, String expectedHash,
                                      IntegrityRun.Mode mode, IntegrityRun run) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return IntegrityFinding.missing(path, expectedSize, expectedHash);
        }

        long observedSize;
        try {
            observedSize = Files.size(path);
        } catch (IOException e) {
            return IntegrityFinding.unreadable(path, expectedSize, expectedHash, describe(e));
        }

        if (observedSize != expectedSize) {
            return IntegrityFinding.resized(path, expectedSize, observedSize, expectedHash);
        }

        if (mode == IntegrityRun.Mode.QUICK) {
            // Right size is all quick mode claims to have established. The run records its mode so
            // this cannot later be read as proof of content (FR-066).
            return IntegrityFinding.intact(path, expectedSize, expectedHash);
        }

        String observedHash;
        try {
            observedHash = sha256Of(path);
            run.addBytesRead(observedSize);
        } catch (Exception e) {
            return IntegrityFinding.unreadable(path, expectedSize, expectedHash, describe(e));
        }

        return observedHash.equalsIgnoreCase(expectedHash)
            ? IntegrityFinding.intact(path, expectedSize, expectedHash)
            : IntegrityFinding.corrupted(path, expectedSize, expectedHash, observedHash);
    }

    /**
     * Reports archive files with no transfer record (FR-068).
     *
     * <p>Not a failure — a user may have put them there deliberately — but a verification that only
     * checked its own records could not tell a complete archive from a half-empty one.
     */
    private void sweepForUntracked(Path archiveRoot, Set<Path> accountedFor,
                                   IntegrityRun run, Consumer<Progress> onProgress) {
        if (archiveRoot == null || !Files.isDirectory(archiveRoot)) return;

        try (java.util.stream.Stream<Path> walk = Files.walk(archiveRoot)) {
            walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                .forEach(p -> {
                    if (cancelled.get()) return;
                    Path normalised = p.toAbsolutePath().normalize();
                    if (accountedFor.contains(normalised)) return;
                    if (isReportArtifact(archiveRoot, normalised)) return;

                    long size;
                    try {
                        size = Files.size(p);
                    } catch (IOException e) {
                        size = 0L;
                    }
                    run.record(IntegrityFinding.untracked(p, size));
                    if (onProgress != null) {
                        onProgress.accept(new Progress(
                            run.verifiedCount(), -1, run.getBytesRead(),
                            IntegrityStatus.UNTRACKED, p));
                    }
                });
        } catch (IOException e) {
            // A tree we cannot fully walk still yields a valid verification of what we did check.
            log.warn("Could not complete the untracked sweep of {}: {}", archiveRoot, e.toString());
        }
    }

    /**
     * True for the report directories MediaScanner writes into the archive itself.
     *
     * <p>Without this, every job's own skip and failure reports would be reported as untracked on the
     * next verification — technically true and completely useless.
     */
    private boolean isReportArtifact(Path archiveRoot, Path file) {
        Path relative = archiveRoot.toAbsolutePath().normalize().relativize(file);
        for (Path segment : relative) {
            String name = segment.toString();
            if (name.equals("_skipped") || name.equals("_failures") || name.equals("_duplicates")) {
                return true;
            }
        }
        return false;
    }

    /**
     * SHA-256 of the bytes on disk, computed fresh every time.
     *
     * <p>Deliberately not routed through {@link HashEngine} — see the class comment. This method reads
     * and nothing else.
     */
    public static String sha256Of(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private String describe(Exception e) {
        String message = e.getMessage();
        String type = e.getClass().getSimpleName();
        return (message == null || message.isBlank()) ? type : type + ": " + message;
    }
}
