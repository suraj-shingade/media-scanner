package com.mediascanner.engine;

import com.mediascanner.model.IgnoreRule;
import com.mediascanner.model.MediaFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class FileScannerTest {

    @TempDir
    Path tempDir;

    private void createFile(Path parent, String name, String content) throws IOException {
        Files.createDirectories(parent);
        Files.writeString(parent.resolve(name), content);
    }

    @Test
    void testOnlyMediaFilesReturned() throws IOException {
        createFile(tempDir, "photo.jpg", "dummy");
        createFile(tempDir, "video.mp4", "dummy");
        createFile(tempDir, "document.pdf", "dummy");
        createFile(tempDir, ".DS_Store", "dummy");

        FileScanner scanner = new FileScanner(null);
        List<Path> results;
        try (Stream<Path> stream = scanner.walkFileTree(tempDir)) {
            results = stream.collect(Collectors.toList());
        }

        assertThat(results).hasSize(2);
        assertThat(results.stream().map(p -> p.getFileName().toString()).collect(Collectors.toSet()))
            .containsExactlyInAnyOrder("photo.jpg", "video.mp4");
    }

    @Test
    void testRecursiveTraversal5Levels() throws IOException {
        Path deep = tempDir;
        for (int i = 0; i < 5; i++) {
            deep = deep.resolve("level" + i);
        }
        createFile(deep, "deep.jpg", "dummy");
        createFile(tempDir, "top.png", "dummy");

        FileScanner scanner = new FileScanner(null);
        List<Path> results;
        try (Stream<Path> stream = scanner.walkFileTree(tempDir)) {
            results = stream.collect(Collectors.toList());
        }
        assertThat(results).hasSize(2);
    }

    @Test
    void testDefaultIgnorePatternsFiltered() throws IOException {
        createFile(tempDir, "photo.jpg", "dummy");
        createFile(tempDir, ".DS_Store", "sys");
        createFile(tempDir, "Thumbs.db", "sys");
        createFile(tempDir, "desktop.ini", "sys");

        List<IgnoreRule> rules = Arrays.asList(
            new IgnoreRule(".DS_Store", IgnoreRule.Source.DEFAULT),
            new IgnoreRule("Thumbs.db", IgnoreRule.Source.DEFAULT),
            new IgnoreRule("desktop.ini", IgnoreRule.Source.DEFAULT)
        );
        FileScanner scanner = new FileScanner(rules);
        List<Path> results;
        try (Stream<Path> stream = scanner.walkFileTree(tempDir)) {
            results = stream.collect(Collectors.toList());
        }
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getFileName().toString()).isEqualTo("photo.jpg");
    }

    @Test
    void testCustomIgnorePatternWorks() throws IOException {
        createFile(tempDir, "IMG001.jpg", "dummy");
        createFile(tempDir, "SKIP_ME.jpg", "dummy");

        List<IgnoreRule> rules = List.of(
            new IgnoreRule("SKIP_*", IgnoreRule.Source.USER_DEFINED)
        );
        FileScanner scanner = new FileScanner(rules);
        List<Path> results;
        try (Stream<Path> stream = scanner.walkFileTree(tempDir)) {
            results = stream.collect(Collectors.toList());
        }
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getFileName().toString()).isEqualTo("IMG001.jpg");
    }

    @Test
    void testEmptySourceReturnsEmptyStream() throws IOException {
        FileScanner scanner = new FileScanner(null);
        List<Path> results;
        try (Stream<Path> stream = scanner.walkFileTree(tempDir)) {
            results = stream.collect(Collectors.toList());
        }
        assertThat(results).isEmpty();
    }

    @Test
    void testNonExistentSourceReturnsEmpty() throws IOException {
        FileScanner scanner = new FileScanner(null);
        List<Path> results;
        try (Stream<Path> stream = scanner.walkFileTree(Paths.get("/nonexistent/path"))) {
            results = stream.collect(Collectors.toList());
        }
        assertThat(results).isEmpty();
    }
}
