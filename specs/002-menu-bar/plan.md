# Implementation Plan: Application Menu Bar

**Branch**: `002-menu-bar` | **Date**: 2026-06-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-menu-bar/spec.md`

---

## Summary

Add a native JavaFX `MenuBar` to the MediaScanner desktop application covering six menus (File, Edit/App, Job, View, Tools, Help) with 25 functional requirements spanning scan control, preferences management, screen navigation, dark mode, utility actions, and keyboard shortcuts. The feature is purely additive — all existing on-screen controls are retained. A new `AppStateManager` singleton provides reactive menu item state bindings. A `ScreenNavigator` service decouples screen switching from the `Stage` scene-swap approach. A new `PreferencesController` + `preferences.fxml` exposes all user settings in a proper modal dialog.

---

## Technical Context

**Language/Version**: Java 21 LTS

**Primary Dependencies** (all already in `pom.xml` — no new dependencies required):
- JavaFX 21 (`MenuBar`, `Menu`, `MenuItem`, `CheckMenuItem`, `KeyCombination`, `ObjectProperty`)
- JavaFX FXML (new `preferences.fxml`, `about.fxml`, `shortcuts.fxml`)
- ControlsFX / MaterialFX (existing; CSS dark theme extends current approach)
- Jackson (no change — serialisation already in use)
- SLF4J/Logback (no change)

**Storage**: No schema changes. One new `AppConfig` key: `ui.dark.mode` (boolean, default `false`).

**Testing**: JUnit 5 + AssertJ (existing). No Mockito (removed in feature 001 due to Java 26 incompatibility). Tests use real `AppConfig` with temp directories.

**Target Platform**: macOS (menu bar at screen top via `setUseSystemMenuBar(true)`) + Windows 10+ (menu bar at window top). JavaFX `shortcut` key modifier handles ⌘ vs Ctrl automatically.

**Project Type**: Desktop application (JavaFX, single-user, fully offline). This feature is UI-only — no engine changes except `AppStateManager.setState()` calls in `ScanEngine`.

**Performance Goals**:
- Menu bar render: visible within 1 s of launch (SC-001)
- Keyboard shortcut response: ≤ 200 ms (SC-002; JavaFX event dispatch is synchronous — easily met)
- Menu state update after job transition: ≤ 500 ms (SC-003; property binding is immediate on JavaFX thread)
- Preferences dialog open: ≤ 300 ms (SC-004; FXML load is ≈ 50 ms for a small dialog)

**Constraints**:
- No new Maven dependencies — must compile with existing `pom.xml` (plus one `maven-jar-plugin` config change for version manifest)
- Existing in-window controls (Pause/Resume/Stop buttons on dashboard) MUST remain functional
- `AppStateManager.setState()` MUST be called via `Platform.runLater()` if invoked from worker threads (ScanEngine runs on a non-JavaFX thread)
- All keyboard shortcuts use `KeyCombination.keyCombination("shortcut+X")` — never hard-coded `Ctrl` or `Meta`

**Scale/Scope**: One primary stage, 6 menus, ~25 menu items, 3 new dialogs (Preferences, About, Shortcuts), 1 new service (ScreenNavigator), 1 new singleton (AppStateManager), 2 CSS files (light + dark).

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Requirement | Status |
|-----------|------------|--------|
| **I. Performance-First** | Menu state bindings are O(1) property changes on JavaFX thread — no polling, no I/O | ✅ PASS |
| **II. Context Preservation** | No changes to checkpoint logic; `AppStateManager` reflects job state for menu enable/disable | ✅ PASS |
| **III. SQLite as SSOT** | No schema changes. `Clear Hash Cache` deletes `FILE_HASH_INDEX` rows via existing `HashIndexDao` | ✅ PASS |
| **IV. Observability** | View menu enables direct navigation to Dashboard from any screen — improves observability access | ✅ PASS |
| **V. Duplicate Handling** | No changes to duplicate logic | ✅ PASS |
| **VI. Media Validation** | No changes to validation logic | ✅ PASS |
| **VII. Folder Organisation** | No changes to folder organisation logic | ✅ PASS |
| **VIII. Development Discipline** | TDD: unit tests written before controllers; each US has an independent test | ✅ PASS |

**Result**: ALL GATES PASS — no constitution violations.

---

## Project Structure

### Documentation (this feature)

```text
specs/002-menu-bar/
├── plan.md              # This file
├── research.md          # Phase 0 output ✅
├── data-model.md        # Phase 1 output ✅
├── quickstart.md        # Phase 1 output ✅
├── contracts/
│   ├── menu-structure.md    # Authoritative menu/shortcut/state contract ✅
│   └── ui-screens.md        # Root layout change + new/modified file list ✅
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
src/
└── main/
    ├── java/
    │   └── com/mediascanner/
    │       ├── app/
    │       │   └── MediaScannerApp.java              # Modified: root BorderPane, ScreenNavigator init
    │       ├── engine/
    │       │   ├── AppStateManager.java              # NEW: AppState enum + ObjectProperty singleton
    │       │   └── ScanEngine.java                   # Modified: setState() calls at transitions
    │       ├── ui/
    │       │   ├── MenuBarController.java            # NEW: MenuBar owner, all menu actions + bindings
    │       │   ├── PreferencesController.java        # NEW: Preferences modal dialog controller
    │       │   ├── AboutController.java              # NEW: About dialog controller
    │       │   ├── ScreenNavigator.java              # NEW: center-pane screen switching service
    │       │   ├── DarkModeManager.java              # NEW: CSS swap + preference persist
    │       │   ├── MainController.java               # Modified: use ScreenNavigator; remove Advanced Settings
    │       │   ├── DashboardController.java          # Modified: use ScreenNavigator
    │       │   └── SummaryController.java            # Modified: use ScreenNavigator
    │       └── config/
    │           └── AppConfig.java                    # Modified: add ui.dark.mode field
    └── resources/
        ├── fxml/
        │   ├── preferences.fxml                      # NEW
        │   ├── about.fxml                            # NEW
        │   └── shortcuts.fxml                        # NEW
        └── css/
            ├── mediascanner-light.css                # Renamed from mediascanner.css
            └── mediascanner-dark.css                 # NEW

src/
└── test/
    └── java/
        └── com/mediascanner/
            ├── engine/
            │   └── AppStateManagerTest.java          # NEW
            └── ui/
                ├── MenuBarControllerTest.java        # NEW
                ├── PreferencesControllerTest.java    # NEW
                ├── ScreenNavigatorTest.java          # NEW
                └── DarkModeManagerTest.java          # NEW
```

**Structure Decision**: Extends the existing single Maven project. New classes follow the established package conventions (`engine/` for state management, `ui/` for controllers). No new packages needed.

---

## Complexity Tracking

> No constitution violations. No complexity justifications required.
