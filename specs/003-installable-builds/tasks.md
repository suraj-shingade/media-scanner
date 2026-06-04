# Tasks: Native Installer Packaging

**Input**: Design documents from `specs/003-installable-builds/`

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, quickstart.md ✅

**Tests**: No test tasks — packaging pipeline changes are verified by manual installation, not unit tests.

**Organization**: Tasks grouped by user story. US1 (macOS) and US2 (Windows) are both P1 and can be worked in parallel once Phase 2 is complete (different build machines).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no shared dependencies)
- **[Story]**: Which user story this task belongs to (US1–US4)
- Exact file paths in every description

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Create packaging asset directories and exclude installer output from version control.

- [x] T001 Create packaging asset directories: `src/packaging/macos/` and `src/packaging/windows/`
- [x] T002 [P] Add `target/installer/` entry to project root `.gitignore` (create `.gitignore` if missing)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Icon assets and fat JAR verification must be complete before any packaging profile can run.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [x] T003 Create macOS icon `src/packaging/macos/MediaScanner.icns` — use `sips` + `iconutil` commands from `specs/003-installable-builds/quickstart.md` (Icon Creation section) starting from a 1024×1024 source PNG; if no source PNG exists, create a minimal placeholder using `sips -s format icns /System/Library/CoreServices/CoreTypes.bundle/Contents/Resources/GenericApplicationIcon.icns --out src/packaging/macos/MediaScanner.icns`
- [x] T004 [P] Create Windows icon `src/packaging/windows/MediaScanner.ico` — use ImageMagick `convert` command from `specs/003-installable-builds/quickstart.md` from the same source PNG; if ImageMagick unavailable on macOS, copy a placeholder `.ico` from any Windows-format icon source
- [x] T005 [P] Run `mvn package -DskipTests` and confirm `target/mediascanner.jar` is produced; note the exact JAR size as baseline; this confirms the fat JAR input for jpackage is healthy before adding packaging profiles

**Checkpoint**: Icon assets in place, fat JAR verified — packaging profile work can now begin.

---

## Phase 3: User Story 1 - Build macOS Installer (Priority: P1) 🎯 MVP

**Goal**: `mvn package -P package-mac -DskipTests` produces `target/installer/MediaScanner-<version>.dmg` that installs on macOS 12+ without a pre-existing JVM.

**Independent Test**: Run the command on macOS with Azul Zulu JDK 21 Universal as `JAVA_HOME`; mount the DMG; drag app to Applications; bypass Gatekeeper; confirm launch.

### Implementation for User Story 1

- [x] T006 [US1] Add `package-mac` Maven profile to `pom.xml` — insert after the existing `<profiles>` `<profile id="release">` block:
  - Plugin: `org.codehaus.mojo:exec-maven-plugin:3.2.0`, goal `exec:exec`, bound to `package` phase
  - Executable: `${env.JAVA_HOME}/bin/jpackage` (uses `JAVA_HOME` so the Zulu 21 Universal JDK controls the bundled JRE)
  - Arguments (each in a separate `<argument>` element): `--type`, `dmg`, `--name`, `MediaScanner`, `--app-version`, `${project.version}`, `--input`, `${project.build.directory}`, `--main-jar`, `mediascanner.jar`, `--main-class`, `com.mediascanner.app.MediaScannerApp`, `--icon`, `src/packaging/macos/MediaScanner.icns`, `--dest`, `${project.build.directory}/installer`, `--mac-package-identifier`, `com.mediascanner.app`, `--mac-package-name`, `MediaScanner`, `--java-options`, `--add-opens=java.base/java.lang=ALL-UNNAMED`, `--java-options`, `-Xms64m`, `--java-options`, `-Xmx2g`
  - Add `<skipTests>true</skipTests>` handling: profile must run after `maven-assembly-plugin` produces the fat JAR (assembly plugin already bound to `package` phase — exec goal executes after it if declared later in pom.xml)
- [x] T007 [US1] Set `JAVA_HOME` to Azul Zulu JDK 21 Universal install path; run `mvn package -P package-mac -DskipTests`; verify `target/installer/MediaScanner-1.0.0.dmg` (or matching version) is created within 5 minutes (SC-001); if `jpackage` errors mention "Invalid version", ensure pom.xml `<version>` contains no `-SNAPSHOT` suffix
- [x] T008 [US1] Mount `target/installer/MediaScanner-1.0.0.dmg`; drag `MediaScanner.app` to Applications; right-click → Open to bypass Gatekeeper; confirm app launches, title bar shows "MediaScanner", and app icon in Dock matches `MediaScanner.icns`

**Checkpoint**: macOS installer fully functional and independently verified.

---

## Phase 3b: Bugfix — JavaFX Runtime Missing in macOS Installer

**Root cause**: jpackage builds the bundled JRE via `jlink` from whichever JDK is on `JAVA_HOME`. Standard JDKs (Temurin, Microsoft, Zulu non-FX) contain no JavaFX modules, so `jlink` produces a JRE without `libglass.dylib` / `libjavafx_*.dylib`. The fat JAR contains those `.dylib` files internally but they are inside the JAR (not on `java.library.path`), so `System.loadLibrary("glass")` fails → crash.

**Fix**: (1) Switch `JAVA_HOME` to BellSoft Liberica JDK 21 Full which bundles OpenJFX modules. (2) Add `--add-modules javafx.controls,javafx.fxml,javafx.swing,javafx.graphics` to the jpackage invocation so `jlink` explicitly includes those modules (and their native libs) in the bundled JRE.

- [x] T020 Fix JavaFX crash: remove `--add-modules` from both jpackage profiles (it conflicts with fat JAR classpath loading); add `extract-javafx-natives-mac` exec step to unzip JavaFX `.dylib` files from fat JAR into `target/` before jpackage runs; add `--java-options -Djava.library.path=$APPDIR` so the JVM finds the extracted natives at runtime; apply equivalent pattern to `package-win` profile; update `research.md` and `quickstart.md` to document Liberica JDK 21 Full requirement and add crash troubleshooting entry
- [x] T021 Installed Liberica JDK 21 Full; rebuilt with `JAVA_HOME=/Library/Java/JavaVirtualMachines/liberica-jdk-21-full.jdk/Contents/Home mvn clean package -P package-mac -DskipTests`; confirmed app launches (MediaScanner UI ready log line observed); crash resolved

---

## Phase 3c: Bugfix — `java -jar` Direct Execution

**Problem 1**: `java -jar target/mediascanner-1.0.0.jar` → "no main manifest attribute"
- The maven-jar-plugin manifest does not include `<mainClass>`, so the thin JAR has no `Main-Class` entry.

**Problem 2**: `java -jar target/mediascanner.jar` → "JavaFX runtime components are missing"
- The fat JAR has `Main-Class: com.mediascanner.app.MediaScannerApp` and contains the JavaFX `.dylib` files at its root, but they are embedded inside the JAR archive and therefore never on `java.library.path` when using `java -jar`.

**Fix**: Introduce `com.mediascanner.app.Launcher` as the new `Main-Class` for both JARs:
- When invoked from a fat JAR (`java -jar mediascanner.jar`): extracts JavaFX native libs from the JAR to a temp dir, relaunches the JVM with `-Djava.library.path=<tempdir>`. On the re-launched invocation (flag set), delegates straight to `MediaScannerApp.main()`.
- When invoked from IDE / `mvn javafx:run` (not a JAR path): delegates directly to `MediaScannerApp.main()` — JavaFX module-path is already configured by the plugin.
- When invoked from the thin JAR (`mediascanner-1.0.0.jar`): no native libs are embedded; prints a clear "use mediascanner.jar" error.
- The jpackage `--main-class` arguments remain hardcoded to `MediaScannerApp` — the installer has its own native-lib setup via `$APPDIR` and must not re-enter Launcher.

- [ ] T022 Create `src/main/java/com/mediascanner/app/Launcher.java`: subprocess-relaunch launcher that (1) if `mediascanner.launched` system property is set → call `MediaScannerApp.main(args)`; (2) if not running from a `.jar` code source → call `MediaScannerApp.main(args)` (IDE/javafx:run path); (3) otherwise extract all JavaFX `.dylib`/`.dll` resources from the classpath root to a temp dir and relaunch the JVM via `ProcessBuilder` with `-Dmediascanner.launched=true -Djava.library.path=<tempdir> --add-opens java.base/java.lang=ALL-UNNAMED -jar <current-jar-path>`, exiting with the child's exit code; include a guard that prints a helpful error and exits if `MediaScannerApp` class cannot be found on the classpath (thin-JAR detection)
- [ ] T023 Update `pom.xml`: (1) change `<main.class>` property at line 21 from `com.mediascanner.app.MediaScannerApp` to `com.mediascanner.app.Launcher`; (2) add `<mainClass>${main.class}</mainClass>` inside the maven-jar-plugin `<manifest>` block so `mediascanner-1.0.0.jar` gains a `Main-Class` entry; note: jpackage `--main-class` args at lines ~325 and ~402 must stay hardcoded as `com.mediascanner.app.MediaScannerApp`
- [ ] T024 Run `mvn clean package -DskipTests`; verify `java -jar target/mediascanner.jar` launches the app successfully; verify `java -jar target/mediascanner-1.0.0.jar` prints the "use mediascanner.jar" error; verify `mvn javafx:run` still launches normally

---

## Phase 4: User Story 2 - Build Windows Installer (Priority: P1)

**Goal**: `mvn package -P package-win -DskipTests` produces `target\installer\MediaScanner-<version>.msi` that installs on Windows 10/11 without a pre-existing JVM.

**Independent Test**: Run the command on a Windows machine with JDK 21 and WiX Toolset v3 on PATH; run the MSI; confirm Start Menu entry and app launch.

### Implementation for User Story 2

- [x] T009 [US2] Add `package-win` Maven profile to `pom.xml` — insert alongside the `package-mac` profile:
  - Plugin: `org.codehaus.mojo:exec-maven-plugin:3.2.0`, goal `exec:exec`, bound to `package` phase
  - Executable: `${env.JAVA_HOME}/bin/jpackage`
  - Arguments: `--type`, `msi`, `--name`, `MediaScanner`, `--app-version`, `${project.version}`, `--input`, `${project.build.directory}`, `--main-jar`, `mediascanner.jar`, `--main-class`, `com.mediascanner.app.MediaScannerApp`, `--icon`, `src/packaging/windows/MediaScanner.ico`, `--dest`, `${project.build.directory}/installer`, `--win-dir-chooser`, `--win-menu`, `--win-shortcut`, `--win-menu-group`, `MediaScanner`, `--win-upgrade-uuid`, `a1b2c3d4-e5f6-7890-abcd-ef1234567890`, `--java-options`, `--add-opens=java.base/java.lang=ALL-UNNAMED`, `--java-options`, `-Xms64m`, `--java-options`, `-Xmx2g`
  - The `--win-upgrade-uuid` value (`a1b2c3d4-e5f6-7890-abcd-ef1234567890`) MUST remain constant across all future releases
- [ ] T010 [US2] On Windows build machine: confirm JDK 21 is `JAVA_HOME` (`java -version` shows 21) and `candle /?` shows WiX v3.x; run `mvn package -P package-win -DskipTests`; verify `target\installer\MediaScanner-1.0.0.msi` is produced within 5 minutes (SC-002)
- [ ] T011 [US2] Run the MSI on Windows 10+; complete installation wizard; verify Start Menu entry "MediaScanner" exists under the MediaScanner group; launch the app; confirm it opens correctly without a JVM pre-installed

**Checkpoint**: Windows installer fully functional and independently verified.

---

## Phase 5: User Story 3 - Reproducible Version-Stamped Build (Priority: P2)

**Goal**: Version string appears consistently in the installer file name, the About screen, and OS-level app metadata (Info.plist / Add/Remove Programs).

**Independent Test**: Build with an explicit version; check all three locations show the same version string with zero discrepancy (SC-005).

### Implementation for User Story 3

- [x] T012 [US3] Open `pom.xml` and confirm `maven-jar-plugin` block contains `<addDefaultImplementationEntries>true</addDefaultImplementationEntries>` (already present at line ~220); open `src/main/java/com/mediascanner/config/AppConfig.java` lines 190–193 and confirm `getAppVersion()` reads `getPackage().getImplementationVersion()` with `"1.0.0"` fallback — no code changes needed if both confirmed
- [x] T013 [US3] Build macOS installer with current version (e.g., `1.0.0`); verify three locations match: (1) `target/installer/MediaScanner-1.0.0.dmg` filename, (2) Help → About shows "Version 1.0.0", (3) `plutil -p MediaScanner.app/Contents/Info.plist | grep CFBundleShortVersionString` shows `1.0.0`
- [x] T014 [P] [US3] Temporarily change `<version>` in `pom.xml` to `1.0.1`; rebuild with `mvn package -P package-mac -DskipTests`; verify filename becomes `MediaScanner-1.0.1.dmg` and About screen shows `1.0.1`; revert `pom.xml` to original version after confirmation

**Checkpoint**: Version flows correctly from pom.xml through all three visibility surfaces.

---

## Phase 6: User Story 4 - Single Maven Command Build (Priority: P2)

**Goal**: The complete pipeline (compile → assemble fat JAR → jpackage) is triggered by one Maven command with no manual intermediate steps (SC-006).

**Independent Test**: From a clean project state, execute the single documented command and verify the installer is produced end-to-end.

### Implementation for User Story 4

- [x] T015 [US4] Verify both `package-mac` and `package-win` profiles in `pom.xml` bind the exec-maven-plugin goal to the `package` lifecycle phase and are declared after the `maven-assembly-plugin` in the plugins list; this ordering ensures jpackage runs after the fat JAR is assembled within the same `mvn package` invocation
- [x] T016 [US4] Run `mvn clean package -P package-mac -DskipTests` from the project root (a completely clean build); confirm the installer is produced with no additional manual steps; this validates SC-006 end-to-end

**Checkpoint**: All four user stories are complete and independently verifiable.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Documentation and final verification.

- [x] T017 [P] Create `docs/INSTALL.md` with the following sections: (1) macOS Installation — system requirements (macOS 12+, any CPU architecture), install steps, Gatekeeper bypass (right-click → Open, step-by-step with screenshots note), (2) Windows Installation — system requirements (Windows 10/11 x64), MSI install steps, (3) Build Prerequisites — macOS build machine requirements (Azul Zulu JDK 21 Universal, Maven 3.8+), Windows build machine requirements (JDK 21, WiX Toolset v3, Maven 3.8+)
- [x] T018 [P] Update `specs/003-installable-builds/quickstart.md` if any actual pom.xml profile IDs, argument syntax, or file paths discovered during T006–T016 differ from the documented commands; ensure the troubleshooting table reflects real errors encountered
- [x] T019 Run the complete final verification on macOS: `mvn clean package -P package-mac -DskipTests` → mount DMG → install → bypass Gatekeeper → launch → confirm version in About → all SC-001 through SC-006 satisfied

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 — BLOCKS both US1 and US2
- **US1 macOS (Phase 3)**: Depends on Phase 2 — runs on macOS machine
- **US2 Windows (Phase 4)**: Depends on Phase 2 — runs on Windows machine; **can run in parallel with US1** (different build machines)
- **US3 Version (Phase 5)**: Depends on at least US1 (Phase 3) being complete
- **US4 Command (Phase 6)**: Depends on US1 (Phase 3) being complete; verifies profile design
- **Polish (Phase 7)**: Depends on all user stories complete

### User Story Dependencies

- **US1 (P1)**: Unblocked after Phase 2 — macOS build machine
- **US2 (P1)**: Unblocked after Phase 2 — Windows build machine (parallel with US1)
- **US3 (P2)**: Depends on US1 (needs a working macOS installer to verify version in all three places)
- **US4 (P2)**: Depends on US1 (needs the package-mac profile working to test single-command invocation)

### Within Each Phase

- T003 and T004 are [P] — icon assets created simultaneously (different files)
- T006 and T009 are [P] — both profile blocks added to pom.xml (sequential within same file; coordinate to avoid merge conflicts if done on separate machines)
- T007 and T010 are independent (different OS machines) — can run in parallel
- T013 and T014 are mostly independent — T014 depends on T013 confirming the baseline works

---

## Parallel Opportunities

```bash
# Phase 2: Run T003, T004, T005 together
Task T003: Create src/packaging/macos/MediaScanner.icns
Task T004: Create src/packaging/windows/MediaScanner.ico
Task T005: Verify mvn package -DskipTests produces target/mediascanner.jar

# Phase 3+4: After Phase 2 — run on separate machines simultaneously
macOS machine: T006 → T007 → T008  (US1)
Windows machine: T009 → T010 → T011  (US2)

# Phase 7: Run T017, T018 together
Task T017: Create docs/INSTALL.md
Task T018: Update specs/003-installable-builds/quickstart.md
```

---

## Implementation Strategy

### MVP First (US1 macOS Only)

1. Complete Phase 1: Setup (T001, T002)
2. Complete Phase 2: Foundational (T003, T004, T005)
3. Complete Phase 3: US1 macOS Installer (T006, T007, T008)
4. **STOP and VALIDATE**: macOS installer installs and runs ✅
5. Continue with Windows and remaining stories

### Incremental Delivery

1. Phase 1 + Phase 2 → asset infrastructure ready
2. Phase 3 (US1) → macOS installer deliverable (MVP)
3. Phase 4 (US2) → Windows installer deliverable (in parallel with US1 if two machines available)
4. Phase 5 (US3) → version stamping confirmed
5. Phase 6 (US4) → single-command build confirmed
6. Phase 7 → documentation complete and final end-to-end verified

---

## Notes

- **[P]** tasks = different files or different machines, no shared state
- `--win-upgrade-uuid` in T009 MUST remain `a1b2c3d4-e5f6-7890-abcd-ef1234567890` permanently — changing it makes Windows treat each version as a separate unrelated app
- SNAPSHOT versions (`1.0.0-SNAPSHOT`) are rejected by jpackage — ensure `pom.xml` `<version>` is a clean SemVer (e.g., `1.0.0`) before running packaging profiles
- Icon assets (T003, T004) are one-time setup; they do not need to be regenerated per release unless the icon design changes
- macOS packaging requires `JAVA_HOME` pointing to Azul Zulu JDK 21 Universal — not the system default JDK (which may be a different version or architecture-specific)
- Commit after T006 + T009 (pom.xml profile additions) before testing on either machine to ensure both machines work from the same pom.xml
