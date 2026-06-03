# Contract: UI Screen Changes

**Feature**: Application Menu Bar | **Date**: 2026-06-03

---

## Root Layout Change

The existing per-screen `BorderPane` approach changes to a single persistent root `BorderPane`:

```
Stage (primary)
└── Scene
    └── BorderPane (root — persistent across screen navigation)
        ├── top: MenuBar (MenuBarController — never replaced)
        └── center: [current screen FXML — replaced on navigation]
```

**Before** (current): Each FXML file (`main.fxml`, `dashboard.fxml`, `summary.fxml`) is loaded as a full `Scene` replacement. The `Stage` scene is swapped.

**After** (new): `MediaScannerApp.start()` creates one `BorderPane` with the `MenuBar` in the `top` slot. Navigation replaces only `center`. The `Stage` scene is never replaced after initial load.

---

## New Files

| File | Purpose |
|------|---------|
| `src/main/resources/fxml/preferences.fxml` | Preferences modal dialog |
| `src/main/resources/fxml/about.fxml` | About dialog |
| `src/main/resources/fxml/shortcuts.fxml` | Keyboard Shortcuts reference dialog |
| `src/main/resources/css/mediascanner-light.css` | Renamed from `mediascanner.css` |
| `src/main/resources/css/mediascanner-dark.css` | New dark theme |
| `src/main/java/com/mediascanner/ui/MenuBarController.java` | Menu bar owner and action handler |
| `src/main/java/com/mediascanner/ui/PreferencesController.java` | Preferences dialog controller |
| `src/main/java/com/mediascanner/ui/AboutController.java` | About dialog controller |
| `src/main/java/com/mediascanner/ui/ScreenNavigator.java` | Screen switching service |
| `src/main/java/com/mediascanner/engine/AppStateManager.java` | Centralised AppState observable |

---

## Modified Files

| File | Change |
|------|--------|
| `src/main/java/com/mediascanner/app/MediaScannerApp.java` | Load root BorderPane; attach MenuBar; use ScreenNavigator for initial screen |
| `src/main/java/com/mediascanner/config/AppConfig.java` | Add `ui.dark.mode` field; add version helper |
| `src/main/java/com/mediascanner/engine/ScanEngine.java` | Call `AppStateManager.setState()` at each transition |
| `src/main/java/com/mediascanner/ui/MainController.java` | Remove inline navigation; use ScreenNavigator; remove Advanced Settings panel (moved to Preferences) |
| `src/main/java/com/mediascanner/ui/DashboardController.java` | Remove inline navigation; use ScreenNavigator |
| `src/main/java/com/mediascanner/ui/SummaryController.java` | Remove inline navigation; use ScreenNavigator |
| `src/main/resources/css/mediascanner.css` | Renamed to `mediascanner-light.css` |
| `pom.xml` | Add `maven-jar-plugin` `addDefaultImplementationEntries=true` for version manifest |

---

## Preferences Dialog Layout

```
┌─────────────────────── Preferences ───────────────────────────┐
│                                                                 │
│  [Performance]  [Validation]  [Organisation]  [Ignore Patterns]│
│  ─────────────────────────────────────────────────────────── │
│                                                                 │
│  Performance tab:                                               │
│  Worker threads:    [    ] (0 = CPU×2)                          │
│  High-Priority Mode: [ ] Enable                                 │
│                                                                 │
│                                          [Cancel]    [OK]       │
└─────────────────────────────────────────────────────────────────┘
```

- Window size: 500 × 400 px minimum
- Modality: APPLICATION_MODAL
- Style: same CSS as main app (respects dark mode)

---

## About Dialog Layout

```
┌──────────── About MediaScanner ─────────────┐
│                                              │
│         [MediaScanner Icon 64×64]            │
│              MediaScanner                    │
│              Version 1.0.0                   │
│                                              │
│  High-performance media file organiser       │
│  for macOS and Windows.                      │
│                                              │
│  © 2026 MediaScanner. All rights reserved.  │
│                                              │
│                    [Close]                   │
└──────────────────────────────────────────────┘
```

- Window size: 360 × 280 px
- Modality: APPLICATION_MODAL
- Not resizable
