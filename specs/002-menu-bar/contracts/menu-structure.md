# Contract: Menu Bar Structure

**Feature**: Application Menu Bar | **Date**: 2026-06-03

This contract defines the authoritative menu structure, keyboard shortcuts, and state rules. All implementations MUST match this contract exactly.

---

## Menu Structure

### macOS Layout

```
[MediaScanner] [File]  [Edit]  [Job]  [View]  [Tools]  [Help]
```

On macOS, `setUseSystemMenuBar(true)` moves the bar to the screen top. The "MediaScanner" application menu (auto-created by JavaFX) contains: About MediaScanner, Preferences…, and Quit MediaScanner — placed there by the OS-level system menu bar integration.

### Windows / Linux Layout

```
[File]  [Edit]  [Job]  [View]  [Tools]  [Help]
```

The menu bar is embedded at the top of the application window.

---

## File Menu

| # | Label | Shortcut | State |
|---|-------|----------|-------|
| 1 | New Scan | ⌘N / Ctrl+N | Always enabled |
| 2 | Open Job State… | ⌘O / Ctrl+O | Always enabled |
| — | *separator* | — | — |
| 3 | Quit MediaScanner *(macOS)* / Quit *(Win/Lin)* | ⌘Q / Ctrl+Q | Always enabled; confirmation dialog if job active |

---

## Edit Menu (Windows/Linux) / MediaScanner App Menu (macOS)

| # | Label | Shortcut | State |
|---|-------|----------|-------|
| 1 | Preferences… *(Win/Lin)* / Settings… *(macOS app menu)* | ⌘, / Ctrl+, | Always enabled |

---

## Job Menu

| # | Label | Shortcut | Enabled when AppState |
|---|-------|----------|-----------------------|
| 1 | Start Scan | ⌘↵ / Ctrl+Enter | IDLE, COMPLETED |
| 2 | Pause | ⌘P / Ctrl+P | RUNNING |
| 3 | Resume | ⌘R / Ctrl+R | PAUSED |
| 4 | Stop | ⌘. / Ctrl+. | RUNNING, PAUSED |
| — | *separator* | — | — |
| 5 | Export Job State… | ⌘E / Ctrl+E | RUNNING, PAUSED, COMPLETED |

---

## View Menu

| # | Label | Shortcut | Enabled when AppState |
|---|-------|----------|-----------------------|
| 1 | Configuration | ⌘1 / Ctrl+1 | Always enabled |
| 2 | Dashboard | ⌘2 / Ctrl+2 | RUNNING, PAUSED, COMPLETED |
| 3 | Summary | ⌘3 / Ctrl+3 | COMPLETED |
| — | *separator* | — | — |
| 4 | Toggle Dark Mode | ⌘D / Ctrl+D | Always enabled; CheckMenuItem (shows checkmark when active) |

---

## Tools Menu

| # | Label | Shortcut | Enabled when AppState |
|---|-------|----------|-----------------------|
| 1 | View Failure Report | — | Always enabled (shows info dialog if no report) |
| 2 | Open Log File | — | Always enabled (shows info dialog if no log) |
| — | *separator* | — | — |
| 3 | Clear Hash Cache… | — | IDLE, COMPLETED (never during active scan) |

---

## Help Menu

| # | Label | Shortcut | State |
|---|-------|----------|-------|
| 1 | About MediaScanner | — | Always enabled |
| 2 | Open User Guide | F1 | Always enabled |
| — | *separator* | — | — |
| 3 | Keyboard Shortcuts | — | Always enabled |

---

## State → Menu Binding Rules

| AppState | Start | Pause | Resume | Stop | Export | Dashboard | Summary | Clear Cache |
|----------|-------|-------|--------|------|--------|-----------|---------|-------------|
| IDLE | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ |
| RUNNING | ✗ | ✓ | ✗ | ✓ | ✓ | ✓ | ✗ | ✗ |
| PAUSED | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ |
| COMPLETED | ✓ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ |

---

## Preferences Dialog Sections

The Preferences dialog MUST contain these sections (tabs or titled panes):

### Performance
- Worker threads (integer, 0 = CPU×2, min 1, max 512)
- High-Priority Mode (checkbox)

### Validation
- Image min size in KB (integer, min 1)
- Video min size in KB (integer, min 1)

### Organisation
- Default folder pattern (dropdown: YYYY/MMM, YYYY/MM, YYYY/MMM/DD, YYYY/MM/DD)
- Default duplicate policy (dropdown: Skip, Move to /_duplicates, Keep Both)

### Ignore Patterns
- List of current patterns (editable)
- Add / Remove buttons

---

## About Dialog Content

```
[App Icon]
MediaScanner
Version {version}

High-performance media file organiser for macOS and Windows.

© 2026 MediaScanner. All rights reserved.

[Close]
```

`{version}` is read from `Implementation-Version` in the JAR manifest. Falls back to `"1.0.0"`.

---

## Keyboard Shortcuts Reference Dialog

A non-modal window (or Alert) listing all shortcuts in two-column table format:

| Action | Shortcut |
|--------|----------|
| New Scan | ⌘N / Ctrl+N |
| Open Job State | ⌘O / Ctrl+O |
| Preferences | ⌘, / Ctrl+, |
| Quit | ⌘Q / Ctrl+Q |
| Start Scan | ⌘↵ / Ctrl+Enter |
| Pause | ⌘P / Ctrl+P |
| Resume | ⌘R / Ctrl+R |
| Stop | ⌘. / Ctrl+. |
| Export Job State | ⌘E / Ctrl+E |
| Configuration screen | ⌘1 / Ctrl+1 |
| Dashboard screen | ⌘2 / Ctrl+2 |
| Summary screen | ⌘3 / Ctrl+3 |
| Toggle Dark Mode | ⌘D / Ctrl+D |
| User Guide | F1 |
