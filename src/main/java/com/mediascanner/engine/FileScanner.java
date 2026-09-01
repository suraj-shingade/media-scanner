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

    /**
     * Notified for files excluded during the walk. Ignore-rule matches and unsupported formats are
     * filtered here and never reach a worker, so without this hook they cannot appear in the
     * skipped report (FR-020). Null by default - the counting pass must not double-record.
     */
    private java.util.function.BiConsumer<Path, MediaFile.SkipReason> skipListener;

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

    public void setSkipListener(java.util.function.BiConsumer<Path, MediaFile.SkipReason> listener) {
        this.skipListener = listener;
    }

    public Stream<Path> walkFileTree(Path sourceDir) throws IOException {
        if (!Files.exists(sourceDir)) {
            return Stream.empty();
        }
        return walkSafely(sourceDir)
            .filter(Files::isRegularFile)
            .filter(this::acceptOrReport);
    }

    /** Applies the ignore and media-type filters, reporting anything excluded to the listener. */
    private boolean acceptOrReport(Path path) {
        if (isIgnored(path)) {
            notifySkip(path, MediaFile.SkipReason.IGNORE_RULE_MATCHED);
            return false;
        }
        if (classifyMediaType(path) == null) {
            notifySkip(path, MediaFile.SkipReason.UNSUPPORTED_FORMAT);
            return false;
        }
        return true;
    }

    private void notifySkip(Path path, MediaFile.SkipReason reason) {
        if (skipListener == null) return;
        try {
            skipListener.accept(path, reason);
        } catch (Exception e) {
            log.debug("Skip listener failed for {}: {}", path, e.getMessage());
        }
    }

    /**
     * Lazily walks the tree, logging and skipping directories that cannot be read.
     * {@code Files.walk} instead throws {@link java.io.UncheckedIOException} mid-stream on the
     * first permission-denied directory, which would abort an entire multi-hour scan.
     */
    private Stream<Path> walkSafely(Path dir) {
        Stream<Path> children;
        try {
            children = Files.list(dir);
        } catch (IOException e) {
            log.warn("Skipping unreadable directory {}: {}", dir, e.getMessage());
            return Stream.empty();
        }
        return children
            .flatMap(p -> Files.isDirectory(p) ? walkSafely(p) : Stream.of(p))
            .onClose(children::close);
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
