package com.mediascanner.engine;

import com.mediascanner.model.MediaFile;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;

public class FileValidator {

    private static final Logger log = LoggerFactory.getLogger(FileValidator.class);

    private final long imageSizeThresholdBytes;
    private final long videoSizeThresholdBytes;
    private final Tika tika;

    public FileValidator(int imageSizeThresholdKb, int videoSizeThresholdKb) {
        this.imageSizeThresholdBytes = (long) imageSizeThresholdKb * 1024;
        this.videoSizeThresholdBytes = (long) videoSizeThresholdKb * 1024;
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

            // Classify file type
            FileScanner.getImageExtensions();
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

            // Gate 4: media readability via Tika
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

            mediaFile.setValidationStatus(MediaFile.ValidationStatus.VALID);

        } catch (IOException e) {
            fail(mediaFile, "IO error during validation: " + e.getMessage());
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
