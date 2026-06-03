package com.mediascanner.engine;

import com.mediascanner.model.MediaFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

import static org.assertj.core.api.Assertions.*;

class FileValidatorTest {

    @TempDir
    Path tempDir;

    private FileValidator validator = new FileValidator(10, 100);

    private Path createFile(String name, byte[] content) throws Exception {
        Path p = tempDir.resolve(name);
        Files.write(p, content);
        return p;
    }

    @Test
    void testZeroByteFile() throws Exception {
        Path p = createFile("empty.jpg", new byte[0]);
        MediaFile mf = new MediaFile();
        mf.setExtension("jpg");
        validator.validate(mf, p);
        assertThat(mf.getValidationStatus()).isEqualTo(MediaFile.ValidationStatus.SKIPPED);
        assertThat(mf.getSkipReason()).isEqualTo(MediaFile.SkipReason.EMPTY_FILE);
    }

    @Test
    void testSmallImageBelowThreshold() throws Exception {
        byte[] content = new byte[5 * 1024]; // 5 KB, threshold is 10 KB
        Path p = createFile("small.jpg", content);
        MediaFile mf = new MediaFile();
        mf.setExtension("jpg");
        validator.validate(mf, p);
        assertThat(mf.getValidationStatus()).isEqualTo(MediaFile.ValidationStatus.SKIPPED);
        assertThat(mf.getSkipReason()).isEqualTo(MediaFile.SkipReason.SMALL_FILE);
    }

    @Test
    void testSmallImageAboveCustomThreshold() throws Exception {
        byte[] content = new byte[5 * 1024]; // 5 KB
        Path p = createFile("ok.jpg", content);
        FileValidator customValidator = new FileValidator(4, 100); // 4 KB threshold
        MediaFile mf = new MediaFile();
        mf.setExtension("jpg");
        customValidator.validate(mf, p);
        // Can't get VALID without real JPEG, but should pass size gate
        assertThat(mf.getSkipReason()).isNotEqualTo(MediaFile.SkipReason.SMALL_FILE);
    }

    @Test
    void testSmallVideoBelowThreshold() throws Exception {
        byte[] content = new byte[50 * 1024]; // 50 KB, threshold is 100 KB
        Path p = createFile("small.mp4", content);
        MediaFile mf = new MediaFile();
        mf.setExtension("mp4");
        validator.validate(mf, p);
        assertThat(mf.getValidationStatus()).isEqualTo(MediaFile.ValidationStatus.SKIPPED);
        assertThat(mf.getSkipReason()).isEqualTo(MediaFile.SkipReason.SMALL_FILE);
    }

    @Test
    void testFileSizeCaptured() throws Exception {
        byte[] content = new byte[20 * 1024]; // 20 KB
        Path p = createFile("test.jpg", content);
        MediaFile mf = new MediaFile();
        mf.setExtension("jpg");
        validator.validate(mf, p);
        assertThat(mf.getSizeBytes()).isEqualTo(20 * 1024);
    }
}
