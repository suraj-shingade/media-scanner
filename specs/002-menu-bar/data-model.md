# Data Model: Application Menu Bar

**Branch**: `002-menu-bar` | **Date**: 2026-06-03

---

## Entity Overview

```
AppStateManager ─────────────────────────────────────────────────┐
│ Singleton — one per JVM instance                                 │
│ holds → ObjectProperty<AppState>                                 │
│ read by → MenuBarController (bindings)                           │
│ written by → ScanEngine (on state transitions)                   │
└──────────────────────────────────────────────────────────────────┘

MenuBarController ────────────────────────────────────────────────┐
│ owns → MenuBar (one per primary Stage)                           │
│ observes → AppStateManager                                       │
│ delegates navigation → ScreenNavigator                           │
│ delegates scan control → ScanEngine                              │
└──────────────────────────────────────────────────────────────────┘

PreferencesController ────────────────────────────────────────────┐
│ reads/writes → AppConfig                                         │
│ opened by → MenuBarController                                    │
└──────────────────────────────────────────────────────────────────┘
```

---

## Entities

### AppState (enum)

Represents the current lifecycle state of the application with respect to job execution. All menu item enable/disable rules derive from this value.

| Value | Description | Enabled menus |
|-------|-------------|---------------|
| `IDLE` | No job running or completed in this session | File, Edit, Job(Start only), View(Config only), Tools, Help |
| `RUNNING` | A job is actively processing files | All; Job: Pause+Stop+Export enabled; Start+Resume disabled |
| `PAUSED` | A job is paused mid-scan | All; Job: Resume+Stop+Export enabled; Start+Pause disabled |
| `COMPLETED` | The last job finished (success or stop) | All; Job: Start+Export enabled; Pause+Resume+Stop disabled |

**State transitions** (driven by `ScanEngine`):
```
IDLE → RUNNING → PAUSED ↔ RUNNING → COMPLETED
                        → COMPLETED (stop)
```

---

### AppStateManager

Singleton observable that broadcasts `AppState` changes to all interested components via JavaFX property binding.

| Field | Type | Description |
|-------|------|-------------|
| `instance` | `AppStateManager` | Singleton reference (static) |
| `appState` | `ObjectProperty<AppState>` | Current state; thread-safe via `Platform.runLater` |
| `lastJobTargetPath` | `StringProperty` | Target path of last job; used by Tools → View Failure Report |
| `hasCompletedJob` | `BooleanBinding` | True when state is COMPLETED; drives View → Summary enabled state |
| `isJobActive` | `BooleanBinding` | True when RUNNING or PAUSED; drives quit confirmation guard |

**Invariants**:
- `setState()` MUST be called on the JavaFX Application Thread (wrap with `Platform.runLater` if called from worker threads).
- `instance` is initialised once in `MediaScannerApp.start()` before any controller is created.

---

### MenuBarController

Owns the application `MenuBar` and wires all menu item actions and bindings.

| Field | Type | Description |
|-------|------|-------------|
| `menuBar` | `MenuBar` | The JavaFX MenuBar attached to the primary stage's BorderPane |
| `stateManager` | `AppStateManager` | Reference injected at construction |
| `navigator` | `ScreenNavigator` | Navigation service injected at construction |
| `scanEngine` | `ScanEngine` | Reference to the active scan engine for Job menu actions |
| `miStartScan` | `MenuItem` | File → New Scan / Job → Start Scan |
| `miOpenJobState` | `MenuItem` | File → Open Job State… |
| `miPause` | `MenuItem` | Job → Pause |
| `miResume` | `MenuItem` | Job → Resume |
| `miStop` | `MenuItem` | Job → Stop |
| `miExportState` | `MenuItem` | Job → Export Job State… |
| `miDashboard` | `MenuItem` | View → Dashboard |
| `miSummary` | `MenuItem` | View → Summary |
| `miDarkMode` | `CheckMenuItem` | View → Toggle Dark Mode |
| `darkModeEnabled` | `BooleanProperty` | Bound to `miDarkMode.selectedProperty()`; drives CSS swap |

**Binding rules** (derived from `AppState`):

| MenuItem | Enabled when AppState is… |
|----------|--------------------------|
| Start Scan | IDLE, COMPLETED |
| Pause | RUNNING |
| Resume | PAUSED |
| Stop | RUNNING, PAUSED |
| Export Job State | RUNNING, PAUSED, COMPLETED |
| View → Dashboard | RUNNING, PAUSED, COMPLETED |
| View → Summary | COMPLETED |

---

### ScreenNavigator

Lightweight service that replaces the `center` region of the root `BorderPane` while keeping the `MenuBar` (in the `top` region) stable.

| Field | Type | Description |
|-------|------|-------------|
| `rootPane` | `BorderPane` | Reference to the application root layout |
| `currentScreen` | `ScreenType` | Enum: CONFIGURATION, DASHBOARD, SUMMARY |

| Method | Description |
|--------|-------------|
| `navigateTo(ScreenType)` | Loads the appropriate FXML and sets it as `rootPane.getCenter()` |
| `getCurrentScreen()` | Returns the active screen type |

**Screen → FXML mapping**:

| ScreenType | FXML | Controller |
|------------|------|------------|
| CONFIGURATION | `/fxml/main.fxml` | `MainController` |
| DASHBOARD | `/fxml/dashboard.fxml` | `DashboardController` |
| SUMMARY | `/fxml/summary.fxml` | `SummaryController` |

---

### PreferencesController

Controller for the Preferences modal dialog (`preferences.fxml`).

| Field | Type | Description |
|-------|------|-------------|
| `config` | `AppConfig` | Reference to the singleton config; read on open, written on OK |
| `workingCopy` | In-memory field set | Local copies of all settings; only committed to `config` on OK |
| `threadCountField` | `TextField` | Worker thread count (min 1, max 512) |
| `imageSizeField` | `TextField` | Image min size in KB |
| `videoSizeField` | `TextField` | Video min size in KB |
| `highPriorityCheck` | `CheckBox` | High-Priority Mode |
| `folderPatternCombo` | `ComboBox` | Default folder pattern |
| `duplicatePolicyCombo` | `ComboBox` | Default duplicate policy |
| `ignorePatternsListView` | `ListView` | Editable ignore pattern list |

**Invariants**:
- Dialog is `Modality.APPLICATION_MODAL` — user cannot interact with any other window while open.
- On Cancel or close via X: no writes to `AppConfig`.
- On OK: all fields validated (thread count is a positive integer; KB thresholds are positive integers); invalid fields shown with red border; dialog does not close until valid or user cancels.

---

### DarkModeManager

Handles CSS stylesheet swap and persists the preference.

| Field | Type | Description |
|-------|------|-------------|
| `scene` | `Scene` | Reference to the primary scene |
| `darkEnabled` | `boolean` | Current state |

| Method | Description |
|--------|-------------|
| `toggle()` | Swaps stylesheets and saves preference to `AppConfig` |
| `apply(boolean dark)` | Clears scene stylesheets and adds the appropriate CSS |

**CSS files**:
- `src/main/resources/css/mediascanner-light.css` — current `mediascanner.css` renamed
- `src/main/resources/css/mediascanner-dark.css` — new dark theme

**Config key**: `ui.dark.mode` in `~/.mediascanner/config.properties` (default: `false`)

---

## AppConfig Changes

Two new fields added to `AppConfig`:

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `ui.dark.mode` | `boolean` | `false` | Dark mode preference |
| `app.version` | `String` | read from JAR manifest | Cached version string for About dialog |
