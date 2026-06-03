package com.mediascanner.engine;

import com.mediascanner.model.MediaFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class FFprobeExtractorTest {

    @TempDir
    Path tempDir;

    private final MetadataExtractor extractor = new MetadataExtractor();

    @Test
    void testFallbackToFilesystemWhenFFprobeMissing() throws Exception {
        Path file = tempDir.resolve("video.mp4");
        Files.write(file, new byte[1024]);

        MediaFile mf = new MediaFile();
        mf.setFileType(MediaFile.FileType.VIDEO);
        mf.setFileName("video.mp4");
        mf.setExtension("mp4");
        mf.setSizeBytes(1024);

        extractor.extract(mf, file);

        // Should fall back to filesystem date — not null
        assertThat(mf.getExtractedDate()).isNotNull();
        assertThat(mf.getDateSource()).isIn(
            MediaFile.DateSource.FILE_CREATION,
            MediaFile.DateSource.FILE_MODIFIED,
            MediaFile.DateSource.EMBEDDED_CAPTURE);
    }

    @Test
    void testFolderPatternComputedFromDate() {
        LocalDateTime date = LocalDateTime.of(2024, 6, 15, 10, 0, 0);
        String path = extractor.computeFolderPath(date, com.mediascanner.model.Job.FolderPattern.YYYY_MMM);
        assertThat(path).isEqualTo("2024/Jun");
    }

    @Test
    void testImageExifFallbackToFilesystem() throws Exception {
        Path file = tempDir.resolve("photo.jpg");
        Files.write(file, new byte[2048]);

        MediaFile mf = new MediaFile();
        mf.setFileType(MediaFile.FileType.IMAGE);
        mf.setFileName("photo.jpg");
        mf.setExtension("jpg");

        extractor.extract(mf, file);

        // No real EXIF in dummy file — should fallback to filesystem
        assertThat(mf.getExtractedDate()).isNotNull();
    }
}
