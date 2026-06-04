# Implementation Plan: Native Installer Packaging

**Branch**: `003-installable-builds` | **Date**: 2026-06-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/003-installable-builds/spec.md`

## Summary

Integrate `jpackage` (JDK 14+, already present at `/usr/bin/jpackage`) into the Maven build system via two dedicated Maven profiles (`package-mac`, `package-win`). Each profile invokes `jpackage` using the fat JAR produced by the existing `maven-assembly-plugin` as input. The macOS profile produces a Universal Binary (ARM64 + x86_64) `.dmg` — this requires Azul Zulu JDK 21 Universal on the build machine so the bundled JRE is also universal. The Windows profile produces a `.msi` installer requiring WiX Toolset v3 on the build machine. Version flows automatically from `pom.xml` through the JAR manifest (already wired via `addDefaultImplementationEntries`) to `AppConfig.getAppVersion()` and the About screen. Icon assets (`MediaScanner.icns`, `MediaScanner.ico`) are added under `src/packaging/`. Installation documentation covering macOS Gatekeeper bypass and Windows prerequisites is added to `docs/INSTALL.md`.

## Technical Context

**Language/Version**: Java 21 LTS (source/target in pom.xml); current build machine has JDK 25 (Temurin) — compatible for compilation; macOS packaging requires Azul Zulu JDK 21 Universal to produce a Universal Binary runtime image.

**Primary Dependencies**: `jpackage` (ships with JDK 14+, confirmed present); `exec-maven-plugin` (new, invokes jpackage from Maven); WiX Toolset v3.x (Windows build machine prerequisite for MSI generation).

**Storage**: N/A — this feature is a build pipeline; no application data model changes.

**Testing**: Manual — install produced artifact on a clean OS instance and confirm launch, icon, version, and Start Menu/Applications entry. No unit tests applicable to the packaging pipeline.

**Target Platform**: macOS 12+ Universal Binary (ARM64 + x86_64); Windows 10 / Windows 11.

**Project Type**: Build tooling addition to an existing JavaFX desktop application.

**Performance Goals**: Installer produced in under 5 minutes on a modern development machine (SC-001, SC-002).

**Constraints**: No code signing for v1 (macOS Gatekeeper bypass documented; Windows Authenticode deferred). CI/CD integration deferred. Cross-platform builds out of scope — each platform's installer is built on its native OS.

**Scale/Scope**: One installer artifact per platform per release, invoked by a single developer.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked post-design.*

| Gate | Status | Notes |
|------|--------|-------|
| Runtime: Java 21 LTS | ✅ PASS | pom.xml targets Java 21; JDK 21 runtime bundled in installer |
| Desktop UI: JavaFX 21 | ✅ PASS | Unchanged; fat JAR bundles JavaFX native libraries |
| Build: Maven | ✅ PASS | Packaging invoked via Maven profiles |
| Packaging: jpackage | ✅ PASS | Core tool — this feature implements it |
| Target OS: Windows 10+, macOS | ✅ PASS | Both platforms targeted |
| Performance-First Architecture | ✅ N/A | Build pipeline only; no runtime code changes |
| SQLite as single source of truth | ✅ N/A | No application data model changes |
| Observability | ✅ N/A | No changes to runtime monitoring |

**Result**: No violations. Proceed.

## Project Structure

### Documentation (this feature)

```text
specs/003-installable-builds/
├── plan.md              # This file
├── research.md          # Phase 0 — packaging decisions and prerequisites
├── data-model.md        # Phase 1 — build artifact entities
├── quickstart.md        # Phase 1 — developer packaging commands reference
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code Changes

```text
pom.xml                                    # Add package-mac and package-win profiles
src/
└── packaging/
    ├── macos/
    │   └── MediaScanner.icns              # macOS app icon (multi-size ICNS)
    └── windows/
        └── MediaScanner.ico               # Windows app icon (multi-size ICO)
docs/
└── INSTALL.md                             # Gatekeeper bypass + Windows install steps
target/
└── installer/                             # jpackage output (git-ignored, mvn clean removes)
```

**Structure Decision**: All packaging assets under `src/packaging/` (platform-namespaced). Output to `target/installer/` so `mvn clean` removes it and it is excluded from version control via `.gitignore`.

## Complexity Tracking

No constitution violations requiring justification.
