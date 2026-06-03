package com.mediascanner.engine;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

import static org.assertj.core.api.Assertions.*;

class FileTransferTest {

    @TempDir
    Path sourceDir;

    @TempDir
    Path targetDir;

    private FileTransfer transfer;

    @BeforeEach
    void setUp() {
        transfer = new FileTransfer(targetDir.toString());
    }

    @Test
    void testCopyPreservesSource() throws Exception {
        Path src = sourceDir.resolve("photo.jpg");
        Files.writeString(src, "test content");
        Path dest = targetDir.resolve("photo.jpg");

        transfer.copy(src, dest);

        assertThat(Files.exists(src)).isTrue();
        assertThat(Files.exists(dest)).isTrue();
        assertThat(Files.readString(dest)).isEqualTo("test content");
    }

    @Test
    void testMoveDeletesSource() throws Exception {
        Path src = sourceDir.resolve("move_me.jpg");
        Files.writeString(src, "move content");
        Path dest = targetDir.resolve("move_me.jpg");

        transfer.move(src, dest);

        assertThat(Files.exists(src)).isFalse();
        assertThat(Files.exists(dest)).isTrue();
        assertThat(Files.readString(dest)).isEqualTo("move content");
    }

    @Test
    void testPartialFileCleanupOnResume() throws Exception {
        Path src = sourceDir.resolve("big.jpg");
        Files.write(src, new byte[1000]);

        Path dest = targetDir.resolve("big.jpg");
        Files.write(dest, new byte[500]); // partial — size mismatch

        transfer.copy(src, dest);

        assertThat(Files.size(dest)).isEqualTo(1000L);
    }

    @Test
    void testCollisionFreePathNoConflict() {
        Path dest = targetDir.resolve("img001.jpg");
        Path result = transfer.resolveCollisionFreePath(dest);
        assertThat(result).isEqualTo(dest);
    }

    @Test
    void testCollisionFreePathWithConflict() throws Exception {
        Path dest = targetDir.resolve("img001.jpg");
        Files.writeString(dest, "exists");

        Path result = transfer.resolveCollisionFreePath(dest);
        assertThat(result.getFileName().toString()).isEqualTo("img001(1).jpg");
    }

    @Test
    void testCollisionFreePathMultipleConflicts() throws Exception {
        Files.writeString(targetDir.resolve("img001.jpg"), "exists");
        Files.writeString(targetDir.resolve("img001(1).jpg"), "exists");

        Path dest = targetDir.resolve("img001.jpg");
        Path result = transfer.resolveCollisionFreePath(dest);
        assertThat(result.getFileName().toString()).isEqualTo("img001(2).jpg");
    }

    @Test
    void testCopyCreatesIntermediateDirectories() throws Exception {
        Path src = sourceDir.resolve("photo.jpg");
        Files.writeString(src, "content");
        Path dest = targetDir.resolve("2024/Jan/photo.jpg");

        transfer.copy(src, dest);
        assertThat(Files.exists(dest)).isTrue();
    }
}
