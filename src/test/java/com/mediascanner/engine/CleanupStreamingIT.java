package com.mediascanner.engine;

import com.mediascanner.model.MimeGroup;
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
import java.util.Set;
import java.util.stream.Stream;

import static com.mediascanner.engine.ContentClassifierTest.writeExecutable;
import static com.mediascanner.engine.ContentClassifierTest.writeJpeg;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scan-and-delete in a single pass, used once the user has confirmed the criteria up front.
 *
 * <p>The safety property is unchanged and is the reason this is tested separately: deleting during
 * the walk must not weaken the per-file content check that stands in front of every removal.
 */
class CleanupStreamingIT {

    private final CleanupEngine engine = new CleanupEngine();

    /** Under target/, not the system temp dir — DangerousRoots refuses /var on macOS. */
    private Path root;

    @BeforeEach
    void createTree() throws IOException {
        Path base = Paths.get("target", "cleanup-stream-it").toAbsolutePath();
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
    void testDeletesMatchingFilesDuringTheWalk() throws Exception {
        writeExecutable(root.resolve("installer.exe"));
        Files.writeString(root.resolve("scratch.tmp"), "temporary");
        Files.writeString(root.resolve("keep.txt"), "keep me");

        CleanupEngine.StreamingResult streamed = engine.deleteWhileScanning(
            root, Set.of(MimeGroup.EXECUTABLE), Set.of("tmp"), null);

        assertThat(streamed.getResult().deletedCount()).isEqualTo(2);
        assertThat(root.resolve("installer.exe")).doesNotExist();
        assertThat(root.resolve("scratch.tmp")).doesNotExist();
        assertThat(root.resolve("keep.txt")).exists();
        assertThat(streamed.getExamined()).isEqualTo(3);
    }

    /** The guarantee that matters: deleting inline must not bypass the content check. */
    @Test
    void testPhotosSurviveEvenWhenTheirExtensionIsNamed() throws Exception {
        writeJpeg(root.resolve("holiday.jpg"));
        writeJpeg(root.resolve("disguised.tmp"));
        Files.writeString(root.resolve("real.tmp"), "temporary");

        CleanupEngine.StreamingResult streamed = engine.deleteWhileScanning(
            root, Set.of(), Set.of("jpg", "tmp"), null);

        assertThat(root.resolve("holiday.jpg")).as("named extension, still a photo").exists();
        assertThat(root.resolve("disguised.tmp")).as("a photo wearing .tmp is still a photo").exists();
        assertThat(root.resolve("real.tmp")).doesNotExist();
        assertThat(streamed.getResult().deletedCount()).isEqualTo(1);
        assertThat(streamed.getResult().getSkipped()).hasSize(2);
    }

    @Test
    void testReportsProgressAsItGoes() throws Exception {
        for (int i = 0; i < 5; i++) {
            Files.writeString(root.resolve("junk" + i + ".tmp"), "x".repeat(100));
        }

        List<CleanupEngine.DeleteProgress> updates = new ArrayList<>();
        engine.deleteWhileScanning(root, Set.of(), Set.of("tmp"), updates::add);

        assertThat(updates).as("one update per file examined").hasSize(5);
        assertThat(updates.get(updates.size() - 1).deleted()).isEqualTo(5);
        assertThat(updates.get(updates.size() - 1).bytes()).isEqualTo(500);
        // Progress must be monotonic — a counter that goes backwards makes the UI look broken.
        for (int i = 1; i < updates.size(); i++) {
            assertThat(updates.get(i).examined()).isGreaterThan(updates.get(i - 1).examined());
            assertThat(updates.get(i).deleted()).isGreaterThanOrEqualTo(updates.get(i - 1).deleted());
        }
    }

    @Test
    void testNothingSelectedDeletesNothing() throws Exception {
        Files.writeString(root.resolve("scratch.tmp"), "temporary");
        writeExecutable(root.resolve("installer.exe"));

        CleanupEngine.StreamingResult streamed =
            engine.deleteWhileScanning(root, Set.of(), Set.of(), null);

        assertThat(streamed.getResult().deletedCount()).isZero();
        assertThat(root.resolve("scratch.tmp")).exists();
        assertThat(root.resolve("installer.exe")).exists();
    }

    @Test
    void testRefusesADangerousRootBeforeTouchingAnything() {
        Path systemish = Paths.get(System.getProperty("os.name", "").toLowerCase().contains("win")
            ? "C:\\Windows" : "/usr");
        try {
            engine.deleteWhileScanning(systemish, Set.of(MimeGroup.OTHER), Set.of(), null);
            org.junit.jupiter.api.Assertions.fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("system directory");
        }
    }

    @Test
    void testProtectedGroupCannotBeRequested() {
        try {
            engine.deleteWhileScanning(root, Set.of(MimeGroup.PROTECTED_MEDIA), Set.of(), null);
            org.junit.jupiter.api.Assertions.fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("never be deleted");
        }
    }
}
