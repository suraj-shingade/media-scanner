package com.mediascanner.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;

public class FileTransfer {

    private static final Logger log = LoggerFactory.getLogger(FileTransfer.class);

    public FileTransfer(String targetRoot) {
        // targetRoot is retained by the caller; report writing moved to JobReportService (feature 005).
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
        Files.createDirectories(destination.getParent());
        // Same-volume moves are a metadata rename: no bytes are read or written. Falling straight
        // through to copy+delete would re-read the whole archive for a Move job.
        if (!Files.exists(destination)) {
            try {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
                log.debug("Moved (rename) {} -> {}", source, destination);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                log.debug("Cross-volume move for {}; falling back to copy+delete", source);
            }
        }
        copy(source, destination);
        Files.delete(source);
        log.debug("Moved (copy+delete) {} -> {}", source, destination);
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
}
