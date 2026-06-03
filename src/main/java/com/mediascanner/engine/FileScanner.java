package com.mediascanner.engine;

import com.mediascanner.model.IgnoreRule;
import com.mediascanner.model.MediaFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public class FileScanner {

    private static final Logger log = LoggerFactory.getLogger(FileScanner.class);

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "tif", "tiff",
        "heic", "raw", "cr2", "nef", "arw", "dng"
    );
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
        "mp4", "mov", "avi", "mkv", "webm", "mts", "m4v", "3gp"
    );

    private final List<IgnoreRule> ignoreRules;
    private final List<PathMatcher> matchers;

    public FileScanner(List<IgnoreRule> ignoreRules) {
        this.ignoreRules = ignoreRules != null ? ignoreRules : Collections.emptyList();
        this.matchers = buildMatchers(this.ignoreRules);
    }

    private List<PathMatcher> buildMatchers(List<IgnoreRule> rules) {
        List<PathMatcher> list = new ArrayList<>();
        FileSystem fs = FileSystems.getDefault();
        for (IgnoreRule rule : rules) {
            try {
                list.add(fs.getPathMatcher("glob:" + rule.getPattern()));
            } catch (Exception e) {
                log.warn("Invalid ignore pattern '{}': {}", rule.getPattern(), e.getMessage());
            }
        }
        return list;
    }

    public Stream<Path> walkFileTree(Path sourceDir) throws IOException {
        if (!Files.exists(sourceDir)) {
            return Stream.empty();
        }
        return Files.walk(sourceDir)
            .filter(Files::isRegularFile)
            .filter(p -> !isIgnored(p))
            .filter(p -> classifyMediaType(p) != null);
    }

    public MediaFile.FileType classifyMediaType(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        String ext = name.substring(dot + 1);
        if (IMAGE_EXTENSIONS.contains(ext)) return MediaFile.FileType.IMAGE;
        if (VIDEO_EXTENSIONS.contains(ext)) return MediaFile.FileType.VIDEO;
        return null;
    }

    public boolean isIgnored(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) return false;
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(fileName)) return true;
        }
        return false;
    }

    public static Set<String> getImageExtensions() { return IMAGE_EXTENSIONS; }
    public static Set<String> getVideoExtensions() { return VIDEO_EXTENSIONS; }
}
