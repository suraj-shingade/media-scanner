# Feature Specification: Application Menu Bar

**Feature Branch**: `002-menu-bar`

**Created**: 2026-06-03

**Status**: Draft

**Input**: User description: "Design new feature where we will have menu option added with all the desktop software needed menu options"

---

## Summary

MediaScanner currently has no native application menu bar. This feature adds a standard macOS/Windows menu bar to the JavaFX desktop application with all menu items expected in professional desktop software: File, Edit, View, Job, Tools, Window, and Help menus. The menu bar replaces or supplements the existing in-window controls, making the app feel native and giving keyboard-savvy users access to every function via standard shortcuts.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — File Menu: New Scan, Open, and Exit (Priority: P1)

A user launches MediaScanner and uses the File menu to start a new scan, open a previously saved job state, or quit the application cleanly — exactly as they would in any other desktop tool they use.

**Why this priority**: File menu is the first menu every desktop user looks for. It covers the primary entry points (new scan, open job) and the essential exit path. Without it the app feels unfinished.

**Independent Test**: Launch the app. Open File menu → verify items are present and labeled. Click "New Scan" → main config screen appears. Click "Open Job State…" → file picker opens. Click "Quit" → app exits cleanly.

**Acceptance Scenarios**:

1. **Given** the app is running, **When** the user opens the File menu, **Then** they see: New Scan, Open Job State…, a separator, and Quit (macOS: Quit MediaScanner).
2. **Given** a scan is in progress, **When** the user clicks Quit, **Then** the app prompts "A scan is running. Stop and quit?" with Stop & Quit and Cancel options.
3. **Given** the user selects "Open Job State…", **When** they pick a valid `.json` checkpoint file, **Then** the main screen populates with the job's source/target paths.
4. **Given** the user selects "Open Job State…", **When** they pick an invalid or corrupt file, **Then** a clear error message is shown and the app stays on the current screen.

---

### User Story 2 — Edit Menu: Preferences (Priority: P1)

A user opens Edit → Preferences (macOS: MediaScanner → Settings) to access all application settings — thread count, size thresholds, ignore patterns, default folder pattern, and duplicate policy — in a dedicated preferences dialog rather than buried in the main screen's collapsible panel.

**Why this priority**: Preferences are a macOS/Windows UX standard. Moving settings out of the inline panel and into a proper Preferences dialog is a usability upgrade that all desktop users expect.

**Independent Test**: Open Edit menu → click Preferences → Preferences dialog appears with all settings groups. Change a setting → click OK → setting persists across app restart.

**Acceptance Scenarios**:

1. **Given** the app is running, **When** the user opens Edit (or the app menu on macOS) and clicks Preferences/Settings, **Then** a modal dialog opens showing all configurable settings organised into tabs or sections.
2. **Given** the Preferences dialog is open, **When** the user changes a value and clicks OK, **Then** the change takes effect immediately for the next scan.
3. **Given** the Preferences dialog is open, **When** the user clicks Cancel, **Then** no changes are applied.
4. **Given** the user changes the default Folder Pattern in Preferences, **When** they start a new scan, **Then** the main screen Folder Pattern dropdown defaults to the new preference.

---

### User Story 3 — Job Menu: Scan Control Actions (Priority: P1)

A user accesses all scan control actions — Start, Pause, Resume, Stop, Export Job State — from the Job menu, including keyboard shortcuts, without having to find the right button in the UI.

**Why this priority**: Scan control is the app's core function. Keyboard users and power users expect menu access to every action. It also allows pause/resume from any screen without hunting for the dashboard buttons.

**Independent Test**: Start a scan → Job menu shows Pause enabled, Start greyed. Click Job → Pause → scan pauses within 3 s. Job menu now shows Resume enabled. Click Job → Resume → scan continues.

**Acceptance Scenarios**:

1. **Given** no scan is running, **When** the user opens the Job menu, **Then** Start Scan is enabled; Pause, Resume, Stop are greyed out.
2. **Given** a scan is running, **When** the user opens the Job menu, **Then** Pause is enabled; Start and Resume are greyed out.
3. **Given** a scan is paused, **When** the user selects Job → Resume, **Then** the scan resumes and the dashboard reflects the running state.
4. **Given** a scan is running or paused, **When** the user selects Job → Export Job State…, **Then** a Save dialog appears and the checkpoint file is written to the chosen location.

---

### User Story 4 — View Menu: Screen Navigation and Theme (Priority: P2)

A user switches between the main configuration screen, the live dashboard, and the last job summary using View menu items, and optionally toggles between light and dark appearance.

**Why this priority**: Screen navigation from the menu bar removes the dependency on scan completion to see the dashboard or summary, and is a standard pattern for multi-screen desktop apps.

**Independent Test**: With a scan running, open View → Dashboard → dashboard screen appears. View → Configuration → main screen appears.

**Acceptance Scenarios**:

1. **Given** any screen is active, **When** the user selects View → Configuration, **Then** the main configuration screen is shown.
2. **Given** a scan is running or paused, **When** the user selects View → Dashboard, **Then** the live dashboard screen is shown.
3. **Given** a completed job exists in the current session, **When** the user selects View → Summary, **Then** the end-of-job summary screen is shown.
4. **Given** the app is running, **When** the user selects View → Toggle Dark Mode, **Then** the UI switches between light and dark appearance and the preference is remembered.

---

### User Story 5 — Tools Menu: Utility Actions (Priority: P2)

A user accesses utility actions — View Failure Report, Clear Hash Cache, Open Log File — from the Tools menu without having to navigate the filesystem manually.

**Why this priority**: Power users need these utilities regularly during troubleshooting. A menu entry is faster than hunting for the files on disk.

**Independent Test**: After a scan, open Tools → View Failure Report → the failure JSON opens in the system default viewer. Open Tools → Open Log File → the log file opens.

**Acceptance Scenarios**:

1. **Given** a failure report exists, **When** the user selects Tools → View Failure Report, **Then** the `_failures/failure-report.json` file opens in the system default application.
2. **Given** no failure report exists, **When** the user selects Tools → View Failure Report, **Then** an information dialog states "No failure report found for the last job."
3. **Given** the user selects Tools → Open Log File, **Then** the current log file (`~/.mediascanner/logs/mediascanner.log`) opens in the system default text viewer.
4. **Given** the user selects Tools → Clear Hash Cache, **Then** a confirmation dialog appears; on confirm, the `FILE_HASH_INDEX` table is cleared and a success message is shown.

---

### User Story 6 — Help Menu: About, Documentation, and Updates (Priority: P3)

A user opens the Help menu to find the app version, open the user guide, or check for updates.

**Why this priority**: Help menu is a standard desktop convention. About dialog and version info are required for any shippable desktop app. Lower priority than core scan operations.

**Independent Test**: Open Help → About MediaScanner → dialog shows app name, version number, and copyright. Dismiss dialog → returns to previous screen.

**Acceptance Scenarios**:

1. **Given** the app is running, **When** the user selects Help → About MediaScanner, **Then** a modal dialog shows the app name, version (e.g., "1.0.0"), copyright notice, and a Close button.
2. **Given** the user selects Help → Open User Guide, **Then** the system default browser opens to the documentation URL (or a bundled HTML file if no internet connection is assumed).
3. **Given** the user selects Help → Keyboard Shortcuts, **Then** a reference dialog lists all menu keyboard shortcuts.

---

### Edge Cases

- What happens if the user selects Job → Pause when the scan is already finishing its last file? The pause completes normally; if the job finishes before pause takes effect, the menu reflects COMPLETED state.
- What happens if Preferences are changed while a scan is running? Settings changes apply to the next scan; they do not interrupt the active job.
- What happens on macOS where the app menu ("MediaScanner") holds Preferences and Quit? The macOS-standard placement is used; Edit menu still exists but does not duplicate those items.
- What happens if the user presses a keyboard shortcut (⌘Q / Alt+F4) while a scan is running? The same "scan running" confirmation dialog is triggered as for the Quit menu item.
- What happens if the last job summary is not available (first launch, no completed job)? View → Summary is greyed out.
- What happens if the log file does not exist yet? Tools → Open Log File shows an info dialog: "No log file found. Run a scan first."

---

## Requirements *(mandatory)*

### Functional Requirements

**File Menu**

- **FR-001**: The application MUST display a native menu bar visible at all times (top of window on Windows; screen top on macOS).
- **FR-002**: The File menu MUST contain: New Scan, Open Job State…, and Quit (macOS: Quit MediaScanner).
- **FR-003**: "New Scan" MUST navigate to the main configuration screen and reset all fields to defaults.
- **FR-004**: "Open Job State…" MUST open a file picker filtered to `.json` files and load a valid checkpoint into the configuration screen.
- **FR-005**: "Quit" MUST exit the application; if a scan is active, a confirmation dialog MUST be shown first.

**Edit / Preferences Menu**

- **FR-006**: The Edit menu MUST contain a Preferences (Windows/Linux) or Settings item; on macOS it MUST appear in the application menu per platform convention.
- **FR-007**: Selecting Preferences/Settings MUST open a modal Preferences dialog containing all user-configurable settings currently exposed in the Advanced Settings panel.
- **FR-008**: Settings changes in the Preferences dialog MUST be applied on OK and discarded on Cancel.
- **FR-009**: Settings changed via the Preferences dialog MUST be persisted to `~/.mediascanner/config.properties` and survive app restart.

**Job Menu**

- **FR-010**: The Job menu MUST contain: Start Scan, Pause, Resume, Stop, a separator, and Export Job State….
- **FR-011**: Start Scan, Pause, Resume, and Stop MUST be enabled/disabled to reflect the current job state (idle / running / paused / completed).
- **FR-012**: Pause, Resume, and Stop from the Job menu MUST produce identical behaviour to the corresponding dashboard buttons.
- **FR-013**: "Export Job State…" MUST be available whenever a job is running, paused, or completed in the current session.

**View Menu**

- **FR-014**: The View menu MUST contain: Configuration, Dashboard, Summary, a separator, and Toggle Dark Mode.
- **FR-015**: Configuration, Dashboard, and Summary navigation items MUST switch to the corresponding screen; Dashboard and Summary MUST be greyed out when no active or completed job exists in the current session.
- **FR-016**: "Toggle Dark Mode" MUST switch between light and dark UI appearance and persist the preference.

**Tools Menu**

- **FR-017**: The Tools menu MUST contain: View Failure Report, Open Log File, and Clear Hash Cache.
- **FR-018**: "View Failure Report" MUST open the most recent `_failures/failure-report.json` in the system default application, or show an information dialog if none exists.
- **FR-019**: "Open Log File" MUST open `~/.mediascanner/logs/mediascanner.log` in the system default text viewer, or show an information dialog if the file does not exist.
- **FR-020**: "Clear Hash Cache" MUST show a confirmation dialog before deleting all records from `FILE_HASH_INDEX`; a success notification MUST be shown after completion.

**Help Menu**

- **FR-021**: The Help menu MUST contain: About MediaScanner, Open User Guide, and Keyboard Shortcuts.
- **FR-022**: "About MediaScanner" MUST display a modal dialog showing application name, version, and copyright notice.
- **FR-023**: "Keyboard Shortcuts" MUST display a reference dialog or panel listing all menu keyboard shortcuts.

**Keyboard Shortcuts**

- **FR-024**: All primary menu actions MUST have keyboard shortcuts following platform conventions (⌘ on macOS, Ctrl on Windows/Linux). Minimum required shortcuts: New Scan (⌘N / Ctrl+N), Open Job State (⌘O / Ctrl+O), Quit (⌘Q / Ctrl+Q), Preferences (⌘, / Ctrl+,), Pause (⌘P / Ctrl+P), Stop (⌘. / Ctrl+.), Export Job State (⌘E / Ctrl+E).
- **FR-025**: Keyboard shortcuts MUST be displayed in the menu items alongside the action label.

### Key Entities

- **MenuBar**: The top-level container holding all menus; one instance per application window.
- **MenuItem**: An individual action entry within a menu; has a label, optional keyboard shortcut, and enabled/disabled state.
- **MenuState**: The set of rules that determine which menu items are enabled based on current application state (idle, scanning, paused, completed).
- **PreferencesDialog**: A modal dialog presenting all user-configurable settings grouped into logical sections.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All menu items are visible and correctly labelled within 1 second of application launch.
- **SC-002**: Keyboard shortcuts trigger their corresponding action within 200 ms of key press on both macOS and Windows.
- **SC-003**: Menu item enabled/disabled state updates within 500 ms of a job state change (idle → running → paused → completed).
- **SC-004**: Preferences dialog opens within 300 ms of selection and all current settings are correctly pre-populated.
- **SC-005**: Settings saved via the Preferences dialog are reflected in the next scan without requiring an app restart.
- **SC-006**: The "About" dialog is reachable in 2 clicks or fewer from any screen in the application.
- **SC-007**: 100% of existing scan control actions (Start, Pause, Resume, Stop, Export) are accessible via the menu bar in addition to their existing on-screen controls.
- **SC-008**: The application passes macOS HIG and Windows UX guidelines for menu bar placement and standard menu item naming.

---

## Assumptions

- The existing in-window controls (Start, Pause, Resume, Stop buttons) are retained; the menu bar is additive, not a replacement.
- On macOS, the Preferences item moves to the application menu ("MediaScanner") per HIG; on Windows/Linux it lives in the Edit menu.
- Dark Mode implementation uses JavaFX CSS theming; a full system-level dark mode integration (OS-level tinting) is out of scope for v1 — CSS-based theming is sufficient.
- "Open User Guide" points to a bundled HTML file in the first release; a live documentation URL is a future enhancement.
- Keyboard shortcuts follow the platform's primary modifier key convention automatically (JavaFX `KeyCombination` handles macOS vs Windows mapping).
- The Preferences dialog consolidates all settings currently in the Advanced Settings collapsible panel; no new settings are added in this feature.
- Clear Hash Cache does not affect in-progress scans; it takes effect from the next scan onward.
- The feature targets the same platforms as the core engine: macOS and Windows 10+.
