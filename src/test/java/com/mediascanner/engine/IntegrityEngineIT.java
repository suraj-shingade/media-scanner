package com.mediascanner.engine;

import com.mediascanner.db.Database;
import com.mediascanner.db.HashIndexDao;
import com.mediascanner.model.IntegrityFinding;
import com.mediascanner.model.IntegrityRun;
import com.mediascanner.model.IntegrityStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verification of an archive against the hashes recorded when it was written.
 *
 * <p>The case the whole feature exists for is
 * {@link #testDetectsAFileAlteredInPlaceWithoutChangingItsLength()} — silent corruption is the only
 * failure mode that cannot be seen any other way, and it is the one a size check misses.
 *
 * <p>Trees live under {@code target/}, not the system temp directory, for the reason recorded in
 * {@code CleanupEngineIT}: {@code @TempDir} resolves under {@code /var} on macOS.
 */
class IntegrityEngineIT {

    private Path archive;
    private Path dbDir;
    private Database db;
    private HashIndexDao dao;
    private IntegrityEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        Path base = Paths.get("target", "integrity-it").toAbsolutePath();
        Files.createDirectories(base);
        archive = Files.createTempDirectory(base, "archive-");
        dbDir = Files.createTempDirectory(base, "db-");

        db = new Database(dbDir.resolve("integrity.db"));
        dao = new HashIndexDao(db);
        engine = new IntegrityEngine(dao);
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
        deleteTree(archive);
        deleteTree(dbDir);
    }

    private void deleteTree(Path root) {
        if (root == null) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    /** Writes a file into the archive and records it as transferred, exactly as a real job would. */
    private Path archiveFile(String name, String content) throws Exception {
        Path file = archive.resolve(name);
        Files.createDirectories(file.getParent() == null ? archive : file.getParent());
        Files.writeString(file, content);
        String hash = IntegrityEngine.sha256Of(file);
        dao.claimCanonical(hash, file.toString());
        dao.recordCanonicalDestination(hash, file.toString(), Files.size(file));
        return file;
    }

    private IntegrityFinding findingFor(IntegrityRun run, Path path) {
        return run.getFindings().stream()
            .filter(f -> f.getPath().toAbsolutePath().normalize()
                          .equals(path.toAbsolutePath().normalize()))
            .findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ intact

    @Test
    void testAnUntouchedArchiveVerifiesClean() throws Exception {
        archiveFile("holiday.jpg", "photo bytes");
        archiveFile("clip.mp4", "video bytes");

        IntegrityRun run = engine.verify(archive, IntegrityRun.Mode.DEEP, null);

        assertThat(run.countOf(IntegrityStatus.INTACT)).isEqualTo(2);
        assertThat(run.problemCount()).isZero();
        assertThat(run.isClean()).isTrue();
        assertThat(run.getFindings()).as("intact files are counted, not retained").isEmpty();
    }

    // ------------------------------------------------------------ the core case

    /**
     * The reason deep mode exists. Same length, different bytes — invisible to every check except a
     * re-hash, and the exact signature of bit rot and of a silent re-encode.
     */
    @Test
    void testDetectsAFileAlteredInPlaceWithoutChangingItsLength() throws Exception {
        Path file = archiveFile("holiday.jpg", "original");

        // Same length, different bytes — so a size check cannot possibly catch it.
        Files.writeString(file, "ORIGINAL");
        assertThat(Files.size(file)).isEqualTo(8);

        IntegrityRun run = engine.verify(archive, IntegrityRun.Mode.DEEP, null);

        IntegrityFinding finding = findingFor(run, file);
        assertThat(finding).isNotNull();
        assertThat(finding.getStatus()).isEqualTo(IntegrityStatus.CORRUPTED);
        assertThat(finding.getExpectedHash()).isNotEqualTo(finding.getObservedHash());
        assertThat(run.isClean()).isFalse();
    }

    /** The same corruption is invisible to quick mode, and quick mode must not claim otherwise. */
    @Test
    void testQuickModeCannotSeeContentChangeAndSaysSo() throws Exception {
        Path file = archiveFile("holiday.jpg", "original");
        Files.writeString(file, "ORIGINAL");

        IntegrityRun quick = engine.verify(archive, IntegrityRun.Mode.QUICK, null);

        assertThat(quick.countOf(IntegrityStatus.INTACT)).isEqualTo(1);
        assertThat(quick.problemCount()).isZero();
        assertThat(quick.getMode()).isEqualTo(IntegrityRun.Mode.QUICK);
        assertThat(quick.getBytesRead()).as("quick mode reads no file content").isZero();
    }

    // ---------------------------------------------------------- other verdicts

    @Test
    void testDetectsAMissingFile() throws Exception {
        Path file = archiveFile("gone.jpg", "bytes");
        Files.delete(file);

        IntegrityRun run = engine.verify(archive, IntegrityRun.Mode.DEEP, null);

        assertThat(findingFor(run, file).getStatus()).isEqualTo(IntegrityStatus.MISSING);
    }

    @Test
    void testDetectsATruncatedFileWithoutHashing() throws Exception {
        Path file = archiveFile("truncated.jpg", "the full original contents");
        Files.writeString(file, "the full");

        IntegrityRun run = engine.verify(archive, IntegrityRun.Mode.QUICK, null);

        IntegrityFinding finding = findingFor(run, file);
        assertThat(finding.getStatus()).isEqualTo(IntegrityStatus.RESIZED);
        assertThat(finding.getObservedSize()).isEqualTo(8L);
        assertThat(finding.getExpectedSize()).isEqualTo(26L);
    }

    @Test
    void testReportsArchiveFilesWithNoRecordAsUntrackedNotAsFailures() throws Exception {
        archiveFile("tracked.jpg", "bytes");
        Path stranger = archive.resolve("dropped-in-by-hand.jpg");
        Files.writeString(stranger, "not ours");

        IntegrityRun run = engine.verify(archive, IntegrityRun.Mode.DEEP, null);

        assertThat(findingFor(run, stranger).getStatus()).isEqualTo(IntegrityStatus.UNTRACKED);
        assertThat(run.problemCount()).as("an untracked file is not a problem").isZero();
        assertThat(run.countOf(IntegrityStatus.INTACT)).isEqualTo(1);
    }

    @Test
    void testARecordWithNoDestinationIsUnverifiableNotMissing() throws Exception {
        // A pre-V003 row: claimed, but no destination was ever recorded.
        dao.claimCanonical("deadbeef", archive.resolve("legacy.jpg").toString());

        IntegrityRun run = engine.verify(archive, IntegrityRun.Mode.DEEP, null);

        assertThat(run.countOf(IntegrityStatus.UNVERIFIABLE)).isEqualTo(1);
        assertThat(run.countOf(IntegrityStatus.MISSING))
            .as("a gap in our own records is not evidence of data loss").isZero();
        assertThat(run.problemCount()).isZero();
    }

    @Test
    void testTheApplicationsOwnReportsAreNotReportedAsUntracked() throws Exception {
        archiveFile("tracked.jpg", "bytes");
        Path reports = archive.resolve("_skipped");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("skipped-report.json"), "{}");

        IntegrityRun run = engine.verify(archive, IntegrityRun.Mode.DEEP, null);

        assertThat(run.countOf(IntegrityStatus.UNTRACKED)).isZero();
    }

    // ------------------------------------------------------- the read-only rule

    /**
     * FR-069 and FR-070. The engine must be incapable of changing the thing it is inspecting — and of
     * changing the record it is inspecting against, which would destroy the evidence for next time.
     */
    @Test
    void testVerificationLeavesTheArchiveAndTheIndexUntouched() throws Exception {
        Path a = archiveFile("one.jpg", "first");
        Path b = archiveFile("two.jpg", "second");
        String before = treeFingerprint(archive);
        String hashBefore = IntegrityEngine.sha256Of(a);

        engine.verify(archive, IntegrityRun.Mode.DEEP, null);

        assertThat(treeFingerprint(archive)).as("archive is byte-identical").isEqualTo(before);
        assertThat(IntegrityEngine.sha256Of(a)).isEqualTo(hashBefore);
        assertThat(Files.exists(b)).isTrue();

        // The recorded destination must still be there for the next run to check against.
        String recorded = dao.findCanonicalPath(IntegrityEngine.sha256Of(a));
        assertThat(recorded).isEqualTo(a.toString());
    }

    private String treeFingerprint(Path root) throws Exception {
        List<String> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted().toList()) {
                if (Files.isRegularFile(p)) {
                    entries.add(root.relativize(p) + ":" + Files.size(p) + ":"
                              + IntegrityEngine.sha256Of(p));
                }
            }
        }
        return String.join("|", entries);
    }

    // ------------------------------------------------------- progress & cancel

    @Test
    void testReportsProgressAsItGoes() throws Exception {
        for (int i = 0; i < 4; i++) {
            archiveFile("file" + i + ".jpg", "contents " + i);
        }

        List<IntegrityEngine.Progress> updates = new ArrayList<>();
        engine.verify(archive, IntegrityRun.Mode.DEEP, updates::add);

        assertThat(updates).isNotEmpty();
        assertThat(updates.get(updates.size() - 1).checked()).isGreaterThanOrEqualTo(4);
        for (int i = 1; i < updates.size(); i++) {
            assertThat(updates.get(i).bytesRead())
                .isGreaterThanOrEqualTo(updates.get(i - 1).bytesRead());
        }
    }

    @Test
    void testACancelledRunIsNeverReportedAsClean() throws Exception {
        for (int i = 0; i < 6; i++) {
            archiveFile("file" + i + ".jpg", "contents " + i);
        }

        IntegrityRun run = engine.verify(archive, IntegrityRun.Mode.DEEP,
            progress -> engine.cancel());

        assertThat(run.wasCancelled()).isTrue();
        assertThat(run.isClean())
            .as("a partial pass must never read as proof of integrity").isFalse();
    }

    @Test
    void testAnEmptyArchiveCompletesWithNothingVerified() throws Exception {
        IntegrityRun run = engine.verify(archive, IntegrityRun.Mode.DEEP, null);

        assertThat(run.verifiedCount()).isZero();
        assertThat(run.isClean()).isTrue();
        assertThat(run.wasCancelled()).isFalse();
    }
}
