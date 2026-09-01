package com.mediascanner.engine;

import com.mediascanner.model.MediaFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * FR-012 corrupt-media detection.
 *
 * <p>The header-only gate this replaces could not see any of these cases. Note in particular that
 * {@code ImageIO.read} <em>successfully decodes</em> a truncated or shredded JPEG and returns a
 * garbage image without throwing — so "did it decode" is not the test. The decoder's warnings are.
 */
class DeepValidationTest {

    @TempDir Path dir;

    private byte[] validJpeg;
    private byte[] validPng;

    @BeforeEach
    void setUp() throws Exception {
        BufferedImage img = new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(7);
        for (int x = 0; x < 300; x++) {
            for (int y = 0; y < 300; y++) img.setRGB(x, y, r.nextInt(0xFFFFFF));
        }
        ByteArrayOutputStream jb = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", jb);
        validJpeg = jb.toByteArray();

        ByteArrayOutputStream pb = new ByteArrayOutputStream();
        ImageIO.write(img, "png", pb);
        validPng = pb.toByteArray();
    }

    private MediaFile validate(String name, byte[] content, boolean deep) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, content);
        MediaFile mf = new MediaFile();
        mf.setAbsolutePath(p.toString());
        mf.setFileName(name);
        int dot = name.lastIndexOf('.');
        mf.setExtension(dot >= 0 ? name.substring(dot + 1).toLowerCase() : "");
        new FileValidator(1, 1, deep).validate(mf, p);
        return mf;
    }

    @Test
    void testValidJpegPasses() throws Exception {
        MediaFile mf = validate("good.jpg", validJpeg, true);
        assertThat(mf.getValidationStatus()).isEqualTo(MediaFile.ValidationStatus.VALID);
    }

    @Test
    void testValidPngPasses() throws Exception {
        MediaFile mf = validate("good.png", validPng, true);
        assertThat(mf.getValidationStatus()).isEqualTo(MediaFile.ValidationStatus.VALID);
    }

    /** SC-001: a truncated JPEG decodes without throwing, so only the warnings reveal it. */
    @Test
    void testTruncatedJpegFails() throws Exception {
        byte[] truncated = Arrays.copyOf(validJpeg, (int) (validJpeg.length * 0.4));
        MediaFile mf = validate("truncated.jpg", truncated, true);

        assertThat(mf.getValidationStatus()).isEqualTo(MediaFile.ValidationStatus.FAILED);
        assertThat(mf.getFailureReason()).isNotBlank();
    }

    /** A valid header with a shredded payload — the "damaged PNG / incomplete MOV" case in FR-012. */
    @Test
    void testShreddedJpegPayloadFails() throws Exception {
        byte[] shredded = validJpeg.clone();
        Random r = new Random(99);
        for (int i = validJpeg.length / 2; i < validJpeg.length; i++) {
            shredded[i] = (byte) r.nextInt(256);
        }
        MediaFile mf = validate("shredded.jpg", shredded, true);

        assertThat(mf.getValidationStatus()).isEqualTo(MediaFile.ValidationStatus.FAILED);
    }

    /** Even with the end-of-image marker restored, the corrupt scan data must still be caught. */
    @Test
    void testShreddedJpegWithIntactEndMarkerStillFails() throws Exception {
        byte[] shredded = validJpeg.clone();
        Random r = new Random(1234);
        for (int i = validJpeg.length / 2; i < validJpeg.length; i++) {
            shredded[i] = (byte) r.nextInt(256);
        }
        shredded[shredded.length - 2] = (byte) 0xFF;
        shredded[shredded.length - 1] = (byte) 0xD9;

        MediaFile mf = validate("shredded-eoi.jpg", shredded, true);
        assertThat(mf.getValidationStatus()).isEqualTo(MediaFile.ValidationStatus.FAILED);
    }

    @Test
    void testTruncatedPngFails() throws Exception {
        byte[] truncated = Arrays.copyOf(validPng, validPng.length / 3);
        MediaFile mf = validate("truncated.png", truncated, true);
        assertThat(mf.getValidationStatus()).isEqualTo(MediaFile.ValidationStatus.FAILED);
    }

    @Test
    void testTextFileWearingImageExtensionFails() throws Exception {
        // Must clear the size threshold: the small-file gate runs before the decode gate, so a
        // short bogus file is correctly reported as SMALL_FILE rather than as a failure.
        MediaFile mf = validate("fake.jpg", "not an image at all, just words. ".repeat(200).getBytes(), true);
        assertThat(mf.getValidationStatus()).isEqualTo(MediaFile.ValidationStatus.FAILED);
    }

    /**
     * SC-003 / FR-008-004: the JDK has no reader for HEIC or camera RAW. Those must fall back to
     * header validation, never be failed just because Java cannot decode them.
     */
    @Test
    void testFormatsTheJdkCannotDecodeAreNotFailed() throws Exception {
        // A plausible HEIC header ("ftypheic" box) followed by opaque payload.
        byte[] heic = new byte[4096];
        byte[] header = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};
        System.arraycopy(header, 0, heic, 0, header.length);
        new Random(3).nextBytes(Arrays.copyOfRange(heic, header.length, heic.length));

        MediaFile mf = validate("photo.heic", heic, true);
        assertThat(mf.getValidationStatus())
            .as("an undecodable-but-plausible format must not be failed for lack of a JDK reader")
            .isNotEqualTo(MediaFile.ValidationStatus.FAILED);
    }

    /** FR-008-003: with deep validation off, the old header-only behaviour applies. */
    @Test
    void testDeepValidationCanBeDisabled() throws Exception {
        byte[] truncated = Arrays.copyOf(validJpeg, (int) (validJpeg.length * 0.4));
        MediaFile mf = validate("truncated.jpg", truncated, false);

        assertThat(mf.getValidationStatus())
            .as("header-only mode accepts a truncated JPEG, as it always did")
            .isEqualTo(MediaFile.ValidationStatus.VALID);
    }

    @Test
    void testEmptyAndSmallGatesStillRunFirst() throws Exception {
        MediaFile empty = validate("empty.jpg", new byte[0], true);
        assertThat(empty.getSkipReason()).isEqualTo(MediaFile.SkipReason.EMPTY_FILE);
    }
}
