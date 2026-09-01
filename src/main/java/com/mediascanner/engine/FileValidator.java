package com.mediascanner.engine;

import com.mediascanner.model.MediaFile;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class FileValidator {

    private static final Logger log = LoggerFactory.getLogger(FileValidator.class);

    /**
     * Formats the JDK ships a reader for. A file with one of these extensions that no reader will
     * accept is genuinely not that format. Anything else (heic, raw, cr2, nef, arw, dng, webp on
     * older JDKs) may be perfectly valid and simply undecodable here, so it is never failed for
     * that reason alone (FR-008-004).
     */
    private static final Set<String> JDK_DECODABLE =
        Set.of("jpg", "jpeg", "png", "gif", "bmp", "tif", "tiff");

    /**
     * Decoder warnings that mean the pixel data is damaged.
     *
     * <p>Matching on text is unlovely, but it is the only signal available: {@code ImageIO} decodes
     * a truncated or shredded JPEG <em>successfully</em> and returns a garbage image without
     * throwing. Only the warning stream reveals the damage. The list is deliberately narrow so an
     * unusual-but-valid file is not failed for a benign warning.
     */
    private static final List<String> CORRUPTION_WARNINGS = List.of(
        "truncated", "corrupt", "premature", "missing eoi", "bogus", "extraneous bytes");

    private static final int FFPROBE_TIMEOUT_SECONDS = 15;

    private final long imageSizeThresholdBytes;
    private final long videoSizeThresholdBytes;
    private final boolean deepValidation;
    private final Tika tika;

    public FileValidator(int imageSizeThresholdKb, int videoSizeThresholdKb) {
        this(imageSizeThresholdKb, videoSizeThresholdKb, true);
    }

    public FileValidator(int imageSizeThresholdKb, int videoSizeThresholdKb,
                         boolean deepValidation) {
        this.imageSizeThresholdBytes = (long) imageSizeThresholdKb * 1024;
        this.videoSizeThresholdBytes = (long) videoSizeThresholdKb * 1024;
        this.deepValidation = deepValidation;
        this.tika = new Tika();
    }

    public void validate(MediaFile mediaFile, Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            long size = attrs.size();
            mediaFile.setSizeBytes(size);
            mediaFile.setModificationTimestamp(attrs.lastModifiedTime().toInstant());

            // Gate 1: zero-byte
            if (size == 0) {
                skip(mediaFile, MediaFile.SkipReason.EMPTY_FILE);
                return;
            }

            String ext = mediaFile.getExtension();
            if (FileScanner.getImageExtensions().contains(ext)) {
                mediaFile.setFileType(MediaFile.FileType.IMAGE);
            } else if (FileScanner.getVideoExtensions().contains(ext)) {
                mediaFile.setFileType(MediaFile.FileType.VIDEO);
            }

            // Gate 2/3: small file
            if (mediaFile.getFileType() == MediaFile.FileType.IMAGE
                    && size < imageSizeThresholdBytes) {
                skip(mediaFile, MediaFile.SkipReason.SMALL_FILE);
                return;
            }
            if (mediaFile.getFileType() == MediaFile.FileType.VIDEO
                    && size < videoSizeThresholdBytes) {
                skip(mediaFile, MediaFile.SkipReason.SMALL_FILE);
                return;
            }

            // Gate 4: declared type must at least look like media
            try {
                String detectedType = tika.detect(path.toFile());
                if (!isValidMediaType(detectedType)) {
                    fail(mediaFile, "Unreadable media: detected type=" + detectedType);
                    return;
                }
            } catch (IOException e) {
                fail(mediaFile, "Tika detection failed: " + e.getMessage());
                return;
            }

            // Gate 5: the content actually decodes (FR-012)
            if (deepValidation) {
                String problem = deepValidate(mediaFile, path);
                if (problem != null) {
                    fail(mediaFile, problem);
                    return;
                }
            }

            mediaFile.setValidationStatus(MediaFile.ValidationStatus.VALID);

        } catch (IOException e) {
            fail(mediaFile, "IO error during validation: " + e.getMessage());
        }
    }

    /** @return a failure reason, or null when the file is sound (or cannot be checked here). */
    private String deepValidate(MediaFile mediaFile, Path path) {
        if (mediaFile.getFileType() == MediaFile.FileType.IMAGE) {
            return validateImage(path, mediaFile.getExtension());
        }
        if (mediaFile.getFileType() == MediaFile.FileType.VIDEO) {
            return validateVideo(path);
        }
        return null;
    }

    /**
     * Decodes the image and watches the decoder's warning stream.
     *
     * <p>A successful decode proves nothing on its own — the JPEG reader happily returns a 300x300
     * image for a file truncated to 40% of its bytes. The warnings are the actual evidence.
     */
    private String validateImage(Path path, String extension) {
        List<String> warnings = new ArrayList<>();
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) {
                return "Could not open image stream";
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                // No reader. For a format the JDK is expected to handle this means the content is
                // not that format; for heic/raw/etc. it just means Java cannot read it (FR-008-004).
                return JDK_DECODABLE.contains(extension)
                    ? "Not a readable " + extension.toUpperCase(Locale.ROOT) + " image"
                    : null;
            }

            ImageReader reader = readers.next();
            try {
                reader.addIIOReadWarningListener((source, warning) -> warnings.add(warning));
                reader.setInput(input, true, true);
                reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return "Image decode failed: " + e.getClass().getSimpleName()
                 + (e.getMessage() != null ? " - " + e.getMessage() : "");
        }

        for (String warning : warnings) {
            String lower = warning.toLowerCase(Locale.ROOT);
            for (String marker : CORRUPTION_WARNINGS) {
                if (lower.contains(marker)) {
                    return "Corrupt image data: " + warning;
                }
            }
        }
        return null;
    }

    /**
     * Asks ffprobe whether the container parses. ffprobe missing is the normal case on a clean
     * machine, not a validation failure (FR-008-002).
     */
    private String validateVideo(Path path) {
        try {
            Process process = new ProcessBuilder(
                "ffprobe", "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1", path.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();

            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(FFPROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.debug("ffprobe timed out on {}", path.getFileName());
                return null;
            }
            if (process.exitValue() != 0) {
                String detail = output.isBlank() ? "container could not be parsed" : output.trim();
                return "Corrupt video: " + detail.lines().findFirst().orElse(detail);
            }
            return null;
        } catch (IOException e) {
            // ffprobe is not installed. Header validation already passed; do not invent a failure.
            log.debug("ffprobe unavailable, skipping deep video validation");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private boolean isValidMediaType(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.startsWith("image/") || mimeType.startsWith("video/");
    }

    private void skip(MediaFile mediaFile, MediaFile.SkipReason reason) {
        mediaFile.setValidationStatus(MediaFile.ValidationStatus.SKIPPED);
        mediaFile.setSkipReason(reason);
        mediaFile.setOutcome(MediaFile.Outcome.SKIPPED);
    }

    private void fail(MediaFile mediaFile, String reason) {
        mediaFile.setValidationStatus(MediaFile.ValidationStatus.FAILED);
        mediaFile.setFailureReason(reason);
        mediaFile.setOutcome(MediaFile.Outcome.FAILED);
    }
}
