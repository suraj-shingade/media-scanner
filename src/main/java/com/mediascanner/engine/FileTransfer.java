package com.mediascanner.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mediascanner.model.FailureRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class FileTransfer {

    private static final Logger log = LoggerFactory.getLogger(FileTransfer.class);

    private final String targetRoot;
    private final ObjectMapper mapper;

    public FileTransfer(String targetRoot) {
        this.targetRoot = targetRoot;
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void copy(Path source, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());

        // Partial file detection on resume
        if (Files.exists(destination)) {
            long destSize = Files.size(destination);
            long srcSize = Files.size(source);
            if (destSize != srcSize) {
                Files.delete(destination);
                log.debug("Deleted partial file at {}", destination);
            }
        }

        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        verifyCopy(source, destination);
    }

    public void move(Path source, Path destination) throws IOException {
        copy(source, destination);
        Files.delete(source);
        log.debug("Moved {} -> {}", source, destination);
    }

    private void verifyCopy(Path source, Path destination) throws IOException {
        long srcSize = Files.size(source);
        long destSize = Files.size(destination);
        if (srcSize != destSize) {
            Files.deleteIfExists(destination);
            throw new IOException("Copy verification failed: source=" + srcSize
                + " dest=" + destSize + " for " + destination);
        }
    }

    /**
     * Returns a path that does not collide with any existing file.
     * IMG001.jpg → IMG001(1).jpg → IMG001(2).jpg
     */
    public Path resolveCollisionFreePath(Path destination) {
        if (!Files.exists(destination)) return destination;

        String name = destination.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String nameNoExt = dot >= 0 ? name.substring(0, dot) : name;
        String ext = dot >= 0 ? name.substring(dot) : "";
        Path parent = destination.getParent();

        int n = 1;
        Path candidate;
        do {
            candidate = parent.resolve(nameNoExt + "(" + n + ")" + ext);
            n++;
        } while (Files.exists(candidate));
        return candidate;
    }

    public void appendFailureRecord(FailureRecord record) throws IOException {
        Path failureDir = Paths.get(targetRoot, "_failures");
        Files.createDirectories(failureDir);
        Path reportFile = failureDir.resolve("failure-report.json");

        List<FailureRecord> records = new ArrayList<>();
        if (Files.exists(reportFile)) {
            try {
                FailureRecord[] existing = mapper.readValue(reportFile.toFile(), FailureRecord[].class);
                records.addAll(List.of(existing));
            } catch (Exception e) {
                log.warn("Could not read existing failure report, starting fresh");
            }
        }
        records.add(record);
        mapper.writeValue(reportFile.toFile(), records);
    }
}
