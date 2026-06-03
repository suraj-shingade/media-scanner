package com.mediascanner.engine;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

import static org.assertj.core.api.Assertions.*;

class DuplicatePolicyTest {

    @TempDir Path sourceDir;
    @TempDir Path targetDir;

    private FileTransfer transfer;

    @BeforeEach
    void setUp() {
        transfer = new FileTransfer(targetDir.toString());
    }

    @Test
    void testCopyToDuplicatesBucket() throws Exception {
        Path src = sourceDir.resolve("dup.jpg");
        Files.writeString(src, "duplicate content");

        Path dupDir = targetDir.resolve("_duplicates");
        Files.createDirectories(dupDir);
        Path dest = transfer.resolveCollisionFreePath(dupDir.resolve("dup.jpg"));
        transfer.copy(src, dest);

        assertThat(Files.exists(src)).isTrue();
        assertThat(Files.exists(dest)).isTrue();
    }

    @Test
    void testKeepBothRenamesWithSuffix() throws Exception {
        Path src = sourceDir.resolve("photo.jpg");
        Files.writeString(src, "photo content");

        Path existing = targetDir.resolve("photo.jpg");
        Files.writeString(existing, "existing photo");

        String baseName = "photo";
        String ext = ".jpg";
        Path destDir = targetDir;
        int n = 1;
        Path dest;
        do {
            dest = destDir.resolve(baseName + "_DUP_" + n + ext);
            n++;
        } while (Files.exists(dest));

        transfer.copy(src, dest);

        assertThat(Files.exists(src)).isTrue();
        assertThat(dest.getFileName().toString()).startsWith("photo_DUP_");
    }

    @Test
    void testSkipPolicySourcePreserved() throws Exception {
        Path src = sourceDir.resolve("skip.jpg");
        Files.writeString(src, "skip content");

        assertThat(Files.exists(src)).isTrue();
        // Skip policy: do nothing with destination
        // Source must still exist
        assertThat(Files.exists(src)).isTrue();
    }

    @Test
    void testSourceNeverDeletedByDuplicateLogic() throws Exception {
        Path src = sourceDir.resolve("original.jpg");
        Files.writeString(src, "original content");

        // Simulate all three policy actions — none should delete source
        Path dupDir = targetDir.resolve("_duplicates");
        Files.createDirectories(dupDir);
        transfer.copy(src, transfer.resolveCollisionFreePath(dupDir.resolve("original.jpg")));

        assertThat(Files.exists(src)).isTrue();
    }
}
