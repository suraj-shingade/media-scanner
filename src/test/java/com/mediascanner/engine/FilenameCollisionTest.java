package com.mediascanner.engine;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

import static org.assertj.core.api.Assertions.*;

class FilenameCollisionTest {

    @TempDir Path targetDir;
    FileTransfer transfer;

    @BeforeEach
    void setUp() {
        transfer = new FileTransfer(targetDir.toString());
    }

    @Test
    void testNoConflictReturnsOriginalName() {
        Path dest = targetDir.resolve("IMG001.jpg");
        Path result = transfer.resolveCollisionFreePath(dest);
        assertThat(result.getFileName().toString()).isEqualTo("IMG001.jpg");
    }

    @Test
    void testOneConflictAppendsParens1() throws Exception {
        Files.writeString(targetDir.resolve("IMG001.jpg"), "exists");
        Path result = transfer.resolveCollisionFreePath(targetDir.resolve("IMG001.jpg"));
        assertThat(result.getFileName().toString()).isEqualTo("IMG001(1).jpg");
    }

    @Test
    void testTwoConflictsAppendsParens2() throws Exception {
        Files.writeString(targetDir.resolve("IMG001.jpg"), "exists");
        Files.writeString(targetDir.resolve("IMG001(1).jpg"), "exists");
        Path result = transfer.resolveCollisionFreePath(targetDir.resolve("IMG001.jpg"));
        assertThat(result.getFileName().toString()).isEqualTo("IMG001(2).jpg");
    }

    @Test
    void testFileWithoutExtension() throws Exception {
        Files.writeString(targetDir.resolve("myfile"), "exists");
        Path result = transfer.resolveCollisionFreePath(targetDir.resolve("myfile"));
        assertThat(result.getFileName().toString()).isEqualTo("myfile(1)");
    }

    @Test
    void testHighCollisionCount() throws Exception {
        for (int i = 0; i < 5; i++) {
            String name = i == 0 ? "IMG001.jpg" : "IMG001(" + i + ").jpg";
            Files.writeString(targetDir.resolve(name), "exists");
        }
        Path result = transfer.resolveCollisionFreePath(targetDir.resolve("IMG001.jpg"));
        assertThat(result.getFileName().toString()).isEqualTo("IMG001(5).jpg");
    }
}
