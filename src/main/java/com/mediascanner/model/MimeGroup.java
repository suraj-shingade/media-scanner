package com.mediascanner.model;

import java.util.List;
import java.util.Set;

/**
 * The classification buckets the Cleanup tool sorts files into (FR-034).
 *
 * <p>Membership is decided by the MIME type detected from a file's <em>contents</em>. A file's name
 * never participates (FR-033), which is why these are MIME prefixes and exact types rather than
 * extension lists.
 *
 * <p>{@link #PROTECTED_MEDIA} is the load-bearing entry: it is the only group with
 * {@code deletable == false}, and it is what makes FR-045 — "media is never deleted" — a property of
 * the type system rather than of whichever caller happens to be passing a selection today.
 */
public enum MimeGroup {

    /**
     * Images and videos, identified by content. Never deletable, under any user selection
     * (FR-035, FR-045, Constitution IX).
     */
    PROTECTED_MEDIA("Protected media", false, List.of("image/", "video/"), Set.of()),

    /** Android packages. Detected by probing ZIP contents, not by the {@code .apk} name (D2). */
    ANDROID_PACKAGE("Android packages", true, List.of(),
        Set.of("application/vnd.android.package-archive")),

    EXECUTABLE("Executables and installers", true, List.of(),
        Set.of("application/x-msdownload",
               "application/x-dosexec",
               "application/vnd.microsoft.portable-executable",
               "application/x-executable",
               "application/x-sharedlib",
               "application/x-mach-binary",
               "application/x-ms-installer",
               "application/x-msi",
               "application/vnd.ms-cab-compressed",
               "application/x-elf",
               "application/x-sh",
               "application/x-bat",
               "application/x-msdos-program")),

    ARCHIVE("Archives", true, List.of(),
        Set.of("application/zip",
               "application/x-rar-compressed",
               "application/vnd.rar",
               "application/x-7z-compressed",
               "application/gzip",
               "application/x-gzip",
               "application/x-tar",
               "application/x-bzip2",
               "application/x-xz",
               "application/java-archive")),

    DOCUMENT("Documents", true, List.of("text/"),
        Set.of("application/pdf",
               "application/msword",
               "application/rtf",
               "application/vnd.ms-excel",
               "application/vnd.ms-powerpoint",
               "application/vnd.oasis.opendocument.text",
               "application/vnd.oasis.opendocument.spreadsheet",
               "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
               "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
               "application/vnd.openxmlformats-officedocument.presentationml.presentation")),

    /**
     * Audio. Not protected media — the archive is images and videos — but broken out of
     * {@link #OTHER} so that deleting a music collection is always a deliberate, separate choice
     * rather than a side effect of ticking "other" (spec Assumptions).
     */
    AUDIO("Audio", true, List.of("audio/"), Set.of()),

    /** Everything else that is not protected media. The catch-all required by FR-036. */
    OTHER("Other non-media", true, List.of(), Set.of());

    private final String displayName;
    private final boolean deletable;
    private final List<String> mimePrefixes;
    private final Set<String> exactMimeTypes;

    MimeGroup(String displayName, boolean deletable,
              List<String> mimePrefixes, Set<String> exactMimeTypes) {
        this.displayName = displayName;
        this.deletable = deletable;
        this.mimePrefixes = mimePrefixes;
        this.exactMimeTypes = exactMimeTypes;
    }

    public String getDisplayName() { return displayName; }

    /** False only for {@link #PROTECTED_MEDIA}. Checked again per file inside the delete loop. */
    public boolean isDeletable() { return deletable; }

    private boolean matches(String mimeType) {
        if (mimeType == null) return false;
        String type = mimeType.toLowerCase();
        int semi = type.indexOf(';');
        if (semi >= 0) {
            type = type.substring(0, semi).trim();
        }
        if (exactMimeTypes.contains(type)) return true;
        for (String prefix : mimePrefixes) {
            if (type.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Maps a detected MIME type to its group.
     *
     * <p>{@link #PROTECTED_MEDIA} is tested first and {@link #OTHER} last, so a type that would match
     * several groups always resolves to the most protective one. Every input produces a group —
     * there is no null return and no "unclassified" state (FR-036).
     */
    public static MimeGroup forMimeType(String mimeType) {
        if (PROTECTED_MEDIA.matches(mimeType)) return PROTECTED_MEDIA;
        for (MimeGroup group : values()) {
            if (group != PROTECTED_MEDIA && group != OTHER && group.matches(mimeType)) {
                return group;
            }
        }
        return OTHER;
    }

    /** The groups a user is permitted to select for deletion. */
    public static List<MimeGroup> deletableGroups() {
        return List.of(ANDROID_PACKAGE, EXECUTABLE, ARCHIVE, DOCUMENT, AUDIO, OTHER);
    }
}
