# Research: Native Installer Packaging

**Feature**: 003-installable-builds | **Date**: 2026-06-03

## Decision 1: Universal Binary Strategy for macOS

**Decision**: Use BellSoft Liberica JDK 21 Full (Universal Binary, ARM64 + x86_64) as the JDK on the macOS build machine. jpackage bundles the JRE from the JDK it runs from, so a universal JDK produces a universal app bundle automatically. Liberica JDK Full is required (not the standard Liberica JDK) because it bundles OpenJFX — the JavaFX module native libraries (`libglass.dylib`, `libjavafx_*.dylib`, etc.) must be present in the JDK so jpackage's `jlink` step can include them in the bundled runtime via `--add-modules javafx.controls,javafx.fxml,javafx.swing,javafx.graphics`.

**Rationale**: Standard JDKs (Temurin, Microsoft, Azul Zulu non-FX, Corretto) do not include JavaFX modules. Without them, jpackage builds a bundled JRE with no JavaFX native libs, causing a "JavaFX runtime components are missing" crash on launch. Liberica JDK Full solves both requirements in one package: Universal Binary JRE + JavaFX native libs.

**Install (macOS)**:
```bash
brew tap bell-sw/liberica
brew install --cask liberica-jdk21-full
export JAVA_HOME=/Library/Java/JavaVirtualMachines/liberica-jdk-21-full.jdk/Contents/Home
```
Verify: `file $JAVA_HOME/bin/java` must show `Mach-O universal binary with 2 architectures`.

**jpackage requirement**: Always pass `--add-modules javafx.controls,javafx.fxml,javafx.swing,javafx.graphics` to jpackage. Without this flag jlink will not include the JavaFX modules in the bundled JRE even when the JDK has them.

**Alternatives considered**:
- Azul Zulu Universal (non-FX): Universal Binary JRE but no JavaFX modules — produces a crash. Rejected.
- Azul Zulu with OpenJFX: Available from azul.com but not in Homebrew; download is manual and version must be verified. Viable but less convenient.
- Two-pass lipo approach (build ARM64 + x86_64 separately, merge with `lipo`): more complex, requires two JDK installs and post-processing scripts. Rejected.
- x86_64 only with Rosetta 2: excludes native performance on Apple Silicon. Rejected per spec clarification (Q1 → A).
- ARM64 only: excludes Intel Mac users. Rejected per spec clarification (Q1 → A).

---

## Decision 2: jpackage Maven Integration Mechanism

**Decision**: Use `exec-maven-plugin` (org.codehaus.mojo:exec-maven-plugin) inside dedicated Maven profiles to invoke jpackage with the required arguments.

**Rationale**: `exec-maven-plugin` is a standard Maven plugin already available in the Maven Central ecosystem without adding an exotic dependency. It passes Maven properties (including `${project.version}`) as arguments to the subprocess, enabling version stamping without a wrapper script. This keeps the entire build pipeline within Maven and avoids platform-specific shell scripts in the repository.

**Profile names**: `package-mac` (macOS) and `package-win` (Windows). Activated explicitly with `-P package-mac` or `-P package-win`.

**Alternatives considered**:
- `com.github.akman:jpackage-maven-plugin`: dedicated plugin with cleaner XML, but less widely adopted, requires a third-party repository, and adds a non-trivial dependency.
- Shell scripts (`package-mac.sh`, `package-win.bat`): simpler but bypasses Maven property resolution, harder to integrate with CI in the future, and requires separate invocation outside Maven.
- `io.github.fvarrui:javapackager`: another third-party plugin; more complex configuration.

---

## Decision 3: jpackage Input — Fat JAR

**Decision**: Use the fat JAR at `target/mediascanner.jar` (produced by `maven-assembly-plugin` during `mvn package`) as the `--input` artifact for jpackage.

**Rationale**: The fat JAR bundles all dependencies including JavaFX native libraries for the current platform (via the platform-classified JavaFX artifacts). jpackage classpath mode with a fat JAR is the simplest jpackage invocation pattern. The existing assembly plugin already produces this file reliably.

**Key jpackage flags**:
- `--input target` — directory containing the JAR
- `--main-jar mediascanner.jar`
- `--main-class com.mediascanner.app.MediaScannerApp`

**JavaFX native libraries note**: The fat JAR assembled by `maven-assembly-plugin` includes JavaFX `.dylib` / `.dll` natives for the current build platform's architecture. When building the Universal Binary on macOS with Zulu JDK 21 Universal, the JavaFX jars must also include universal/ARM64 natives. Use `javafx-controls`, `javafx-fxml`, and `javafx-swing` with the `mac-aarch64` and `mac` classifiers. The universal JDK approach handles this transparently when building on macOS — Maven downloads platform-matched JavaFX JARs for the build host.

**Alternatives considered**:
- Module-path / jlink approach: produces a smaller runtime image but requires full modularisation of the application (all JARs must be proper modules). This is complex given the non-modular dependencies in the project. Deferred to a future optimisation.

---

## Decision 4: macOS Installer Type — DMG

**Decision**: Produce a `.dmg` (disk image) installer for macOS.

**Rationale**: `.dmg` is the standard drag-to-Applications distribution format for unsigned macOS applications. It is the most familiar installer experience for macOS users and requires no additional tooling (unlike `.pkg`, which requires `pkgbuild`/`productbuild`). Gatekeeper blocks unsigned `.pkg` more aggressively than unsigned `.dmg`. `.dmg` is the correct choice for v1 unsigned distribution.

**jpackage flag**: `--type dmg`

**Alternatives considered**:
- `.pkg`: More enterprise-friendly (silent install, MDM deployment), but Gatekeeper is more restrictive with unsigned `.pkg`. Deferred to a future signed release.

---

## Decision 5: Windows Installer Type — MSI

**Decision**: Produce a `.msi` installer for Windows.

**Rationale**: `.msi` is preferred for enterprise environments (Group Policy, silent install, proper uninstall via Add/Remove Programs) and is the jpackage default for Windows when WiX Toolset is installed. The spec clarification (Q3 confirmation) and assumption confirm `.msi` preference.

**Prerequisite**: WiX Toolset v3.x must be installed on the Windows build machine and on the PATH. Download: https://wixtoolset.org/releases/ (v3.x — jpackage does NOT support WiX v4).

**jpackage flag**: `--type msi`

**Windows-specific jpackage flags**:
- `--win-dir-chooser` — lets user choose install location
- `--win-menu` — creates Start Menu entry
- `--win-shortcut` — creates Desktop shortcut
- `--win-menu-group MediaScanner` — Start Menu group name
- `--win-upgrade-uuid` — stable GUID for upgrade detection (must be generated once and kept constant)

**Windows Upgrade UUID**: `a1b2c3d4-e5f6-7890-abcd-ef1234567890` (generated for this project — must not change between releases or Windows will treat each version as a separate app in Add/Remove Programs).

**Alternatives considered**:
- `.exe` (NSIS-style): simpler for consumers but lacks enterprise deployment features. Deferred.

---

## Decision 6: Icon Asset Creation

**Decision**: Create icon assets from a single source PNG (512×512 minimum, 1024×1024 recommended).

**macOS**: Use macOS built-in `sips` and `iconutil` to convert a PNG to ICNS format.
```bash
# Create iconset directory, resize to required sizes, then:
iconutil -c icns MediaScanner.iconset
```

**Windows**: Use ImageMagick (`convert`) or an online converter to produce a multi-size ICO (16, 32, 48, 64, 128, 256 px).
```bash
convert icon-1024.png -define icon:auto-resize=256,128,64,48,32,16 MediaScanner.ico
```

**If no source PNG exists**: A placeholder icon must be created before running the packaging pipeline. Tasks will include this as a prerequisite.

---

## Decision 7: Version Stamping

**Decision**: No code changes required. Version flows correctly through the existing pipeline.

**Flow**: `pom.xml` `<version>` → `maven-jar-plugin` `addDefaultImplementationEntries=true` → `META-INF/MANIFEST.MF` `Implementation-Version` → `AppConfig.getAppVersion()` (already implemented) → About screen.

**jpackage version**: Pass `${project.version}` via exec-maven-plugin argument `--app-version`. This stamps the installer metadata, file name, and macOS Info.plist / Windows Add/Remove Programs.

**Confirmed**: `AppConfig.getAppVersion()` at line 190–193 already reads `getPackage().getImplementationVersion()` with fallback to `"1.0.0"`.

---

## Decision 8: Build Machine Prerequisites Summary

### macOS Build Machine
| Requirement | Version | Notes |
|---|---|---|
| BellSoft Liberica JDK 21 Full | 21.x | Must be `JAVA_HOME`; must be the Full edition (includes JavaFX + Universal Binary) |
| Maven | 3.8+ | Already used in project |
| `iconutil` | built-in | macOS tool, pre-installed |
| `sips` | built-in | macOS tool, pre-installed |

### Windows Build Machine
| Requirement | Version | Notes |
|---|---|---|
| JDK 21 (any vendor, x64) | 21.x | Must be `JAVA_HOME` for packaging profile |
| Maven | 3.8+ | Standard |
| WiX Toolset | v3.x (NOT v4) | Must be on PATH; jpackage requirement for MSI |
| ImageMagick | any | For ICO generation (one-time) |
