# Tasks: Automated Release Delivery via CI/CD Pipeline

**Input**: Design documents from `specs/004-github-actions-release/`

**Prerequisites**: plan.md ✅ | spec.md ✅ | research.md ✅ | data-model.md ✅ | quickstart.md ✅

**Sole deliverable**: `.github/workflows/release.yml` — a single new file containing the complete release pipeline. No application source files are modified.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (no dependency on incomplete tasks)
- **[Story]**: User story this task belongs to (US1/US2/US3)

---

## Phase 1: Setup

**Purpose**: Create the workflow file skeleton with trigger and job stubs so subsequent phases have a valid starting point to fill in.

- [x] T001 Create `.github/workflows/` directory if it does not exist, and create `.github/workflows/release.yml` with the workflow name (`MediaScanner Release`), semantic version tag trigger (`on: push: tags: ['v[0-9]+.[0-9]+.[0-9]+']`), and empty stubs for the three jobs: `build-mac`, `build-win`, `publish-release`
- [x] T002 Add `permissions: contents: write` at the workflow level in `.github/workflows/release.yml` so `GITHUB_TOKEN` has the write access required by `gh release create`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Implement the shared version extraction and Maven version-wiring steps that both platform build jobs depend on. No job is independently runnable until these steps exist.

**⚠️ CRITICAL**: Both `build-mac` and `build-win` jobs require these steps before their platform-specific packaging steps can be added.

- [x] T003 Add a `Set version from tag` step to the `build-mac` job in `.github/workflows/release.yml` that exports `VERSION="${GITHUB_REF_NAME#v}"` as a job environment variable (strips the leading `v` from the tag name)
- [x] T004 [P] Add the same `Set version from tag` step to the `build-win` job in `.github/workflows/release.yml` using the PowerShell equivalent: `$VERSION = "${{ github.ref_name }}".TrimStart('v')` exported as `$env:VERSION`
- [x] T005 Add a `Set Maven version` step after version extraction in `build-mac` in `.github/workflows/release.yml`: `mvn versions:set -DnewVersion=$VERSION -DgenerateBackupPoms=false` — ensures `${project.version}` in `pom.xml` matches the release tag before the fat JAR and DMG are built
- [x] T006 [P] Add the equivalent `Set Maven version` step to `build-win` in `.github/workflows/release.yml`: `mvn versions:set "-DnewVersion=$env:VERSION" -DgenerateBackupPoms=false`

**Checkpoint**: Both build jobs now have version extraction and Maven version wiring. Platform packaging can proceed.

---

## Phase 3: User Story 1 — Developer Tags a Version and Gets a Published Release (Priority: P1) 🎯 MVP

**Goal**: Pushing a `v*.*.*` tag triggers an automated pipeline that produces and publishes a versioned GitHub Release with both platform installers and checksums — no manual steps.

**Independent Test**: Push tag `v0.0.1` to a fork or the main repo. Within 30 minutes a GitHub Release titled `MediaScanner 0.0.1` must appear with 4 files: `.dmg`, `.dmg.sha256`, `.msi`, `.msi.sha256`. The version displayed in the installed application must read `0.0.1`.

### Implementation for User Story 1

- [x] T007 [P] [US1] Implement the full `build-mac` job body in `.github/workflows/release.yml`: add `actions/checkout@v4`, `actions/setup-java@v4` with `distribution: zulu` and `java-version: 21`, fat JAR build step (`mvn package -DskipTests`), DMG packaging step (`mvn package -P package-mac -DskipTests`), SHA-256 checksum generation (`shasum -a 256 target/installer/MediaScanner-$VERSION.dmg > MediaScanner-$VERSION.dmg.sha256`), and `actions/upload-artifact@v4` uploading both `target/installer/MediaScanner-$VERSION.dmg` and `MediaScanner-$VERSION.dmg.sha256`
- [x] T008 [P] [US1] Implement the full `build-win` job body in `.github/workflows/release.yml`: add `actions/checkout@v4`, `actions/setup-java@v4` with `distribution: temurin` and `java-version: 21`, fat JAR build step (`mvn package -DskipTests`), MSI packaging step (`mvn package -P package-win -DskipTests`), SHA-256 checksum generation (PowerShell `Get-FileHash` writing `<hash>  <filename>` to `MediaScanner-$VERSION.msi.sha256`), and `actions/upload-artifact@v4` uploading both installer and checksum
- [x] T009 [US1] Implement the `publish-release` job in `.github/workflows/release.yml`: set `needs: [build-mac, build-win]`, use `ubuntu-latest` runner, add `actions/checkout@v4`, `actions/download-artifact@v4` (download all 4 files), overwrite check (`gh release view "$TAG" &>/dev/null && gh release delete "$TAG" --yes || true`), and `gh release create "$TAG" --title "MediaScanner $VERSION" --generate-notes --latest MediaScanner-$VERSION.dmg MediaScanner-$VERSION.dmg.sha256 MediaScanner-$VERSION.msi MediaScanner-$VERSION.msi.sha256` in `.github/workflows/release.yml`
- [ ] T010 [US1] Acceptance test: push a test tag (`git tag v0.0.1 && git push origin v0.0.1`) to the repository, monitor the Actions tab, and verify the GitHub Release is published within 30 minutes with all 4 artifacts attached and the correct version label

**Checkpoint**: After T010 passes, User Story 1 is complete. A full release pipeline exists end-to-end.

---

## Phase 4: User Story 2 — End User Downloads and Installs from a Release (Priority: P2)

**Goal**: A user visiting the GitHub Releases page can find, download, and successfully install the correct-version application on their OS with no confusion about which file to choose.

**Independent Test**: As an unauthenticated browser user, navigate to the Releases page after a successful pipeline run. Verify both platform installers are present with version-stamped filenames, the release is marked Latest, and each installer produces a working application displaying the correct version number.

### Implementation for User Story 2

- [ ] T011 [US2] Verify artifact filenames produced by `build-mac` include the version: confirm jpackage `--name MediaScanner` + `--app-version $VERSION` outputs `target/installer/MediaScanner-{VERSION}.dmg` — adjust the upload step in `.github/workflows/release.yml` if the filename differs from the expected pattern
- [ ] T012 [P] [US2] Verify artifact filenames produced by `build-win` include the version: confirm jpackage `--name MediaScanner` + `--app-version $VERSION` outputs `target/installer/MediaScanner-{VERSION}.msi` on the Windows runner — adjust the upload step in `.github/workflows/release.yml` if needed
- [ ] T013 [US2] Manual install acceptance test (macOS): download `MediaScanner-{VERSION}.dmg` from the GitHub Releases page on a clean macOS machine, mount and install, launch the application, and confirm the version shown in the About screen or window title matches the release tag version
- [ ] T014 [P] [US2] Manual install acceptance test (Windows): download `MediaScanner-{VERSION}.msi` from the GitHub Releases page on a Windows 10 or 11 machine, run the installer, launch the application, and confirm the displayed version matches the release tag version

**Checkpoint**: After T013 and T014 pass, User Story 2 is complete. Both platform installers install and show correct versions.

---

## Phase 5: User Story 3 — Build Failure Alerts the Developer (Priority: P3)

**Goal**: When a build fails, no partial release is published and the developer is immediately informed via the repository's CI status. Recovery is straightforward.

**Independent Test**: Introduce a deliberate syntax error in a source file on a test branch, push a version tag, and confirm: (1) the pipeline run shows as failed in the Actions tab, (2) no GitHub Release is created or modified, (3) the error is visible in the failed job's log.

### Implementation for User Story 3

- [x] T015 [US3] Verify that `publish-release` is implicitly skipped when either `build-mac` or `build-win` fails: confirm that `needs: [build-mac, build-win]` without an explicit `if:` condition causes GitHub Actions to skip the publish job automatically on upstream failure — no additional `if: success()` annotation is required (it is the default behaviour)
- [ ] T016 [US3] Failure scenario test: on a scratch branch introduce a deliberate compile error (e.g., remove a closing brace in `Launcher.java`), commit, push a test tag (e.g., `v0.0.2-test`), confirm the `build-mac` and/or `build-win` jobs fail in the Actions UI, confirm `publish-release` is skipped, and confirm no GitHub Release for that tag exists
- [ ] T017 [US3] Cleanup after failure test: delete the test tag locally and remotely (`git tag -d v0.0.2-test && git push origin :v0.0.2-test`), revert the deliberate error, and verify the quickstart.md recovery steps in `specs/004-github-actions-release/quickstart.md` are accurate and complete

**Checkpoint**: After T015–T017 pass, User Story 3 is complete. Failure handling is verified.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Hardening tasks that apply across all user stories.

- [x] T018 [P] Add workflow-level `concurrency` configuration to `.github/workflows/release.yml`: `concurrency: group: release-${{ github.ref }} cancel-in-progress: false` — prevents duplicate pipeline runs if the same tag is pushed twice rapidly, without cancelling an in-progress release build
- [x] T019 [P] Add explicit `GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}` environment variable to the `publish-release` job in `.github/workflows/release.yml` so the `gh` CLI is authenticated without relying on implicit token discovery
- [ ] T020 Run the full acceptance test from `specs/004-github-actions-release/quickstart.md` end-to-end: tag, push, wait for pipeline, verify release on Releases page, download and install both platforms, verify checksum files, confirm version display in installed application

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 completion — BLOCKS all user story phases
- **User Story 1 (Phase 3)**: Depends on Phase 2 — core pipeline implementation
- **User Story 2 (Phase 4)**: Depends on Phase 3 (T010 must pass — a working release must exist to run install tests)
- **User Story 3 (Phase 5)**: Depends on Phase 3 (the publish job must exist to verify failure skipping)
- **Polish (Phase 6)**: Depends on all user story phases

### User Story Dependencies

- **US1 (P1)**: Depends on Foundational only — no cross-story dependencies
- **US2 (P2)**: Depends on US1 being testable (T010 passed) — needs a real release to download
- **US3 (P3)**: Depends on the publish job existing (T009) — can run in parallel with US2

### Within Each User Story

- T007 and T008 are independent and can run in parallel (different job sections of the same YAML file)
- T009 depends on T007 and T008 (publish job references outputs from both build jobs)
- T013 and T014 (install tests) are independent and can run in parallel

### Parallel Opportunities

- T003 and T004 (version extraction in both jobs) — parallel
- T005 and T006 (Maven version set in both jobs) — parallel
- T007 and T008 (build-mac and build-win job bodies) — parallel
- T011 and T012 (filename verification on both platforms) — parallel
- T013 and T014 (install acceptance tests) — parallel on different machines
- T018 and T019 (polish tasks) — parallel

---

## Parallel Example: User Story 1 Core Build Jobs

```bash
# T007 and T008 can be written in parallel (different job sections of release.yml):
Task T007: "Implement full build-mac job body in .github/workflows/release.yml"
Task T008: "Implement full build-win job body in .github/workflows/release.yml"

# T009 requires both T007 and T008 to be complete first:
Task T009: "Implement publish-release job in .github/workflows/release.yml"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001–T002)
2. Complete Phase 2: Foundational (T003–T006)
3. Complete Phase 3: User Story 1 (T007–T010)
4. **STOP and VALIDATE**: Confirm end-to-end release pipeline works (T010)
5. Demo: tag → published GitHub Release with 4 artifacts in under 30 minutes

### Incremental Delivery

1. Setup + Foundational → Workflow skeleton exists, version wiring works
2. User Story 1 (T007–T010) → Full pipeline works end-to-end, MVP achieved ✅
3. User Story 2 (T011–T014) → Install experience verified on both platforms
4. User Story 3 (T015–T017) → Failure handling confirmed, no partial releases
5. Polish (T018–T020) → Hardening and final acceptance

---

## Notes

- [P] tasks touch different sections of the same YAML file — coordinate with care to avoid conflicts when working in parallel
- All test/verification tasks require an actual GitHub repository with Actions enabled; local testing of the workflow is not possible
- The `GITHUB_TOKEN` is automatically available in GitHub Actions; no manual secret setup is required
- WiX Toolset v3 is pre-installed on `windows-latest` runners; if the MSI build fails with a WiX error, install it explicitly via Chocolatey (`choco install wixtoolset`) as a fallback (see research.md Decision 5)
- Azul Zulu JDK 21 Universal on the macOS runner may require `architecture: 'x64+arm64'` in `setup-java` to ensure the bundled JRE is a Universal Binary — verify the produced DMG runs on both Intel and Apple Silicon Macs (T013)
- Delete test tags after verification to keep the Releases page clean
