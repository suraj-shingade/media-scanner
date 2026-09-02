package com.mediascanner.engine;

import com.mediascanner.model.CleanupCandidate;
import com.mediascanner.model.CleanupRun;
import com.mediascanner.model.MimeGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static com.mediascanner.engine.ContentClassifierTest.writeApk;
import static com.mediascanner.engine.ContentClassifierTest.writeExecutable;
import static com.mediascanner.engine.ContentClassifierTest.writeJpeg;
import static com.mediascanner.engine.ContentClassifierTest.writeZip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end behaviour of the Cleanup engine, focused on the Constitution IX invariants.
 *
 * <p>These are the tests that would have to fail before a user loses a photo, so they are written to
 * be blunt: build a real tree, run the real engine, then assert against the real filesystem.
 */
class CleanupEngineIT {

    private final CleanupEngine engine = new CleanupEngine();

    /** Builds the standard mixed tree used by most cases below. */
    private Tree standardTree(Path root) throws IOException {
        Tree t = new Tree();
        t.jpeg = writeJpeg(root.resolve("photo.jpg"));
        t.jpegNamedExe = writeJpeg(root.resolve("nested/payload.exe"));
        t.exe = writeExecutable(root.resolve("installer.exe"));
        t.exeNamedJpg = writeExecutable(root.resolve("nested/holiday.jpg"));
        t.apk = writeApk(root.resolve("app.apk"));
        t.zip = writeZip(root.resolve("archive.zip"), "a.txt");
        t.doc = Files.write(root.resolve("notes.txt"), "hello".getBytes());
        return t;
    }

    static class Tree {
        Path jpeg, jpegNamedExe, exe, exeNamedJpg, apk, zip, doc;
    }

    // --------------------------------------------------------- US1: analysis

    @Test
    void analysisMutatesNothing(@TempDir Path root) throws Exception {
        standardTree(root);
        String before = hashTree(root);

        CleanupRun run = engine.analyze(root, null);

        assertThat(run.totalFiles()).isEqualTo(7);
        assertThat(hashTree(root))
            .as("analysis must be strictly read-only")
            .isEqualTo(before);
    }

    @Test
    void analysisGroupsByContent(@TempDir Path root) throws Exception {
        Tree t = standardTree(root);
        CleanupRun run = engine.analyze(root, null);

        assertThat(pathsIn(run, MimeGroup.PROTECTED_MEDIA))
            .containsExactlyInAnyOrder(t.jpeg, t.jpegNamedExe);
        assertThat(pathsIn(run, MimeGroup.EXECUTABLE))
            .containsExactlyInAnyOrder(t.exe, t.exeNamedJpg);
        assertThat(pathsIn(run, MimeGroup.ANDROID_PACKAGE)).containsExactly(t.apk);
        assertThat(pathsIn(run, MimeGroup.ARCHIVE)).containsExactly(t.zip);
    }

    @Test
    void refusesDangerousRoots(@TempDir Path root) {
        Path driveRoot = root.getRoot();
        assertThatThrownBy(() -> engine.analyze(driveRoot, null))
            .isInstanceOf(IllegalArgumentException.class);

        Path home = Path.of(System.getProperty("user.home"));
        assertThatThrownBy(() -> engine.analyze(home, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("profile root");
    }

    // ----------------------------------------------------------- US2: delete

    /**
     * Quality Gate G7. If this test ever fails, the feature is unshippable: it means a confirmed
     * deletion can destroy the archive the whole product exists to protect.
     */
    @Test
    void protectedMediaSurvivesConfirmedDeletion(@TempDir Path root) throws Exception {
        Tree t = standardTree(root);
        CleanupRun run = engine.analyze(root, null);

        // The most aggressive selection a user could possibly make.
        engine.delete(run, EnumSet.copyOf(MimeGroup.deletableGroups()));

        assertThat(t.jpeg).exists();
        assertThat(t.jpegNamedExe)
            .as("a real photo named .exe must survive even a delete-everything selection")
            .exists();
        assertThat(t.exe).doesNotExist();
        assertThat(t.exeNamedJpg)
            .as("an executable named .jpg must not be shielded by its name")
            .doesNotExist();
    }

    @Test
    void protectedMediaCannotEvenBeRequested(@TempDir Path root) throws Exception {
        standardTree(root);
        CleanupRun run = engine.analyze(root, null);

        assertThatThrownBy(() -> engine.delete(run, EnumSet.of(MimeGroup.PROTECTED_MEDIA)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("never be deleted");
    }

    @Test
    void deletesOnlySelectedGroups(@TempDir Path root) throws Exception {
        Tree t = standardTree(root);
        CleanupRun run = engine.analyze(root, null);

        CleanupEngine.DeleteResult result =
            engine.delete(run, EnumSet.of(MimeGroup.EXECUTABLE));

        assertThat(result.deletedCount()).isEqualTo(2);
        assertThat(t.exe).doesNotExist();
        assertThat(t.exeNamedJpg).doesNotExist();
        // Everything in an unticked group is untouched.
        assertThat(t.apk).exists();
        assertThat(t.zip).exists();
        assertThat(t.doc).exists();
        assertThat(t.jpeg).exists();
    }

    @Test
    void reverifySkipsAFileThatChangedSincePreview(@TempDir Path root) throws Exception {
        Tree t = standardTree(root);
        CleanupRun run = engine.analyze(root, null);

        // Between preview and confirmation the user replaces an executable with a photo.
        Files.delete(t.exe);
        writeJpeg(t.exe);

        CleanupEngine.DeleteResult result = engine.delete(run, EnumSet.of(MimeGroup.EXECUTABLE));

        assertThat(t.exe)
            .as("the file is now a photo; the stale preview must not authorise deleting it")
            .exists();
        assertThat(result.getSkipped()).hasSize(1);
        assertThat(result.getSkipped().get(0).getReason()).contains("Contents changed");
        assertThat(result.getDeleted()).hasSize(1); // the other executable still went
    }

    @Test
    void skipsAFileThatDisappearedSincePreview(@TempDir Path root) throws Exception {
        Tree t = standardTree(root);
        CleanupRun run = engine.analyze(root, null);
        Files.delete(t.exe);

        CleanupEngine.DeleteResult result = engine.delete(run, EnumSet.of(MimeGroup.EXECUTABLE));

        assertThat(result.getSkipped()).hasSize(1);
        assertThat(result.getSkipped().get(0).getReason()).contains("no longer exists");
        assertThat(result.getDeleted()).hasSize(1);
    }

    @Test
    void continuesPastAnUndeletableFile(@TempDir Path root) throws Exception {
        Tree t = standardTree(root);
        CleanupRun run = engine.analyze(root, null);

        // Hold the file open. On Windows this makes deletion fail outright; on POSIX it does not,
        // so the assertion below accepts either a recorded failure or a successful delete - what
        // must never happen is the run aborting and leaving the other file behind.
        try (var ignored = Files.newByteChannel(t.exe,
                java.nio.file.StandardOpenOption.READ)) {
            CleanupEngine.DeleteResult result = engine.delete(run, EnumSet.of(MimeGroup.EXECUTABLE));

            assertThat(result.getDeleted().size() + result.getFailed().size())
                .as("every selected candidate must be accounted for, none silently dropped")
                .isEqualTo(2);
            assertThat(t.exeNamedJpg)
                .as("a failure on one file must not stop the run")
                .doesNotExist();
        }
    }

    @Test
    void deletingNothingWhenNoGroupSelected(@TempDir Path root) throws Exception {
        Tree t = standardTree(root);
        CleanupRun run = engine.analyze(root, null);

        CleanupEngine.DeleteResult result = engine.delete(run, EnumSet.noneOf(MimeGroup.class));

        assertThat(result.deletedCount()).isZero();
        assertThat(t.exe).exists();
    }

    // ------------------------------------------------------------ US3: prune

    @Test
    void prunesNestedEmptyChainInOnePass(@TempDir Path root) throws Exception {
        Path c = Files.createDirectories(root.resolve("a/b/c"));
        Path withFile = Files.createDirectories(root.resolve("d"));
        Files.write(withFile.resolve("photo.jpg"), "x".getBytes());
        Path withEmptyFile = Files.createDirectories(root.resolve("e"));
        Files.createFile(withEmptyFile.resolve("zero.bin"));

        List<Path> preview = engine.findEmptyDirectories(root);
        assertThat(preview).contains(c, root.resolve("a/b"), root.resolve("a"));
        assertThat(preview).doesNotContain(withFile, withEmptyFile);

        CleanupEngine.PruneResult result = engine.pruneEmptyDirectories(root);

        assertThat(root.resolve("a")).doesNotExist();
        assertThat(root.resolve("a/b")).doesNotExist();
        assertThat(c).doesNotExist();
        assertThat(withFile).exists();
        assertThat(withEmptyFile)
            .as("a zero-byte file is still a file, so its folder is not empty")
            .exists();
        assertThat(result.removedCount()).isEqualTo(3);
    }

    @Test
    void prunePreservesTheSelectedRoot(@TempDir Path root) {
        Path emptyRoot = root.resolve("only-child");
        try {
            Files.createDirectories(emptyRoot);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        engine.pruneEmptyDirectories(emptyRoot);

        assertThat(emptyRoot)
            .as("the root the user chose is never removed, even when it ends up empty")
            .exists();
    }

    // ----------------------------------------------------------------- utils

    private List<Path> pathsIn(CleanupRun run, MimeGroup group) {
        List<Path> paths = new ArrayList<>();
        for (CleanupCandidate c : run.inGroup(group)) {
            paths.add(c.getPath());
        }
        return paths;
    }

    /** Content hash of every file in the tree, used to prove analysis changed nothing. */
    private String hashTree(Path root) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).sorted().forEach(files::add);
        }
        for (Path f : files) {
            digest.update(root.relativize(f).toString().getBytes());
            digest.update(Files.readAllBytes(f));
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
}
