# Research: Automated Release Delivery via GitHub Actions

**Feature**: 004-github-actions-release | **Date**: 2026-06-04

## Decision 1: Workflow Trigger Strategy

**Decision**: Trigger on `push` to tags matching `v[0-9]+.[0-9]+.[0-9]+` (strict semver pattern).

**Rationale**: Tag-push events fire once per tag, are idempotent on re-push, and naturally separate release builds from CI builds. Using a strict pattern (digits only, no pre-release suffix) enforces FR-002 at the workflow level without additional scripting — non-matching tags simply do not trigger the workflow.

**Alternatives considered**:
- `workflow_dispatch` (manual trigger): Rejected — requires manual steps, violating FR-011.
- `on: release: types: [created]`: Rejected — requires manually creating the GitHub Release first, which is the step we are automating.
- Broader pattern `v*`: Rejected — would match pre-release tags (`v1.0.0-rc.1`) which are out of scope.

---

## Decision 2: Platform Build Strategy (Separate Jobs vs Matrix)

**Decision**: Two dedicated jobs (`build-mac`, `build-win`) rather than a matrix strategy.

**Rationale**: macOS and Windows builds differ fundamentally — different JDK vendors (Zulu Universal vs Temurin), different native extraction commands (bash vs PowerShell), and different checksum tools (`shasum` vs `Get-FileHash`). A matrix would force shared steps that need per-platform branching, producing an unreadable conditional mess. Dedicated jobs are explicit, independently debuggable, and easier to maintain.

**Alternatives considered**:
- Matrix strategy with `if:` conditionals: Rejected — platform-specific JDK vendor, shell commands, and file extensions would require `if: matrix.os == '...'` on nearly every step, defeating the purpose of a matrix.

---

## Decision 3: JDK for macOS — Azul Zulu 21 Universal

**Decision**: Use `actions/setup-java@v4` with `distribution: 'zulu'` and `java-version: '21'` on `macos-latest`.

**Rationale**: The `package-mac` Maven profile was designed for Azul Zulu JDK 21 Universal (ARM64 + x86_64) to produce a Universal Binary DMG, as established in feature 003. GitHub's `macos-latest` runner is now Apple Silicon (M-series). The `actions/setup-java` action with `distribution: 'zulu'` provisions the Azul Zulu JDK 21 for the runner's native architecture. On Apple Silicon macOS, this is the ARM64 variant. For a true Universal Binary output, the JDK bundled by jpackage must be Universal. Azul Zulu distributes a Universal Binary JDK 21 for macOS; `setup-java` with `architecture: 'x64+arm64'` (or using the Zulu-specific universal download) is the correct mechanism.

**Alternatives considered**:
- Eclipse Temurin on macOS: Rejected — Temurin does not distribute a macOS Universal Binary JDK; would produce ARM64-only DMG.
- Microsoft Build of OpenJDK: Rejected — same limitation as Temurin for Universal Binary.

---

## Decision 4: JDK for Windows — Eclipse Temurin 21

**Decision**: Use `actions/setup-java@v4` with `distribution: 'temurin'` and `java-version: '21'` on `windows-latest`.

**Rationale**: Temurin is the de facto standard for Windows CI Java builds, widely tested with jpackage/WiX workflows, and available directly via `setup-java`. No Universal Binary requirement exists for Windows.

**Alternatives considered**:
- Azul Zulu on Windows: No reason to prefer it over Temurin; Temurin is simpler and more commonly documented for Windows jpackage workflows.

---

## Decision 5: WiX Toolset on Windows Runners

**Decision**: Rely on WiX Toolset v3 pre-installed on `windows-latest` GitHub runners. No explicit installation step required.

**Rationale**: GitHub's `windows-latest` (Windows Server 2022) runners include WiX Toolset v3 in the pre-installed software list. The existing `package-win` Maven profile already targets WiX v3 (via jpackage's MSI backend). Adding a WiX installation step would add ~2–3 minutes to build time for no benefit.

**Risk**: If GitHub removes WiX from pre-installed software (they have done this before), the workflow will fail with a clear jpackage error. Mitigation: add WiX installation step if this occurs.

**Alternatives considered**:
- Explicit WiX install via Chocolatey (`choco install wixtoolset`): Available as fallback; adds ~2 minutes.

---

## Decision 6: Maven Version Override Strategy

**Decision**: Use `mvn versions:set -DnewVersion=$VERSION -DgenerateBackupPoms=false` to update `pom.xml` in the workspace before building.

**Rationale**: The fat JAR's manifest entries (`Implementation-Version`) are derived from `${project.version}` at Maven build time. Setting the project version via `versions:set` ensures the version flows correctly through the shade plugin's manifest transformer into the JAR, and then through jpackage's `--app-version` argument (also sourced from `${project.version}`) into the installer metadata. This is the cleanest single-change approach.

**Alternatives considered**:
- `-Dproject.version=X.Y.Z` on the Maven command line: Does not reliably override the POM `<version>` element for all plugins; `versions:set` is the authoritative mechanism.
- Keeping `pom.xml` at `0.0.0-SNAPSHOT` and always overriding: Would require a convention change; current workflow works with the existing `pom.xml` structure.

---

## Decision 7: Release Overwrite on Duplicate Tag (FR-013)

**Decision**: In the publish job, attempt `gh release view "$TAG"` and if found, run `gh release delete "$TAG" --yes` before creating the new release. Do NOT use `--cleanup-tag` to avoid remote tag deletion.

**Rationale**: Deleting only the release (not the tag) is safer — it avoids the race condition where `--cleanup-tag` removes the remote tag and a concurrent process could observe the repository in a tag-less state. The tag push that triggered this workflow already exists; we only need to replace the release object.

**Alternatives considered**:
- `gh release edit`: Cannot replace attached artifacts reliably; better for metadata-only edits.
- `--cleanup-tag` + recreate: Introduces a brief window where the remote tag does not exist.

---

## Decision 8: Checksum File Format

**Decision**: SHA-256 checksum in `<hash>  <filename>` format (two-space separator), matching `shasum -a 256` output on macOS and equivalent format on Windows.

**Rationale**: Standard `shasum`-compatible format allows users to verify with `shasum -c` on macOS/Linux. Windows users can verify with `Get-FileHash` and manual comparison. A README or release notes can document the verification procedure.

**File naming**: `MediaScanner-{VERSION}.dmg.sha256` and `MediaScanner-{VERSION}.msi.sha256`.

---

## Decision 9: Publish Job Runner

**Decision**: Use `ubuntu-latest` for the `publish-release` job.

**Rationale**: The publish job only runs `gh` CLI commands and downloads GitHub Actions artifacts — no platform-specific build tools needed. Ubuntu is the fastest and cheapest GitHub runner. The `gh` CLI is pre-installed on all runners.
