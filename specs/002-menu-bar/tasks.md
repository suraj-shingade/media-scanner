---
description: "Task list for Application Menu Bar — 002-menu-bar"
---

# Tasks: Application Menu Bar

**Input**: Design documents from `specs/002-menu-bar/`

**Prerequisites**: plan.md ✅ | spec.md ✅ | data-model.md ✅ | research.md ✅ | contracts/ ✅ | quickstart.md ✅

**Stack**: Java 21 LTS · JavaFX 21 · Maven · existing project (no new dependencies)

**Organization**: Tasks grouped by user story for independent implementation and testing.

---

## Format: `- [ ] [ID] [P?] [Story?] Description — file path`

- **[P]**: Can run in parallel (different files, no shared state)
- **[Story]**: Which user story this task belongs to (US1–US6)
- File paths relative to repository root

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Introduce `AppStateManager` and `ScreenNavigator` — the two foundational components all user stories depend on. Refactor `MediaScannerApp` to use the new persistent root `BorderPane` layout.

- [X] T001 Create `AppState` enum (IDLE, RUNNING, PAUSED, COMPLETED) and `AppStateManager` singleton with `ObjectProperty<AppState>`, `lastJobTargetPath`, `isJobActive`, and `hasCompletedJob` bindings — `src/main/java/com/mediascanner/engine/AppStateManager.java`
- [X] T002 [P] Create `ScreenNavigator` service: holds reference to root `BorderPane`, `navigateTo(ScreenType)` replaces center pane, `ScreenType` enum (CONFIGURATION, DASHBOARD, SUMMARY) — `src/main/java/com/mediascanner/ui/ScreenNavigator.java`
- [X] T003 [P] Update `AppConfig.java` to add `ui.dark.mode` boolean field (default `false`), `isDarkMode()`, `setDarkMode()`, and `getAppVersion()` (reads from JAR manifest, falls back to "1.0.0") — `src/main/java/com/mediascanner/config/AppConfig.java`
- [X] T004 [P] Add `maven-jar-plugin` configuration to `pom.xml` with `addDefaultImplementationEntries=true` and `addDefaultSpecificationEntries=true` so `Implementation-Version` is set in the JAR manifest — `pom.xml`
- [X] T005 Refactor `MediaScannerApp.start()`: create one persistent root `BorderPane`; instantiate `AppStateManager`, `ScreenNavigator`, `AppConfig`; set initial center to `main.fxml` via `ScreenNavigator`; never swap the `Scene` after initial load — `src/main/java/com/mediascanner/app/MediaScannerApp.java`
- [X] T006 [P] Rename `src/main/resources/css/mediascanner.css` to `mediascanner-light.css`; create `mediascanner-dark.css` with dark colour overrides for all `.stats-panel`, `.header-bar`, `.primary-button`, and root background rules — `src/main/resources/css/mediascanner-light.css`, `src/main/resources/css/mediascanner-dark.css`
- [X] T007 [P] Create `DarkModeManager.java`: holds `Scene` reference; `toggle()` swaps stylesheets and calls `AppConfig.setDarkMode()`; `apply(boolean dark)` clears and re-adds correct CSS; called from `MediaScannerApp.start()` on startup to restore persisted preference — `src/main/java/com/mediascanner/ui/DarkModeManager.java`
- [X] T008 Wire `ScanEngine` to call `AppStateManager.setState(AppState.RUNNING)` on start, `PAUSED` on pause, `RUNNING` on resume, `COMPLETED` on job completion or stop — all via `Platform.runLater()` — `src/main/java/com/mediascanner/engine/ScanEngine.java`

**Checkpoint**: `mvn clean compile` succeeds. `AppStateManager.getInstance()` is non-null. `ScreenNavigator` switches panes without crashing.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Build the `MenuBarController` skeleton with all menus, items, shortcuts, and state bindings wired — but menu actions are no-ops until each user story phase implements the handlers. Also update the three existing controllers to use `ScreenNavigator` instead of scene-swapping.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T009 Create `MenuBarController.java` skeleton: construct `MenuBar` with File, Edit, Job, View, Tools, Help menus per `contracts/menu-structure.md`; call `menuBar.setUseSystemMenuBar(true)` on macOS detection; attach `MenuBar` to root `BorderPane.top` in `MediaScannerApp`; inject `AppStateManager`, `ScreenNavigator`, `AppConfig`, `DarkModeManager` — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T010 [P] Wire all `disableProperty()` bindings in `MenuBarController` per the state table in `contracts/menu-structure.md`: Start bound to `!(IDLE||COMPLETED)`, Pause bound to `!RUNNING`, Resume bound to `!PAUSED`, Stop bound to `!(RUNNING||PAUSED)`, Export bound to `!(RUNNING||PAUSED||COMPLETED)`, Dashboard bound to `IDLE`, Summary bound to `!(COMPLETED)` — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T011 [P] Register all `KeyCombination` accelerators per `contracts/menu-structure.md` (use `"shortcut+N"`, `"shortcut+O"`, `"shortcut+COMMA"`, `"shortcut+Q"`, `"shortcut+ENTER"`, `"shortcut+P"`, `"shortcut+R"`, `"shortcut+PERIOD"`, `"shortcut+E"`, `"shortcut+1"`, `"shortcut+2"`, `"shortcut+3"`, `"shortcut+D"`, `"F1"`) — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T012 [P] Refactor `MainController.java`: replace all `Stage.setScene(new Scene(...))` navigation calls with `ScreenNavigator.navigateTo()`; remove the `Advanced Settings` `TitledPane` and its fields (moved to Preferences dialog in US2) — `src/main/java/com/mediascanner/ui/MainController.java`
- [X] T013 [P] Refactor `DashboardController.java`: replace all scene navigation calls with `ScreenNavigator.navigateTo()`; expose `getScanEngine()` method for `MenuBarController` to access pause/resume/stop — `src/main/java/com/mediascanner/ui/DashboardController.java`
- [X] T014 [P] Refactor `SummaryController.java`: replace all scene navigation calls with `ScreenNavigator.navigateTo()` — `src/main/java/com/mediascanner/ui/SummaryController.java`
- [X] T015 [P] Unit test `AppStateManagerTest.java`: initial state is IDLE; setState(RUNNING) updates property; `isJobActive` true for RUNNING+PAUSED, false otherwise; `hasCompletedJob` true only for COMPLETED; setState from background thread via Platform.runLater does not throw — `src/test/java/com/mediascanner/engine/AppStateManagerTest.java`
- [X] T016 [P] Unit test `ScreenNavigatorTest.java`: navigateTo(CONFIGURATION) sets correct FXML; navigateTo(DASHBOARD) sets correct FXML; repeated calls to same screen do not crash — `src/test/java/com/mediascanner/ui/ScreenNavigatorTest.java`

**Checkpoint**: `mvn test -Dtest="AppStateManagerTest,ScreenNavigatorTest"` passes. App launches with visible menu bar. All menu items present. Keyboard shortcuts registered (no action yet for most).

---

## Phase 3: User Story 1 — File Menu (Priority: P1) 🎯 MVP

**Goal**: File menu fully operational — New Scan resets and navigates, Open Job State loads a checkpoint, Quit guards against active scans.

**Independent Test**: Launch app → File menu visible with all items → New Scan resets form → Open Job State loads a valid checkpoint file → Quit with active scan shows confirmation dialog → Quit without active scan exits immediately.

### Implementation for User Story 1

- [X] T017 [US1] Implement `onNewScan()` handler in `MenuBarController`: call `ScreenNavigator.navigateTo(CONFIGURATION)`; broadcast reset event or call `MainController.resetForm()` via a `Runnable` callback — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T018 [P] [US1] Implement `onOpenJobState()` handler in `MenuBarController`: open `FileChooser` filtered to `*.json`; call `JobStateExporter.importFrom(path)`; if valid, call `MainController.loadCheckpoint(state)`; if invalid, show error `Alert` — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T019 [P] [US1] Implement `onQuit()` handler in `MenuBarController`: check `AppStateManager.isJobActive()`; if true show confirmation `Alert` ("A scan is running. Stop and quit?") with Stop & Quit / Cancel; if confirmed call `ScanEngine.stop()` then `Platform.exit()`; if not active call `Platform.exit()` directly — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T020 [P] [US1] Register `Stage.setOnCloseRequest()` in `MediaScannerApp` to call the same quit guard logic (⌘Q / Alt+F4 / window X button all trigger same confirmation) — `src/main/java/com/mediascanner/app/MediaScannerApp.java`
- [X] T021 [P] [US1] Unit test `MenuBarControllerTest.java` US1 cases: New Scan navigates to CONFIGURATION; Open Job State with valid JSON populates source/target; Open Job State with invalid JSON shows error; Quit with IDLE state exits; Quit with RUNNING state shows confirmation — `src/test/java/com/mediascanner/ui/MenuBarControllerTest.java`

**Checkpoint**: US1 independent test passes. File menu fully functional.

---

## Phase 4: User Story 2 — Preferences Dialog (Priority: P1)

**Goal**: Preferences modal dialog opens from Edit menu (or macOS app menu), displays all settings grouped by section, applies on OK, discards on Cancel, and persists through restart.

**Independent Test**: Open Edit → Preferences → dialog opens → all 4 sections visible with correct current values → change thread count → OK → start new scan → thread count change reflected → relaunch app → changed value still present → open Preferences → Cancel → value unchanged.

### Implementation for User Story 2

- [X] T022 [US2] Create `preferences.fxml`: `TabPane` with 4 tabs (Performance, Validation, Organisation, Ignore Patterns) per `contracts/menu-structure.md` Preferences Dialog Sections; each tab contains the appropriate fields with labels — `src/main/resources/fxml/preferences.fxml`
- [X] T023 [US2] Create `PreferencesController.java`: on `initialize()` reads all values from `AppConfig` into field controls; `onOk()` validates all fields (thread count ≥ 1; KB thresholds ≥ 1), writes to `AppConfig.save()`, closes stage; `onCancel()` closes stage without writing; invalid fields shown with `-fx-border-color: red` — `src/main/java/com/mediascanner/ui/PreferencesController.java`
- [X] T024 [P] [US2] Implement `onPreferences()` handler in `MenuBarController`: load `preferences.fxml` as `Stage` with `Modality.APPLICATION_MODAL`, `initOwner(primaryStage)`, show and wait — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T025 [P] [US2] On macOS, wire the auto-generated application menu's Preferences item to the same `onPreferences()` handler (JavaFX exposes this via `Desktop.getDesktop().setPreferencesHandler()` on Java 9+ / macOS) — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T026 [P] [US2] Unit test `PreferencesControllerTest.java`: dialog pre-populates from AppConfig; OK with valid values calls AppConfig.save(); OK with invalid thread count (0, negative, non-integer) does not close; Cancel does not call AppConfig.save() — `src/test/java/com/mediascanner/ui/PreferencesControllerTest.java`

**Checkpoint**: US2 independent test passes. Preferences dialog fully functional. Settings persist across restart.

---

## Phase 5: User Story 3 — Job Menu Scan Control (Priority: P1)

**Goal**: Job menu provides full scan control (Start, Pause, Resume, Stop, Export) with state-accurate enable/disable and identical behaviour to the dashboard buttons.

**Independent Test**: No scan running → Start enabled, Pause/Resume/Stop greyed → Start scan via Job menu → Pause via Job menu (scan pauses ≤ 3 s) → Resume via Job menu → Stop via Job menu → back to IDLE. Export Job State available throughout.

### Implementation for User Story 3

- [X] T027 [US3] Implement `onStartScan()` handler in `MenuBarController`: navigate to CONFIGURATION if not already there; call `MainController.startScan()` programmatically (or navigate to Configuration and enable Start) — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T028 [P] [US3] Implement `onPause()` handler in `MenuBarController`: call `scanEngineRef.pause()`; disable `miPause`, enable `miResume` (state binding handles this automatically via `AppStateManager`) — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T029 [P] [US3] Implement `onResume()` handler in `MenuBarController`: call `scanEngineRef.resume()` — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T030 [P] [US3] Implement `onStop()` handler in `MenuBarController`: call `scanEngineRef.stop()` — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T031 [P] [US3] Implement `onExportJobState()` handler in `MenuBarController`: open `FileChooser` Save dialog; serialize current `CheckpointState` via `JobStateExporter.export()`; show success status — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T032 [P] [US3] Add `setScanEngine(ScanEngine engine)` method to `MenuBarController`; call it from `DashboardController.init()` when a scan starts so `MenuBarController` always holds the current engine reference — `src/main/java/com/mediascanner/ui/MenuBarController.java`, `src/main/java/com/mediascanner/ui/DashboardController.java`
- [X] T033 [P] [US3] Unit test `MenuBarControllerTest.java` US3 cases: Job menu items enabled/disabled correctly per AppState transitions; Pause calls engine.pause(); Resume calls engine.resume(); Stop calls engine.stop(); Export opens file chooser — `src/test/java/com/mediascanner/ui/MenuBarControllerTest.java`

**Checkpoint**: US3 independent test passes. Job menu scan control fully functional with correct state management.

---

## Phase 6: User Story 4 — View Menu Navigation and Dark Mode (Priority: P2)

**Goal**: View menu switches between Configuration, Dashboard, and Summary screens; Dashboard/Summary grayed out correctly; Toggle Dark Mode swaps CSS and persists.

**Independent Test**: Start scan → View → Dashboard (enabled, switches screen) → View → Configuration (switches back) → stop scan → View → Summary (enabled) → View → Toggle Dark Mode (UI darkens, checkmark appears) → restart → dark mode persists.

### Implementation for User Story 4

- [X] T034 [US4] Implement `onViewConfiguration()` handler: call `ScreenNavigator.navigateTo(CONFIGURATION)` — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T035 [P] [US4] Implement `onViewDashboard()` handler: call `ScreenNavigator.navigateTo(DASHBOARD)` (only reachable when enabled per state binding) — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T036 [P] [US4] Implement `onViewSummary()` handler: call `ScreenNavigator.navigateTo(SUMMARY)` (only reachable when enabled) — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T037 [P] [US4] Implement `onToggleDarkMode()` handler: call `DarkModeManager.toggle()`; bind `miDarkMode.selectedProperty()` to `AppConfig.isDarkMode()` so checkmark reflects persisted state on startup — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T038 [P] [US4] Complete `mediascanner-dark.css`: dark background (`#1e1e1e`), light text (`#f0f0f0`), dark panel borders, dark button styles — ensure all existing style classes are covered — `src/main/resources/css/mediascanner-dark.css`
- [X] T039 [P] [US4] Unit test `DarkModeManagerTest.java`: toggle() swaps stylesheet; toggle() twice returns to original; apply(true) sets dark CSS; apply(false) sets light CSS; preference persisted to AppConfig — `src/test/java/com/mediascanner/ui/DarkModeManagerTest.java`

**Checkpoint**: US4 independent test passes. Screen navigation works from any screen. Dark mode toggles and persists.

---

## Phase 7: User Story 5 — Tools Menu Utilities (Priority: P2)

**Goal**: Tools menu provides one-click access to View Failure Report, Open Log File, and Clear Hash Cache (with confirmation).

**Independent Test**: Run scan with bad files → Tools → View Failure Report → file opens in system viewer (or info dialog if none) → Tools → Open Log File → log opens → Tools → Clear Hash Cache → confirmation → confirm → FILE_HASH_INDEX cleared → rerun scan → files re-hashed.

### Implementation for User Story 5

- [X] T040 [US5] Implement `onViewFailureReport()` handler: read `AppStateManager.lastJobTargetPath`; resolve `_failures/failure-report.json`; if exists call `Desktop.getDesktop().open(file)`; else show `Alert` "No failure report found for the last job." — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T041 [P] [US5] Implement `onOpenLogFile()` handler: resolve `~/.mediascanner/logs/mediascanner.log`; if exists call `Desktop.getDesktop().open(file)`; else show `Alert` "No log file found. Run a scan first." — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T042 [P] [US5] Implement `onClearHashCache()` handler: show confirmation `Alert` ("Clear the hash cache? All files will be re-hashed on the next scan."); on confirm call `hashIndexDao.clearAll()` (new method); show success `Alert` "Hash cache cleared." — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T043 [P] [US5] Add `clearAll()` method to `HashIndexDao.java`: execute `DELETE FROM FILE_HASH_INDEX` — `src/main/java/com/mediascanner/db/HashIndexDao.java`
- [X] T044 [P] [US5] `Clear Hash Cache` menu item: bound to disabled when `AppStateManager.isJobActive()` is true (never during active scan) per `contracts/menu-structure.md` — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T045 [P] [US5] Unit test `HashIndexDaoIT.java` addition: `clearAll()` deletes all rows; subsequent `findBySha256()` returns null — `src/test/java/com/mediascanner/db/HashIndexDaoIT.java`

**Checkpoint**: US5 independent test passes. All three utility actions work correctly including the confirmation guard on Clear Hash Cache.

---

## Phase 8: User Story 6 — Help Menu (Priority: P3)

**Goal**: Help menu provides About dialog (version + copyright), User Guide (opens bundled HTML or browser), and Keyboard Shortcuts reference dialog.

**Independent Test**: Help → About → dialog shows app name, version, copyright → Close → Help → Keyboard Shortcuts → all shortcuts listed → F1 → user guide opens.

### Implementation for User Story 6

- [X] T046 [US6] Create `about.fxml`: centered layout with app icon placeholder (64×64), app name label, version label (bound at runtime), description, copyright, Close button — size 360×280, not resizable — `src/main/resources/fxml/about.fxml`
- [X] T047 [P] [US6] Create `AboutController.java`: on `initialize()` sets version label to `AppConfig.getAppVersion()`; `onClose()` closes the stage — `src/main/java/com/mediascanner/ui/AboutController.java`
- [X] T048 [P] [US6] Implement `onAbout()` handler in `MenuBarController`: load `about.fxml` as `Stage` with `Modality.APPLICATION_MODAL`, `initOwner(primaryStage)`, not resizable, show and wait — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T049 [P] [US6] Create `shortcuts.fxml`: `TableView` or `GridPane` listing all shortcuts in two columns (Action, Shortcut) per `contracts/menu-structure.md` Keyboard Shortcuts Reference Dialog table — `src/main/resources/fxml/shortcuts.fxml`
- [X] T050 [P] [US6] Implement `onKeyboardShortcuts()` handler in `MenuBarController`: load `shortcuts.fxml` as non-modal `Stage`; show (not show-and-wait, allows user to keep it open while using shortcuts) — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T051 [P] [US6] Implement `onOpenUserGuide()` handler: resolve bundled `user-guide.html` from classpath resources; if found call `Desktop.getDesktop().browse(URI)`; else open a fallback info dialog — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T052 [P] [US6] Create bundled user guide placeholder: `src/main/resources/docs/user-guide.html` with basic HTML page listing features — `src/main/resources/docs/user-guide.html`
- [X] T053 [P] [US6] On macOS, register `Desktop.getDesktop().setAboutHandler()` to call the same `onAbout()` so the standard macOS "About MediaScanner" in the app menu works — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T054 [P] [US6] Unit test `AboutControllerTest.java`: version label populated from AppConfig; controller loads without exception — `src/test/java/com/mediascanner/ui/AboutControllerTest.java`

**Checkpoint**: US6 independent test passes. Help menu fully functional. All dialogs open and close correctly.

---

## Phase 9: Polish and Cross-Cutting Concerns

**Purpose**: Integration validation, code review for state correctness, macOS HIG compliance check, and acceptance test run.

- [X] T055 [P] Integration test `MenuBarIntegrationTest.java`: AppStateManager transitions from IDLE → RUNNING → PAUSED → RUNNING → COMPLETED; verify all six menu binding states correct at each transition; verify no NullPointerException on rapid state changes — `src/test/java/com/mediascanner/ui/MenuBarIntegrationTest.java`
- [X] T056 [P] Verify macOS compliance: `setUseSystemMenuBar(true)` is called; Preferences item is in app menu (not duplicated in Edit); About item is in app menu; Quit item reads "Quit MediaScanner" on macOS — `src/main/java/com/mediascanner/ui/MenuBarController.java`
- [X] T057 [P] Verify keyboard shortcuts smoke test per `quickstart.md` Keyboard Shortcut Smoke Test table — validate all 14 shortcuts trigger correct actions
- [X] T058 [P] Security review: confirm no source or target paths are logged at DEBUG level in any new menu handler; confirm `Clear Hash Cache` cannot be triggered during active scan (disabled state + no programmatic path)
- [X] T059 [P] Update `quickstart.md` with any build or run commands that changed during implementation — `specs/002-menu-bar/quickstart.md`
- [X] T060 [P] Validate all US1–US6 acceptance scenarios from `quickstart.md` Manual Acceptance Checklist pass

---

## Dependencies and Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — start immediately
- **Phase 2 (Foundational)**: Depends on Phase 1 — **BLOCKS all user stories**
- **Phase 3–8 (User Stories)**: All depend on Phase 2 completion
  - US1, US2, US3 (P1) can run in parallel after Phase 2
  - US4, US5 (P2) can run in parallel after Phase 2
  - US6 (P3) can run after Phase 2
- **Phase 9 (Polish)**: Depends on all desired user stories complete

### User Story Dependencies

- **US1 (P1)**: Can start after Phase 2 — no story dependencies
- **US2 (P1)**: Can start after Phase 2 — no story dependencies; parallelizable with US1
- **US3 (P1)**: Can start after Phase 2 — needs `setScanEngine()` from US3 T032; parallelizable with US1+US2
- **US4 (P2)**: Can start after Phase 2 — independent; parallelizable with US1–US3
- **US5 (P2)**: Can start after Phase 2 — needs `HashIndexDao.clearAll()` (T043); parallelizable with US4
- **US6 (P3)**: Can start after Phase 2 — fully independent

### Within Each User Story

- Implementation tasks before wiring handlers
- State bindings (T010) must be complete before any handler can correctly reflect state
- `MenuBarController` skeleton (T009) is the shared file — US-specific handlers are additive, not conflicting

### Parallel Opportunities

```
Phase 1 parallel group: T002, T003, T004, T006, T007 (all different files)
Phase 2 parallel group: T010, T011, T012, T013, T014, T015, T016

After Phase 2:
  US1 (T017–T021)    ─┐
  US2 (T022–T026)    ─┤ all parallelizable
  US3 (T027–T033)    ─┤
  US4 (T034–T039)    ─┤
  US5 (T040–T045)    ─┤
  US6 (T046–T054)    ─┘
```

---

## Implementation Strategy

### MVP (US1 + US2 + US3 — File, Preferences, Job Control)

1. Complete Phase 1 (Setup)
2. Complete Phase 2 (Foundational — CRITICAL)
3. Complete Phase 3 (US1 — File menu)
4. Complete Phase 4 (US2 — Preferences dialog)
5. Complete Phase 5 (US3 — Job menu scan control)
6. **STOP and VALIDATE**: App has a functional menu bar covering all primary actions
7. Demonstrate: launch → File menu works → Preferences open/save → start scan → pause via menu → resume → stop

### Incremental Delivery

1. Setup + Foundational → menu bar skeleton visible with shortcuts
2. US1 + US2 + US3 (P1) → all critical actions accessible — **MVP demo point**
3. US4 (P2) → screen navigation + dark mode
4. US5 (P2) → utility tools
5. US6 (P3) → Help menu + About + Shortcuts
6. Phase 9 → Polish, macOS HIG validation, acceptance tests

### Parallel Team Strategy (if staffed)

After Phase 2:
- **Developer A**: US1 + US2 (File menu + Preferences)
- **Developer B**: US3 (Job menu control)
- **Developer C**: US4 (View menu + Dark Mode)
- After above: **A** → US5; **B** → US6; **C** → Phase 9

---

## Notes

- `[P]` tasks target different files — safe to run concurrently
- `MenuBarController.java` is the single shared file across all US phases; handlers are additive (each story adds new methods, never modifies existing ones) — merge conflicts are minimal
- `AppStateManager.setState()` MUST always be wrapped in `Platform.runLater()` when called from non-JavaFX threads (ScanEngine worker threads)
- On macOS: `setUseSystemMenuBar(true)` + `Desktop.getDesktop().setAboutHandler()` + `.setPreferencesHandler()` ensure native app menu integration
- Dark mode CSS swap is instantaneous — no need for animation or delay
- The `ScreenNavigator` center-pane approach avoids creating a new `Scene` on each navigation — this is important because `MenuBar` and keyboard shortcuts are attached to the `Scene`, not the `Stage`
