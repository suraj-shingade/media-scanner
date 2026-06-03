# Research: Application Menu Bar

**Branch**: `002-menu-bar` | **Date**: 2026-06-03

---

## 1. JavaFX MenuBar and Platform Integration

**Decision**: Use JavaFX `MenuBar` with `setUseSystemMenuBar(true)` on macOS.

**Rationale**: JavaFX 21's built-in `MenuBar` is the standard approach for JavaFX desktop applications. Setting `useSystemMenuBar=true` on macOS automatically moves the bar to the screen top and handles the macOS "Application" menu conventions (Quit, About, Preferences appear in the app-named menu). On Windows/Linux, `useSystemMenuBar` has no effect and the bar sits at the top of the window — the desired behaviour in both cases.

**Alternatives considered**:
- Third-party menu libraries (e.g., FXRibbon): Rejected — adds unnecessary dependency; JavaFX native is sufficient for standard menus.
- Manual OS-detection + duplicate menu construction: Rejected — JavaFX handles this transparently with `useSystemMenuBar`.

---

## 2. Application State Management for Menu Enable/Disable

**Decision**: Introduce `AppStateManager` — a singleton holding an `ObjectProperty<AppState>` where `AppState` is an enum (`IDLE`, `RUNNING`, `PAUSED`, `COMPLETED`). Menu items bind their `disableProperty()` to this observable.

**Rationale**: JavaFX property bindings ensure menu item states update on the JavaFX Application Thread without polling. A central state manager avoids scattering `menuItem.setDisable()` calls across controllers. `ScanEngine` calls `AppStateManager.setState()` at each transition; all menu items update automatically within one frame (~16 ms, well under the 500 ms SC-003 budget).

**Alternatives considered**:
- Direct `setDisable()` calls in each controller: Rejected — brittle; adding a new menu item requires editing every controller.
- Event bus approach: Rejected — over-engineering for a single shared state enum.

---

## 3. Dark Mode Implementation

**Decision**: CSS stylesheet swap at runtime. Two stylesheets: `mediascanner-light.css` (current `mediascanner.css` renamed) and `mediascanner-dark.css`. The active sheet is applied to the root `Scene`. Preference stored as `ui.dark.mode=true/false` in `~/.mediascanner/config.properties`.

**Rationale**: JavaFX has no built-in OS dark mode sync on Java 21 (that arrived in later JavaFX versions for some platforms). CSS swap is the idiomatic JavaFX approach, is instantaneous, and is reversible. The spec explicitly states CSS theming is sufficient for v1.

**Alternatives considered**:
- OS-level dark mode detection via JNA: Rejected — spec defers this to a future enhancement.
- MaterialFX built-in theming: Rejected — MaterialFX is already a dependency but its theming API requires a larger refactor; CSS swap is simpler and non-breaking.

---

## 4. Preferences Dialog Architecture

**Decision**: A dedicated `preferences.fxml` FXML file with a `PreferencesController` class, loaded as a `Stage` with `Modality.APPLICATION_MODAL`. Settings are read from `AppConfig` on open, written back to `AppConfig` only on OK.

**Rationale**: Consistent with the existing FXML-per-screen pattern (`main.fxml`, `dashboard.fxml`, `summary.fxml`). Modal stage is the correct JavaFX idiom for a Preferences dialog. Separating read/write (only commit on OK) satisfies FR-008 (Cancel discards).

**Alternatives considered**:
- Reusing the existing Advanced Settings panel in a popup: Rejected — the panel is embedded in `main.fxml`; extracting it is cleaner than duplicating it.
- `Dialog<ButtonType>` wrapper: Considered — simpler for small dialogs but less flexible for the tab layout needed for all settings groups.

---

## 5. Application Version in About Dialog

**Decision**: Read from `Implementation-Version` JAR manifest attribute, set by adding `maven-jar-plugin` `addDefaultImplementationEntries=true` to `pom.xml`. Falls back to the literal `"1.0.0"` if the manifest attribute is absent (e.g., running in-IDE without packaging).

**Rationale**: Standard Maven approach; no hard-coded version string in source. Works correctly in both packaged and development modes.

**Alternatives considered**:
- Hard-coded constant: Rejected — would drift from `pom.xml` version on each release.
- Reading `pom.xml` at runtime: Rejected — `pom.xml` is not in the classpath in production builds.

---

## 6. Keyboard Shortcut Mapping

**Decision**: Use JavaFX `KeyCombination.keyCombination("shortcut+N")` where `shortcut` maps to ⌘ on macOS and Ctrl on Windows/Linux automatically. No platform detection code needed.

**Rationale**: JavaFX's `shortcut` modifier is the idiomatic cross-platform key alias. All required shortcuts (FR-024) use standard combinations that map cleanly.

**Alternatives considered**:
- `KeyCodeCombination` with explicit `META_DOWN` / `CONTROL_DOWN` branching: Rejected — `shortcut` handles this transparently.

---

## 7. MenuController Architecture

**Decision**: A single `MenuBarController` class owns the `MenuBar` and all `MenuItem` references. It is instantiated once in `MediaScannerApp.start()` alongside the primary stage and passed a reference to `AppStateManager` and the navigation service. All menu action handlers live in `MenuBarController`.

**Rationale**: Centralising all menu logic in one controller avoids the complexity of each screen controller managing menu state. The `MenuBar` is attached to the `BorderPane` top once and persists across screen navigation (screens replace only the `center` region).

**Alternatives considered**:
- Each FXML screen owns its own `MenuBar`: Rejected — duplicates markup, breaks keyboard shortcuts during screen transitions.
- FXML-defined `MenuBar` embedded in each screen FXML: Rejected — same problem as above.
