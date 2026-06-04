package com.mediascanner.app;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Entry point for direct JAR execution (java -jar mediascanner.jar).
 *
 * JavaFX native libraries (.dylib / .dll) are embedded inside the fat JAR.
 * They are not on java.library.path when launched with "java -jar", so JavaFX
 * fails to load glass/prism. This class extracts those libraries to a temp
 * directory and re-launches the JVM with -Djava.library.path pointing there.
 *
 * When invoked by mvn javafx:run or an IDE the code source is a directory,
 * not a JAR — in that case JavaFX is already on the module path and we
 * delegate directly to MediaScannerApp without relaunching.
 *
 * The jpackage installer continues to use MediaScannerApp as its --main-class;
 * it sets up java.library.path via $APPDIR and must not go through this class.
 */
public class Launcher {

    private static final Logger LOG = Logger.getLogger(Launcher.class.getName());
    private static final String LAUNCHED_FLAG = "mediascanner.launched";

    private static final String[] NATIVE_LIBS = {
        // macOS
        "libglass.dylib", "libjavafx_iio.dylib", "libjavafx_font.dylib",
        "libprism_common.dylib", "libprism_es2.dylib", "libdecora_sse.dylib",
        "libprism_sw.dylib",
        // Windows
        "glass.dll", "javafx_font.dll", "javafx_font_t2k.dll",
        "javafx_iio.dll", "prism_common.dll", "prism_d3d.dll",
        "prism_es2.dll", "prism_sw.dll"
    };

    public static void main(String[] args) throws Exception {
        if (Boolean.getBoolean(LAUNCHED_FLAG) || !isRunningFromJar()) {
            MediaScannerApp.main(args);
            return;
        }

        if (!canLoadAppClass()) {
            LOG.severe("This is the thin JAR (mediascanner-1.0.0.jar) and does not contain all required classes.");
            LOG.severe("Run the fat JAR instead:  java -jar target/mediascanner.jar");
            System.exit(1);
        }

        Path nativeDir = extractNativeLibs();
        relaunch(nativeDir, args);
    }

    private static boolean isRunningFromJar() {
        try {
            URI location = Launcher.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            return location.getPath().endsWith(".jar");
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean canLoadAppClass() {
        return Launcher.class.getResource("/com/mediascanner/app/MediaScannerApp.class") != null;
    }

    private static Path extractNativeLibs() throws IOException {
        Path tmpDir = createSecureTempDir();
        for (String lib : NATIVE_LIBS) {
            try (InputStream in = Launcher.class.getResourceAsStream("/" + lib)) {
                if (in != null) {
                    Files.copy(in, tmpDir.resolve(lib));
                }
            }
        }
        return tmpDir;
    }

    // S5443: POSIX 700 is set for Unix; Windows has no standard attribute API —
    // File.setReadable/setWritable/setExecutable(true, ownerOnly=true) is the best available.
    @SuppressWarnings("java:S5443")
    private static Path createSecureTempDir() throws IOException {
        try {
            Set<PosixFilePermission> ownerOnly = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
            return Files.createTempDirectory("mediascanner-native-",
                    PosixFilePermissions.asFileAttribute(ownerOnly));
        } catch (UnsupportedOperationException e) {
            // Windows does not support POSIX attributes; restrict via File API
            Path dir = Files.createTempDirectory("mediascanner-native-");
            File f = dir.toFile();
            boolean readable   = f.setReadable(true, true);
            boolean writable   = f.setWritable(true, true);
            boolean executable = f.setExecutable(true, true);
            boolean ok = readable && writable && executable;
            if (!ok) {
                LOG.warning("Could not set owner-only permissions on native lib temp dir: " + dir);
            }
            return dir;
        }
    }

    private static void relaunch(Path nativeDir, String[] args)
            throws IOException, InterruptedException, URISyntaxException {
        URI location = Launcher.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI();
        String jarPath = new File(location).getAbsolutePath();

        String javaExe = ProcessHandle.current().info().command().orElse("java");

        List<String> cmd = new ArrayList<>(List.of(
                javaExe,
                "-D" + LAUNCHED_FLAG + "=true",
                "-Djava.library.path=" + nativeDir.toAbsolutePath(),
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "-jar", jarPath
        ));
        cmd.addAll(List.of(args));

        Process process = new ProcessBuilder(cmd).inheritIO().start();
        System.exit(process.waitFor());
    }
}
