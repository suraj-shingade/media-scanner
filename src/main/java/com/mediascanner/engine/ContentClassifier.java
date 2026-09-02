package com.mediascanner.engine;

import com.mediascanner.model.MimeGroup;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Decides a file's {@link MimeGroup} from its contents alone (FR-033).
 *
 * <p><strong>Why not {@code Tika.detect(File)}:</strong> that overload feeds the filename into the
 * detector alongside the magic bytes, so a Windows executable renamed {@code holiday.jpg} comes back
 * as {@code image/jpeg}. For the transfer pipeline that is a harmless optimisation. Here it would mean
 * an executable is silently reclassified as protected media — or worse, a real photo named
 * {@code payload.exe} lands in a group the user has ticked for permanent deletion. Detection is driven
 * from the stream, which uses magic bytes only.
 *
 * <p>Classification never throws for a file it cannot read. An unreadable or empty file still receives
 * a definite group (FR-036), because a file that vanishes from the preview is a file nobody reviewed.
 */
public class ContentClassifier {

    private static final Logger log = LoggerFactory.getLogger(ContentClassifier.class);

    /** Detected for a file we could not open. Lands in OTHER, and is shown as such in the preview. */
    public static final String UNREADABLE = "application/x-unreadable";

    /** Detected for a zero-byte file. Tika would say {@code application/octet-stream}. */
    public static final String EMPTY = "application/x-empty";

    private final Tika tika = new Tika();

    /**
     * @return the MIME type detected from the file's bytes; never null.
     */
    public String detectMimeType(Path path) {
        try {
            if (Files.size(path) == 0) {
                return EMPTY;
            }
        } catch (IOException e) {
            return UNREADABLE;
        }

        String detected;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            // Stream overload only - passing the path or a filename here would defeat FR-033.
            detected = tika.detect(in);
        } catch (IOException e) {
            log.debug("Could not read {} for classification: {}", path, e.getMessage());
            return UNREADABLE;
        }

        if (isZipContainer(detected)) {
            String refined = refineZipContainer(path);
            if (refined != null) {
                return refined;
            }
        }
        return detected;
    }

    public MimeGroup classify(Path path) {
        return MimeGroup.forMimeType(detectMimeType(path));
    }

    private boolean isZipContainer(String mimeType) {
        return "application/zip".equalsIgnoreCase(mimeType)
            || "application/java-archive".equalsIgnoreCase(mimeType);
    }

    /**
     * An APK is a ZIP, so magic bytes alone report {@code application/zip}. Falling back to the
     * {@code .apk} extension is exactly what FR-033 forbids, so instead we read the archive's entry
     * names — still contents, not the name — and look for the two members every APK has.
     *
     * @return a refined MIME type, or null to keep the original detection.
     */
    private String refineZipContainer(Path path) {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            boolean manifest = false;
            boolean dex = false;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements() && !(manifest && dex)) {
                String name = entries.nextElement().getName();
                if ("AndroidManifest.xml".equals(name)) manifest = true;
                else if ("classes.dex".equals(name)) dex = true;
            }
            if (manifest && dex) {
                return "application/vnd.android.package-archive";
            }
        } catch (IOException | RuntimeException e) {
            // A corrupt or unreadable ZIP stays an archive; it is still not media, and the user
            // still sees it in the preview.
            log.debug("Could not probe ZIP container {}: {}", path, e.getMessage());
        }
        return null;
    }
}
