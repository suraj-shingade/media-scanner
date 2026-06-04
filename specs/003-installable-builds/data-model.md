# Data Model: Native Installer Packaging

**Feature**: 003-installable-builds | **Date**: 2026-06-03

This feature introduces no changes to the application's runtime data model (SQLite schema, domain entities, or AppConfig). All changes are build-pipeline artifacts.

## Build Artifact Entities

These entities describe the inputs and outputs of the packaging pipeline. They are not persisted in any database; they exist as files in the build system.

---

### InstallerArtifact

The distributable file produced by the packaging pipeline for a given platform and version.

| Field | Type | Description |
|---|---|---|
| platform | enum: `macos`, `windows` | Target OS |
| installerType | enum: `dmg`, `msi` | File format |
| appVersion | SemVer string | e.g., `1.0.0` — sourced from pom.xml |
| outputPath | file path | e.g., `target/installer/MediaScanner-1.0.0.dmg` |
| architecture | enum: `universal`, `x86_64` | `universal` for macOS, `x86_64` for Windows |
| signed | boolean | Always `false` for v1 |

**Naming convention**: `MediaScanner-{appVersion}.dmg` (macOS) / `MediaScanner-{appVersion}.msi` (Windows). jpackage derives this automatically from `--name` and `--app-version`.

---

### ApplicationBundle

The self-contained directory structure that the installer deploys to the end user's machine.

| Component | macOS location | Windows location |
|---|---|---|
| Application launcher | `MediaScanner.app/Contents/MacOS/MediaScanner` | `MediaScanner\MediaScanner.exe` |
| Bundled JRE | `MediaScanner.app/Contents/runtime/` | `MediaScanner\runtime\` |
| Application JAR | `MediaScanner.app/Contents/app/mediascanner.jar` | `MediaScanner\app\mediascanner.jar` |
| App icon | `MediaScanner.app/Contents/Resources/MediaScanner.icns` | embedded in `.exe` |
| Info.plist / manifest | `MediaScanner.app/Contents/Info.plist` | `MediaScanner\MediaScanner.exe` metadata |

---

### BuildProfile

A named Maven profile that activates the jpackage invocation for a specific platform.

| Field | Value |
|---|---|
| macOS profile ID | `package-mac` |
| Windows profile ID | `package-win` |
| Input artifact | `target/mediascanner.jar` (fat JAR from `maven-assembly-plugin`) |
| Output directory | `target/installer/` |
| Invocation | `mvn package -P package-mac` or `mvn package -P package-win` |

---

### IconAsset

Platform-specific icon files that jpackage embeds in the application bundle.

| Asset | Path | Format | Sizes required |
|---|---|---|---|
| macOS icon | `src/packaging/macos/MediaScanner.icns` | ICNS | 16, 32, 64, 128, 256, 512, 1024 px |
| Windows icon | `src/packaging/windows/MediaScanner.ico` | ICO | 16, 32, 48, 64, 128, 256 px |

**Source requirement**: Both icons derived from a single source PNG at 1024×1024 px minimum.

---

## State Transitions

```
Developer machine
      │
      ▼
mvn package -P package-mac   ──►  target/mediascanner.jar  (existing fat JAR)
      │                                      │
      │                                      ▼
      │                              jpackage (exec-maven-plugin)
      │                                      │
      ▼                                      ▼
target/installer/MediaScanner-1.0.0.dmg   (new InstallerArtifact)
      │
      ▼
Distributed to end user → drag to Applications → MediaScanner.app installed
      │
      ▼
User launches → macOS verifies (Gatekeeper warns for unsigned) → app runs
```
