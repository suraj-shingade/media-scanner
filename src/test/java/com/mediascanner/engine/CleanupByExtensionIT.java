package com.mediascanner.engine;

import com.mediascanner.model.CleanupRun;
import com.mediascanner.model.MimeGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;

import static com.mediascanner.engine.ContentClassifierTest.writeExecutable;
import static com.mediascanner.engine.ContentClassifierTest.writeJpeg;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Extension-based selection, used by the direct-delete mode where the user names the formats to
 * remove instead of reviewing what analysis found.
 *
 * <p>The case that matters most is the last one: naming an extension must never be able to reach a
 * photo. Selection is additive, and the per-file content re-check still stands in front of every
 * deletion.
 */
class CleanupByExtensionIT {

    private final CleanupEngine engine = new CleanupEngine();

    /** Under target/, not the system temp dir — see CleanupEngineIT for why. */
    private Path root;

    @BeforeEach
    void createTree() throws IOException {
        Path base = Paths.get("target", "cleanup-ext-it").toAbsolutePath();
        Files.createDirectories(base);
        root = Files.createTempDirectory(base, "tree-");
    }

    @AfterEach
    void removeTree() {
        if (root == null) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    @Test
    void testNormaliseAcceptsTheFormsAUserWouldType() {
        assertThat(CleanupEngine.normaliseExtension("jpg")).isEqualTo("jpg");
        assertThat(CleanupEngine.normaliseExtension(".JPG")).isEqualTo("jpg");
        assertThat(CleanupEngine.normaliseExtension("*.Jpg")).isEqualTo("jpg");
        assertThat(CleanupEngine.normaliseExtension("  .tmp  ")).isEqualTo("tmp");
        assertThat(CleanupEngine.normaliseExtension(null)).isEmpty();
    }

    @Test
    void testExtensionOf() {
        assertThat(CleanupEngine.extensionOf(Paths.get("a", "b.TXT"))).isEqualTo("txt");
        assertThat(CleanupEngine.extensionOf(Paths.get("noextension"))).isEmpty();
        assertThat(CleanupEngine.extensionOf(Paths.get("trailing."))).isEmpty();
    }

    @Test
    void testDeletesFilesNamedByExtensionWithNoGroupSelected() throws Exception {
        Files.writeString(root.resolve("scratch.tmp"), "temporary");
        Files.writeString(root.resolve("run.log"), "log line");
        Files.writeString(root.resolve("keep.txt"), "keep me");

        CleanupRun run = engine.analyze(root, null);
        CleanupEngine.DeleteResult result =
            engine.delete(run, Set.of(), Set.of("tmp", "log"));

        assertThat(result.deletedCount()).isEqualTo(2);
        assertThat(root.resolve("scratch.tmp")).doesNotExist();
        assertThat(root.resolve("run.log")).doesNotExist();
        assertThat(root.resolve("keep.txt"))
            .as("an extension that was not named is untouched").exists();
    }

    @Test
    void testGroupAndExtensionSelectionsAreAdditive() throws Exception {
        writeExecutable(root.resolve("installer.exe"));
        Files.writeString(root.resolve("scratch.tmp"), "temporary");
        Files.writeString(root.resolve("notes.txt"), "keep");

        CleanupRun run = engine.analyze(root, null);
        CleanupEngine.DeleteResult result =
            engine.delete(run, Set.of(MimeGroup.EXECUTABLE), Set.of("tmp"));

        assertThat(result.deletedCount()).isEqualTo(2);
        assertThat(root.resolve("installer.exe")).doesNotExist();
        assertThat(root.resolve("scratch.tmp")).doesNotExist();
        assertThat(root.resolve("notes.txt")).exists();
    }

    /**
     * The safety property. A user typing "jpg" into the extensions box is asking to delete photos;
     * the content check refuses, whatever the name says.
     */
    @Test
    void testNamingAMediaExtensionStillDeletesNothing() throws Exception {
        writeJpeg(root.resolve("holiday.jpg"));
        writeJpeg(root.resolve("misnamed.tmp"));   // a photo wearing a deletable extension

        CleanupRun run = engine.analyze(root, null);
        CleanupEngine.DeleteResult result =
            engine.delete(run, Set.of(), Set.of("jpg", "tmp"));

        assertThat(result.deletedCount())
            .as("no photo is deletable by naming its extension").isZero();
        assertThat(root.resolve("holiday.jpg")).exists();
        assertThat(root.resolve("misnamed.tmp"))
            .as("a photo named .tmp is still a photo").exists();
        assertThat(result.getSkipped()).hasSize(2);
    }

    @Test
    void testEmptyExtensionSetFallsBackToGroupSelectionOnly() throws Exception {
        writeExecutable(root.resolve("installer.exe"));
        Files.writeString(root.resolve("scratch.tmp"), "temporary");

        CleanupRun run = engine.analyze(root, null);
        CleanupEngine.DeleteResult result =
            engine.delete(run, Set.of(MimeGroup.EXECUTABLE), Set.of());

        assertThat(result.deletedCount()).isEqualTo(1);
        assertThat(root.resolve("scratch.tmp")).exists();
    }
}
