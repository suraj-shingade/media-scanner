package com.mediascanner.engine;

import com.mediascanner.model.MimeGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract that keeps a permanent-deletion feature safe: a file's group comes from its bytes,
 * never from its name (FR-033).
 */
class ContentClassifierTest {

    private final ContentClassifier classifier = new ContentClassifier();

    // ---------------------------------------------------------------- helpers

    static Path writeJpeg(Path path) throws IOException {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 64, 64);
        g.dispose();
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "jpg", path.toFile());
        return path;
    }

    /** A DOS/PE executable stub: 'MZ' magic is what a real .exe leads with. */
    static Path writeExecutable(Path path) throws IOException {
        byte[] mz = new byte[512];
        mz[0] = 'M';
        mz[1] = 'Z';
        mz[2] = (byte) 0x90;
        for (int i = 64; i < mz.length; i++) {
            mz[i] = (byte) (i % 251);
        }
        Files.createDirectories(path.getParent());
        Files.write(path, mz);
        return path;
    }

    static Path writeZip(Path path, String... entryNames) throws IOException {
        Files.createDirectories(path.getParent());
        try (OutputStream out = Files.newOutputStream(path);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String name : entryNames) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(("content of " + name).getBytes());
                zip.closeEntry();
            }
        }
        return path;
    }

    static Path writeApk(Path path) throws IOException {
        return writeZip(path, "AndroidManifest.xml", "classes.dex", "res/layout/main.xml");
    }

    // ------------------------------------------------------------------ tests

    @Test
    void classifiesByContentNotExtension(@TempDir Path dir) throws IOException {
        // The whole point: names lie, bytes do not.
        Path executableNamedJpg = writeExecutable(dir.resolve("holiday.jpg"));
        Path jpegNamedExe = writeJpeg(dir.resolve("payload.exe"));

        assertThat(classifier.classify(executableNamedJpg))
            .as("an executable named .jpg must not be treated as media")
            .isEqualTo(MimeGroup.EXECUTABLE);

        assertThat(classifier.classify(jpegNamedExe))
            .as("a real photo named .exe must be protected, not queued for deletion")
            .isEqualTo(MimeGroup.PROTECTED_MEDIA);
    }

    @Test
    void detectsApkByArchiveContentsNotName(@TempDir Path dir) throws IOException {
        Path apkNamedJpg = writeApk(dir.resolve("holiday.jpg"));
        Path plainZipNamedApk = writeZip(dir.resolve("photos.apk"), "a.txt", "b.txt");

        assertThat(classifier.classify(apkNamedJpg)).isEqualTo(MimeGroup.ANDROID_PACKAGE);
        assertThat(classifier.classify(plainZipNamedApk)).isEqualTo(MimeGroup.ARCHIVE);
    }

    @Test
    void everyFileGetsADefiniteGroup(@TempDir Path dir) throws IOException {
        Path empty = Files.createFile(dir.resolve("empty.bin"));
        Path noExtension = Files.write(dir.resolve("README"), "plain text".getBytes());

        // FR-036: no nulls, no silent omissions - a file missing from the preview is a file
        // nobody got to review.
        assertThat(classifier.classify(empty)).isNotNull();
        assertThat(classifier.classify(noExtension)).isNotNull();
        assertThat(classifier.detectMimeType(empty)).isEqualTo(ContentClassifier.EMPTY);
    }

    @Test
    void mediaIsAlwaysProtectedRegardlessOfGroupOrdering() {
        assertThat(MimeGroup.forMimeType("image/jpeg")).isEqualTo(MimeGroup.PROTECTED_MEDIA);
        assertThat(MimeGroup.forMimeType("video/mp4")).isEqualTo(MimeGroup.PROTECTED_MEDIA);
        assertThat(MimeGroup.PROTECTED_MEDIA.isDeletable()).isFalse();
        assertThat(MimeGroup.deletableGroups()).doesNotContain(MimeGroup.PROTECTED_MEDIA);
    }

    @Test
    void unknownTypesFallIntoOtherRatherThanVanishing() {
        assertThat(MimeGroup.forMimeType("application/x-something-nobody-has-heard-of"))
            .isEqualTo(MimeGroup.OTHER);
        assertThat(MimeGroup.forMimeType(null)).isEqualTo(MimeGroup.OTHER);
    }

    @Test
    void mimeParametersDoNotDefeatMatching() {
        assertThat(MimeGroup.forMimeType("text/plain; charset=UTF-8")).isEqualTo(MimeGroup.DOCUMENT);
        assertThat(MimeGroup.forMimeType("image/jpeg; foo=bar")).isEqualTo(MimeGroup.PROTECTED_MEDIA);
    }
}
