package com.mediascanner.engine;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.mediascanner.model.Job;
import com.mediascanner.model.MediaFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class MetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(MetadataExtractor.class);

    public void extract(MediaFile mediaFile, Path path) {
        if (mediaFile.getFileType() == MediaFile.FileType.IMAGE) {
            extractImage(mediaFile, path);
        } else if (mediaFile.getFileType() == MediaFile.FileType.VIDEO) {
            extractVideo(mediaFile, path);
        }
        if (mediaFile.getExtractedDate() == null) {
            fallbackToFilesystem(mediaFile, path);
        }
    }

    private void extractImage(MediaFile mediaFile, Path path) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(path.toFile());
            ExifSubIFDDirectory exifDir = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (exifDir != null) {
                java.util.Date date = exifDir.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
                if (date != null) {
                    mediaFile.setExtractedDate(
                        date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
                    mediaFile.setDateSource(MediaFile.DateSource.EMBEDDED_CAPTURE);
                    return;
                }
            }
        } catch (Exception e) {
            log.debug("EXIF extraction failed for {}: {}", path.getFileName(), e.getMessage());
        }
        fallbackToFilesystem(mediaFile, path);
    }

    private void extractVideo(MediaFile mediaFile, Path path) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffprobe", "-v", "quiet", "-print_format", "json",
                "-show_format", path.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor();

            if (output.contains("creation_time")) {
                int idx = output.indexOf("\"creation_time\"");
                int start = output.indexOf("\"", idx + 16) + 1;
                int end = output.indexOf("\"", start);
                String dateStr = output.substring(start, end);
                LocalDateTime dt = parseIsoDateTime(dateStr);
                if (dt != null) {
                    mediaFile.setExtractedDate(dt);
                    mediaFile.setDateSource(MediaFile.DateSource.EMBEDDED_CAPTURE);
                    return;
                }
            }
        } catch (Exception e) {
            log.debug("FFprobe extraction failed for {}: {}", path.getFileName(), e.getMessage());
        }
        fallbackToFilesystem(mediaFile, path);
    }

    private void fallbackToFilesystem(MediaFile mediaFile, Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            Instant creation = attrs.creationTime().toInstant();
            if (!creation.equals(Instant.EPOCH)) {
                mediaFile.setExtractedDate(
                    creation.atZone(ZoneId.systemDefault()).toLocalDateTime());
                mediaFile.setDateSource(MediaFile.DateSource.FILE_CREATION);
                return;
            }
            Instant modified = attrs.lastModifiedTime().toInstant();
            if (!modified.equals(Instant.EPOCH)) {
                mediaFile.setExtractedDate(
                    modified.atZone(ZoneId.systemDefault()).toLocalDateTime());
                mediaFile.setDateSource(MediaFile.DateSource.FILE_MODIFIED);
            }
        } catch (IOException e) {
            log.debug("Filesystem date extraction failed for {}: {}", path.getFileName(), e.getMessage());
        }
    }

    public String computeFolderPath(LocalDateTime date, Job.FolderPattern pattern) {
        return switch (pattern) {
            case YYYY_MM -> date.format(DateTimeFormatter.ofPattern("yyyy/MM"));
            case YYYY_MMM -> date.format(DateTimeFormatter.ofPattern("yyyy/MMM"));
            case YYYY_MMM_DD -> date.format(DateTimeFormatter.ofPattern("yyyy/MMM/dd"));
            case YYYY_MM_DD -> date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        };
    }

    private LocalDateTime parseIsoDateTime(String dateStr) {
        try {
            Instant instant = Instant.parse(dateStr);
            return instant.atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
