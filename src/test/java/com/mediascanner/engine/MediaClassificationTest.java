package com.mediascanner.engine;

import com.mediascanner.model.MediaFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Paths;
import java.util.Collections;

import static org.assertj.core.api.Assertions.*;

class MediaClassificationTest {

    private final FileScanner scanner = new FileScanner(Collections.emptyList());

    @ParameterizedTest
    @ValueSource(strings = {"jpg","jpeg","png","gif","webp","bmp","tif","tiff","heic","raw","cr2","nef","arw","dng"})
    void testImageExtensions(String ext) {
        MediaFile.FileType type = scanner.classifyMediaType(Paths.get("file." + ext));
        assertThat(type).isEqualTo(MediaFile.FileType.IMAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mp4","mov","avi","mkv","webm","mts","m4v","3gp"})
    void testVideoExtensions(String ext) {
        MediaFile.FileType type = scanner.classifyMediaType(Paths.get("file." + ext));
        assertThat(type).isEqualTo(MediaFile.FileType.VIDEO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pdf","docx","xlsx","txt","zip","mp3","wav"})
    void testUnsupportedExtensions(String ext) {
        MediaFile.FileType type = scanner.classifyMediaType(Paths.get("file." + ext));
        assertThat(type).isNull();
    }

    @Test
    void testUppercaseExtensionClassified() {
        MediaFile.FileType type = scanner.classifyMediaType(Paths.get("PHOTO.JPG"));
        assertThat(type).isEqualTo(MediaFile.FileType.IMAGE);
    }

    @Test
    void testNoExtensionReturnsNull() {
        MediaFile.FileType type = scanner.classifyMediaType(Paths.get("noextension"));
        assertThat(type).isNull();
    }
}
