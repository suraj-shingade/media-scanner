package com.mediascanner.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Walks a directory for the Cleanup tool.
 *
 * <p>Differs from {@link FileScanner} in three ways that matter here:
 * <ul>
 *   <li>It yields <strong>every</strong> regular file, not only known image and video extensions.
 *       {@code FileScanner} drops everything else before it reaches a worker, which is precisely why
 *       an {@code .exe} sitting in a source tree is invisible to MediaScanner today.</li>
 *   <li>It yields <strong>directories</strong> as well as files, in post-order, which empty-folder
 *       pruning needs and {@code FileScanner}'s flat-mapped walk cannot express.</li>
 *   <li>It never follows symbolic links or junctions (FR-047), so a link cannot lead a permanent
 *       deletion outside the tree the user selected.</li>
 * </ul>
 *
 * <p>Like {@code FileScanner}, an unreadable directory is logged and skipped rather than aborting the
 * walk — the invariant established in Session 4 that a multi-hour job must survive one
 * permission-denied folder.
 */
public class CleanupScanner {

    private static final Logger log = LoggerFactory.getLogger(CleanupScanner.class);

    /**
     * Visits every regular file beneath {@code root}, depth-first.
     *
     * <p>Symbolic links are reported to {@code onFile} if they point at a regular file — the link
     * itself is a candidate — but are never traversed.
     */
    public void walkFiles(Path root, Consumer<Path> onFile, CancelSignal cancel) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        walkFilesRecursive(root, onFile, cancel);
    }

    private void walkFilesRecursive(Path dir, Consumer<Path> onFile, CancelSignal cancel) {
        if (cancel != null && cancel.isCancelled()) return;

        for (Path child : listChildren(dir)) {
            if (cancel != null && cancel.isCancelled()) return;

            if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                walkFilesRecursive(child, onFile, cancel);
            } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(child)) {
                onFile.accept(child);
            }
        }
    }

    /**
     * Collects directories beneath {@code root} in post-order — deepest first.
     *
     * <p>Post-order is what lets a chain of empty folders collapse in a single pass: by the time a
     * parent is considered, its children have already been removed, so it is empty too (FR-051).
     * The root itself is never included (FR-053).
     */
    public List<Path> directoriesDepthFirst(Path root) {
        List<Path> out = new ArrayList<>();
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            collectDirectories(root, out);
        }
        return out;
    }

    private void collectDirectories(Path dir, List<Path> out) {
        for (Path child : listChildren(dir)) {
            // A linked directory is not descended into and is not a prune candidate: removing it
            // would be removing a link, and its "emptiness" is a property of somewhere else.
            if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(child)) {
                collectDirectories(child, out);
                out.add(child);
            }
        }
    }

    /** True when a directory holds no entries at all (FR-050, FR-054). */
    public boolean isEmptyDirectory(Path dir) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            return !stream.iterator().hasNext();
        } catch (IOException e) {
            log.debug("Could not read directory {}: {}", dir, e.getMessage());
            return false;
        }
    }

    private List<Path> listChildren(Path dir) {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                children.add(child);
            }
        } catch (IOException e) {
            log.warn("Skipping unreadable directory {}: {}", dir, e.getMessage());
            return Collections.emptyList();
        }
        return children;
    }

    /** Lets a long walk be abandoned promptly (FR-037). */
    public interface CancelSignal {
        boolean isCancelled();
    }
}
