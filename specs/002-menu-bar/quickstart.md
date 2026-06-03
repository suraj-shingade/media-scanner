# Quickstart: Application Menu Bar

**Branch**: `002-menu-bar` | **Date**: 2026-06-03

This guide covers build, test, and validation for the menu bar feature.

---

## Prerequisites

Same as the core engine. See `specs/001-media-scanner-core/quickstart.md`.

| Tool | Version |
|------|---------|
| Java JDK | 21 LTS |
| Maven | 3.9+ |

---

## Build

```bash
# Build (no test run)
mvn clean package -DskipTests

# Run the app — menu bar should appear
mvn javafx:run
```

---

## Running Tests

```bash
# All unit tests
mvn test -Dtest="*Test"

# Menu bar specific tests
mvn test -Dtest="MenuBarControllerTest,AppStateManagerTest,PreferencesControllerTest,ScreenNavigatorTest,DarkModeManagerTest"

# Integration tests
mvn test -Dtest="*IT"
```

---

## Manual Acceptance Checklist (per story)

| Story | Test |
|-------|------|
| US1 | Launch app → File menu visible → New Scan resets form → Open Job State loads checkpoint → Quit with active scan shows confirmation |
| US2 | Open Preferences → all settings present → change value → OK → value persists on restart → Cancel → value unchanged |
| US3 | Start scan → Job menu: Pause enabled → Pause → Resume enabled → Resume → Stop → IDLE state restored |
| US4 | Start scan → View → Dashboard → dashboard shows → View → Configuration → config shown → View → Summary greyed until job completes |
| US5 | Run scan with bad files → Tools → View Failure Report → file opens → Tools → Open Log File → log opens → Tools → Clear Hash Cache → confirmation → cleared |
| US6 | Help → About → version dialog → Close → Help → Keyboard Shortcuts → shortcuts listed |

---

## Dark Mode Toggle

1. `View → Toggle Dark Mode` — UI switches to dark
2. Quit and relaunch — dark mode persists
3. `View → Toggle Dark Mode` again — reverts to light

---

## Keyboard Shortcut Smoke Test

Press each shortcut in turn and verify the action fires:

| Shortcut | Expected |
|----------|----------|
| ⌘N / Ctrl+N | Navigate to Configuration screen |
| ⌘O / Ctrl+O | File picker opens (json filter) |
| ⌘, / Ctrl+, | Preferences dialog opens |
| ⌘Q / Ctrl+Q | App exits (or confirmation if scan running) |
| ⌘1 / Ctrl+1 | Configuration screen |
| ⌘2 / Ctrl+2 | Dashboard (only if scan active/completed) |
| ⌘3 / Ctrl+3 | Summary (only if job completed) |
| F1 | User guide opens |

---

## AppState Transition Smoke Test

```
Launch → AppState = IDLE
  → Job menu: Start ✓, Pause ✗, Resume ✗, Stop ✗
Start scan → AppState = RUNNING
  → Job menu: Start ✗, Pause ✓, Resume ✗, Stop ✓
Pause → AppState = PAUSED
  → Job menu: Start ✗, Pause ✗, Resume ✓, Stop ✓
Resume → AppState = RUNNING
Stop → AppState = COMPLETED
  → Job menu: Start ✓, Pause ✗, Resume ✗, Stop ✗
  → View → Summary ✓ (now enabled)
```
