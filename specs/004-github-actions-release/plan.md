# Implementation Plan: Automated Release Delivery via CI/CD Pipeline

**Branch**: `004-github-actions-release` | **Date**: 2026-06-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/004-github-actions-release/spec.md`

## Summary

Add a GitHub Actions release workflow (`.github/workflows/release.yml`) that triggers on semantic version tags (`v*.*.*`), runs parallel platform builds on macOS and Windows hosted runners, and publishes a versioned GitHub Release containing both installer artifacts and their SHA-256 checksum files — with no manual steps required. Version flows from the git tag through Maven into the packaged installer via the existing `jpackage` profiles (`package-mac`, `package-win`) already implemented in feature 003.

## Technical Context

**Language/Version**: Java 21 LTS — Maven `pom.xml` hardcodes `<version>1.0.0</version>`; the workflow overrides this at build time via `mvn versions:set -DnewVersion=<tag-version>` so the tag drives the embedded app version.

**Primary Dependencies**:
- `actions/checkout@v4` — code checkout
- `actions/setup-java@v4` — JDK provisioning per platform
- `actions/upload-artifact@v4` / `actions/download-artifact@v4` — cross-job artifact transfer
- Azul Zulu JDK 21 Universal (macOS runner) — required for Universal Binary `.dmg` (ARM64 + x86_64)
- Eclipse Temurin JDK 21 (Windows runner) — standard JDK for `.msi` production
- WiX Toolset v3 — pre-installed on `windows-latest` GitHub runners; required for MSI packaging
- `gh` CLI — pre-installed on all GitHub runners; used for release creation and overwrite

**Storage**: N/A — CI/CD pipeline only; no application data model changes.

**Testing**: Manual verification — push a test tag to a fork or test branch, confirm Release is published with both artifacts. Acceptance scenarios from spec are the test protocol.

**Target Platform**: macOS 14+ runners (`macos-latest`) for `.dmg`; Windows Server 2022 runners (`windows-latest`) for `.msi`.

**Project Type**: CI/CD pipeline addition to an existing JavaFX desktop application. No source code changes to the application itself.

**Performance Goals**: Full pipeline (checkout → build → package → publish) completes within 30 minutes (FR-010, SC-001).

**Constraints**: Code signing deferred (no macOS notarization, no Windows Authenticode). Pipeline triggered by tag push only. GitHub-hosted runners only. Single workflow file; no matrix strategy (platforms differ too much in JDK and prerequisite requirements to benefit from a matrix).

**Scale/Scope**: One release event per tag push; one workflow file; two parallel platform jobs; one publish job.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked post-design.*

| Gate | Status | Notes |
|------|--------|-------|
| Runtime: Java 21 LTS | ✅ PASS | JDK 21 provisioned on both runners; same as local build |
| Desktop UI: JavaFX 21 | ✅ PASS | Unchanged; fat JAR with bundled JavaFX natives used as jpackage input |
| Build: Maven | ✅ PASS | Maven `versions:set` + existing `package-mac`/`package-win` profiles |
| Packaging: jpackage | ✅ PASS | Existing profiles invoke jpackage; no changes to packaging logic |
| Target OS: Windows 10+, macOS | ✅ PASS | Both platform installers produced and published |
| Performance-First Architecture | ✅ N/A | Pipeline only; no runtime code changes |
| SQLite as single source of truth | ✅ N/A | No application data model changes |
| Observability | ✅ N/A | No changes to runtime monitoring |
| Development Discipline (Principle VIII) | ✅ PASS | Each user story has an independent test defined in the spec |

**Result**: No violations. Proceed.

## Project Structure

### Documentation (this feature)

```text
specs/004-github-actions-release/
├── plan.md              # This file
├── research.md          # Phase 0 — workflow design decisions
├── data-model.md        # Phase 1 — pipeline entity model
├── quickstart.md        # Phase 1 — how to cut a release
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
.github/
└── workflows/
    └── release.yml      # New: versioned release pipeline

pom.xml                  # Modified: no structural change; versions:set used at runtime
```

No application source files are modified. The workflow file is the sole deliverable.

## Phase 0: Research

See [research.md](research.md) — all design decisions resolved.

## Phase 1: Design

### Workflow Architecture

The release workflow has three jobs:

```
[tag push] → build-mac ──┐
                          ├──→ publish-release
            build-win ───┘
```

**`build-mac`** (runs on `macos-latest`):
1. Checkout code
2. Setup Azul Zulu JDK 21 Universal
3. Extract version from `GITHUB_REF_NAME` (strip `v` prefix)
4. Set Maven version: `mvn versions:set -DnewVersion=$VERSION -DgenerateBackupPoms=false`
5. Build fat JAR: `mvn package -DskipTests`
6. Package DMG: `mvn package -P package-mac -DskipTests`
7. Generate checksum: `shasum -a 256 target/installer/MediaScanner-$VERSION.dmg`
8. Upload artifact: `MediaScanner-$VERSION.dmg` + checksum file

**`build-win`** (runs on `windows-latest`):
1. Checkout code
2. Setup Eclipse Temurin JDK 21
3. Extract version from `GITHUB_REF_NAME`
4. Set Maven version
5. Build fat JAR: `mvn package -DskipTests`
6. Package MSI: `mvn package -P package-win -DskipTests`
7. Generate checksum (PowerShell): `Get-FileHash` → write `.sha256` file
8. Upload artifact: `MediaScanner-$VERSION.msi` + checksum file

**`publish-release`** (runs on `ubuntu-latest`, `needs: [build-mac, build-win]`):
1. Checkout code (for `gh` CLI auth context)
2. Download all artifacts from both build jobs
3. Check if release for this tag already exists; delete if so (FR-013)
4. Create GitHub Release: `gh release create $TAG --title "MediaScanner $VERSION" --generate-notes --latest`
5. Upload all four files: `.dmg`, `.dmg.sha256`, `.msi`, `.msi.sha256`

### Version Extraction

```bash
VERSION="${GITHUB_REF_NAME#v}"   # strips leading 'v' → "1.2.3"
```

`GITHUB_REF_NAME` is set automatically by GitHub Actions to the tag name (e.g., `v1.2.3`).

### Artifact Naming Convention

| File | Description |
|------|-------------|
| `MediaScanner-{VERSION}.dmg` | macOS installer |
| `MediaScanner-{VERSION}.dmg.sha256` | macOS installer SHA-256 checksum |
| `MediaScanner-{VERSION}.msi` | Windows installer |
| `MediaScanner-{VERSION}.msi.sha256` | Windows installer SHA-256 checksum |

### Release Overwrite Logic (FR-013)

```bash
gh release view "$TAG" &>/dev/null && gh release delete "$TAG" --yes --cleanup-tag || true
gh release create "$TAG" ...
```

The `--cleanup-tag` flag removes the tag from the remote before re-creating the release; the tag is re-applied when `gh release create` is called with the same tag name.

> **Note**: `--cleanup-tag` removes the remote tag. Since the local tag and commit still exist, `gh release create` re-applies the tag to the same commit. This is safe for re-release scenarios.

## Artifacts Generated

- [research.md](research.md) — workflow design decisions and JDK strategy
- [data-model.md](data-model.md) — pipeline entity definitions
- [quickstart.md](quickstart.md) — developer guide for cutting a release
