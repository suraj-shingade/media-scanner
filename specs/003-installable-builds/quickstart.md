# Quickstart: Building Native Installers

**Feature**: 003-installable-builds | **Date**: 2026-06-03

## Prerequisites

### macOS Build Machine

1. **BellSoft Liberica JDK 21 Full** (Universal Binary, ARM64 + x86_64, includes JavaFX)

   > **Important**: Use the **Full** edition — the standard Liberica JDK and all other common JDKs (Temurin, Microsoft, Zulu, Corretto) do not include JavaFX modules. Without JavaFX in the JDK, the bundled installer crashes with "JavaFX runtime components are missing" at launch.

   ```bash
   brew tap bell-sw/liberica
   brew install --cask liberica-jdk21-full
   export JAVA_HOME=/Library/Java/JavaVirtualMachines/liberica-jdk-21-full.jdk/Contents/Home
   ```
   - Verify JDK: `$JAVA_HOME/bin/java -version` should show Liberica 21
   - Verify Universal: `file $JAVA_HOME/bin/java` should show `Mach-O universal binary with 2 architectures`
   - Verify JavaFX: `ls $JAVA_HOME/lib | grep glass` should show `libglass.dylib`

2. **Maven 3.8+**: `mvn -version`

3. **Icon file**: `src/packaging/macos/MediaScanner.icns` must exist (see Icon Creation below)

### Windows Build Machine

1. **JDK 21** (any vendor, x64)
   - Set `JAVA_HOME` to the JDK 21 home

2. **WiX Toolset v3.x** (NOT v4)
   - Download from https://wixtoolset.org/releases/ — install the v3.x release
   - Ensure `candle.exe` and `light.exe` are on the PATH
   - Verify: `candle /?` prints WiX version starting with `3.`

3. **Maven 3.8+**

4. **Icon file**: `src/packaging/windows/MediaScanner.ico` must exist (see Icon Creation below)

---

## Icon Creation (one-time setup)

### macOS (ICNS)

```bash
# Start from a 1024×1024 source PNG (e.g., icon-1024.png)
mkdir MediaScanner.iconset
sips -z 16 16   icon-1024.png --out MediaScanner.iconset/icon_16x16.png
sips -z 32 32   icon-1024.png --out MediaScanner.iconset/icon_16x16@2x.png
sips -z 32 32   icon-1024.png --out MediaScanner.iconset/icon_32x32.png
sips -z 64 64   icon-1024.png --out MediaScanner.iconset/icon_32x32@2x.png
sips -z 128 128 icon-1024.png --out MediaScanner.iconset/icon_128x128.png
sips -z 256 256 icon-1024.png --out MediaScanner.iconset/icon_128x128@2x.png
sips -z 256 256 icon-1024.png --out MediaScanner.iconset/icon_256x256.png
sips -z 512 512 icon-1024.png --out MediaScanner.iconset/icon_256x256@2x.png
sips -z 512 512 icon-1024.png --out MediaScanner.iconset/icon_512x512.png
cp icon-1024.png MediaScanner.iconset/icon_512x512@2x.png
iconutil -c icns MediaScanner.iconset -o src/packaging/macos/MediaScanner.icns
rm -rf MediaScanner.iconset
```

### Windows (ICO)

```bash
# Using ImageMagick (any platform)
convert icon-1024.png -define icon:auto-resize=256,128,64,48,32,16 \
  src/packaging/windows/MediaScanner.ico

# Using Python Pillow (if ImageMagick unavailable)
python3 -c "
from PIL import Image
src = Image.open('icon-1024.png').convert('RGBA')
sizes = [16, 32, 48, 64, 128, 256]
frames = [src.resize((s, s), Image.LANCZOS) for s in sizes]
frames[0].save('src/packaging/windows/MediaScanner.ico', format='ICO',
  sizes=[(s,s) for s in sizes], append_images=frames[1:])
"
```

---

## Building the macOS Installer

```bash
# Must use Liberica JDK 21 Full — provides Universal Binary JRE + JavaFX native libs
export JAVA_HOME=/Library/Java/JavaVirtualMachines/liberica-jdk-21-full.jdk/Contents/Home

# Build the fat JAR + run jpackage
mvn package -P package-mac -DskipTests

# Output: target/installer/MediaScanner-1.0.0.dmg  (version from pom.xml)
```

**To change the version**, update `<version>` in `pom.xml` before running the command.

---

## Building the Windows Installer

```powershell
# Run on a Windows machine with JDK 21 and WiX v3 installed

mvn package -P package-win -DskipTests

# Output: target\installer\MediaScanner-1.0.0.msi
```

---

## Verifying the macOS Installer

1. Open the `.dmg` — a Finder window with the app and an Applications alias should appear.
2. Drag `MediaScanner.app` to Applications.
3. Attempt to open — Gatekeeper will block with "cannot be opened because it is from an unidentified developer."
4. Follow the bypass steps in `docs/INSTALL.md`.
5. The app opens; verify Help → About shows version `1.0.0`.

## Verifying the Windows Installer

1. Run the `.msi` — UAC prompt appears, then the installer wizard.
2. Complete installation; a Start Menu entry appears under MediaScanner.
3. Launch from Start Menu; app opens.
4. Verify version in About dialog.
5. Open Settings → Apps — MediaScanner should appear with version `1.0.0`.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `jpackage: command not found` | JDK < 14 or wrong JAVA_HOME | Set `JAVA_HOME` to Liberica JDK 21 Full |
| `JavaFX runtime components are missing` (crash on launch) | JavaFX `.dylib` files were not bundled alongside the JAR in `Contents/app/` | Ensure pom.xml `package-mac` profile has the `extract-javafx-natives-mac` exec step (unzips `.dylib` from fat JAR into `target/`) and `--java-options -Djava.library.path=$APPDIR` |
| `Missing JavaFX application class ...` (crash on launch) | `--add-modules javafx.*` was passed to jpackage — it puts JavaFX in the named module graph where its classloader can't see the fat JAR's unnamed-module classes | Remove `--add-modules` from the jpackage args; use the native-lib extraction approach above instead |
| macOS app is not Universal Binary (`file MediaScanner.app/Contents/MacOS/MediaScanner` shows single arch) | JAVA_HOME points to arch-specific JDK | Switch to Liberica JDK 21 Full (Universal Binary) |
| `WiX toolset not found` (Windows) | WiX v3 not on PATH | Install WiX v3, add to PATH |
| `Invalid version '1.0.0-SNAPSHOT'` | jpackage rejects SNAPSHOT qualifiers | Remove `-SNAPSHOT` from `<version>` in pom.xml before packaging |
| Icon not found | Missing `src/packaging/macos/MediaScanner.icns` | Run icon creation steps above |
| Gatekeeper blocks on macOS | Unsigned app | Follow Gatekeeper bypass in `docs/INSTALL.md` |
