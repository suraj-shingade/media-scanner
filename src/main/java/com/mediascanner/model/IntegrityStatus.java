package com.mediascanner.model;

/**
 * What a verification pass determined about one file (FR-065, FR-068).
 *
 * <p>The distinction between {@link #UNREADABLE} and {@link #CORRUPTED} is the one that matters and the
 * one that is easiest to blur. "I could not check this file" and "this file is wrong" are different
 * claims, and only the second is evidence of data loss. A run that reports a locked file as corrupt
 * teaches the user to distrust the report.
 */
public enum IntegrityStatus {

    /** Present, right size, and — in deep mode — hashes to the value recorded at transfer time. */
    INTACT("Intact", false),

    /** Recorded as transferred, but nothing is at the destination path now. */
    MISSING("Missing", true),

    /** Present, but its size differs from the size recorded at transfer time. */
    RESIZED("Wrong size", true),

    /** Present and the right size, but the contents no longer hash to the recorded value. */
    CORRUPTED("Corrupted", true),

    /** Present, but could not be read — locked, permission denied, I/O error. Not a verdict. */
    UNREADABLE("Unreadable", true),

    /** Found beneath the archive root with no transfer record. Informational, not a failure (FR-068). */
    UNTRACKED("Untracked", false),

    /** Transferred before V003 recorded destinations, so there is nothing to check it against. */
    UNVERIFIABLE("No destination recorded", false);

    private final String displayName;
    private final boolean problem;

    IntegrityStatus(String displayName, boolean problem) {
        this.displayName = displayName;
        this.problem = problem;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * True when this status means something is wrong with the archive.
     *
     * <p>{@link #UNTRACKED} and {@link #UNVERIFIABLE} are deliberately not problems: the first is a file
     * the user may have put there on purpose, the second is a gap in our own older records. Counting
     * either as a failure would make a healthy archive look damaged.
     */
    public boolean isProblem() {
        return problem;
    }
}
