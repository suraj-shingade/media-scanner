package com.mediascanner.engine;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Refuses directories that are too dangerous to run a permanent deletion against (FR-048).
 *
 * <p>This is a blunt instrument on purpose. A user who points the Cleanup tool at {@code C:\} and
 * ticks "Other non-media" would otherwise be one confirmation click away from deleting their
 * operating system. The check costs nothing and the failure mode it prevents is unrecoverable.
 */
public final class DangerousRoots {

    private static final boolean WINDOWS =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private DangerousRoots() {}

    /** A directory that must not be cleaned, and the reason it is refused. */
    public static final class Refusal {
        private final String reason;
        Refusal(String reason) { this.reason = reason; }
        public String getReason() { return reason; }
    }

    /**
     * @return a {@link Refusal} if the path must not be cleaned, or {@code null} if it is acceptable.
     */
    public static Refusal check(Path dir) {
        if (dir == null) {
            return new Refusal("No directory selected.");
        }
        Path path = dir.toAbsolutePath().normalize();

        if (path.getParent() == null) {
            return new Refusal("This is a drive root. Choose a folder inside it instead.");
        }

        Path home = homeDirectory();
        if (home != null && path.equals(home)) {
            return new Refusal("This is your user profile root. Choose a folder inside it instead.");
        }

        for (Path protectedDir : protectedDirectories()) {
            if (path.equals(protectedDir) || path.startsWith(protectedDir)) {
                return new Refusal("This is a system directory (" + protectedDir + ").");
            }
        }
        return null;
    }

    private static Path homeDirectory() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return null;
        try {
            return Paths.get(home).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Path> protectedDirectories() {
        List<String> raw = new ArrayList<>();
        if (WINDOWS) {
            addEnvPath(raw, "SystemRoot");
            addEnvPath(raw, "ProgramFiles");
            addEnvPath(raw, "ProgramFiles(x86)");
            addEnvPath(raw, "ProgramData");
            raw.add("C:\\Windows");
            raw.add("C:\\Program Files");
            raw.add("C:\\Program Files (x86)");
        } else {
            raw.add("/System");
            raw.add("/Library");
            raw.add("/usr");
            raw.add("/bin");
            raw.add("/sbin");
            raw.add("/etc");
            raw.add("/var");
            raw.add("/boot");
            raw.add("/dev");
            raw.add("/proc");
            raw.add("/Applications");
        }

        List<Path> paths = new ArrayList<>();
        for (String s : raw) {
            try {
                paths.add(Paths.get(s).toAbsolutePath().normalize());
            } catch (Exception ignored) {
                // A path this platform does not understand is simply not a protected directory here.
            }
        }
        return paths;
    }

    private static void addEnvPath(List<String> into, String var) {
        String value = System.getenv(var);
        if (value != null && !value.isBlank()) {
            into.add(value);
        }
    }
}
