# Contract: UI Screens and Component Contracts

**Feature**: MediaScanner Core Engine | **Date**: 2026-06-03

This contract defines the screens, components, and their behavioral contracts for the MediaScanner JavaFX desktop application. Covers what each screen MUST display and what user actions it MUST support.

---

## Screen Inventory

| Screen | FXML File | Controller | When Shown |
|--------|-----------|------------|------------|
| Main / Job Setup | `main.fxml` | `MainController` | On launch (no active job) |
| Active Job Dashboard | `dashboard.fxml` | `DashboardController` | While job is RUNNING or PAUSED |
| End-of-Job Summary | `summary.fxml` | `SummaryController` | When job is COMPLETED, FAILED, or STOPPED |
| Startup Resume Dialog | (modal overlay) | `MainController` | On startup if interrupted job detected |
| Settings Panel | (inline panel) | `MainController` | User opens settings before starting job |

---

## Screen 1: Main / Job Setup

### Required fields (all mandatory before Start is enabled)

| Field | Type | Default | Validation |
|-------|------|---------|------------|
| Source Directory | File path input + Browse button | empty | Must be accessible, non-empty directory |
| Target Directory | File path input + Browse button | empty | Must be accessible; must differ from source |
| Transfer Mode | Radio button: Copy / Move | Copy | Required selection |
| Folder Pattern | Dropdown | `/yyyy/MMM` | One of four options |
| Duplicate Policy | Dropdown | Skip | Skip / Move to `/_duplicates` / Keep Both |

### Required controls

| Control | Action |
|---------|--------|
| Start button | Disabled until both paths are valid and distinct; launches job |
| Settings toggle | Expands settings panel |
| Import Job State button | Opens file picker for FR-015 import |

### Settings panel (expandable)

| Setting | Type | Default |
|---------|------|---------|
| Worker thread count | Numeric input | `cores × 2` |
| High-Priority Mode | Checkbox | unchecked |
| Image min size (KB) | Numeric input | 10 |
| Video min size (KB) | Numeric input | 100 |
| Ignore patterns | Editable list | defaults shown |

### Error states

- Source = Target: display inline error "Source and target must be different directories"
- Source not accessible: display inline error "Directory not found or not readable"
- Start clicked with empty paths: Start button remains disabled (never a runtime error)

---

## Screen 2: Active Job Dashboard

MUST display all of the following, updating at least once per second (SC-007):

### File Statistics Panel

| Metric | Display |
|--------|---------|
| Total Files Found | Integer |
| Files Processed | Integer |
| Files Remaining | Integer |
| Files Copied | Integer |
| Files Moved | Integer |
| Files Skipped | Integer |
| Files Failed | Integer |
| Duplicates Detected | Integer |

### Data Transfer Panel

| Metric | Display |
|--------|---------|
| Total Data Processed | Auto-scaled (B/KB/MB/GB/TB) |
| Data Copied | Auto-scaled |
| Data Moved | Auto-scaled |
| Data Skipped | Auto-scaled |
| Duplicate Data Saved | Auto-scaled |

### Throughput Panel

| Metric | Display |
|--------|---------|
| Files/sec (5 s avg) | Decimal |
| Files/sec (30 s avg) | Decimal |
| Files/sec (job avg) | Decimal |
| MB/sec (5 s avg) | Decimal |
| MB/sec (30 s avg) | Decimal |
| MB/sec (job avg) | Decimal |
| ETA | `HH:MM:SS` format |

### Resource Utilization Panel

| Metric | Display |
|--------|---------|
| CPU Usage | Percentage |
| Memory Usage | GB (decimal) |
| Disk Read Speed | MB/sec |
| Disk Write Speed | MB/sec |
| Active Worker Threads | `N / MAX` format (e.g., `32 / 32`) |

### Historical Throughput Graph (FR-031)

- X-axis: elapsed time (seconds)
- Y-axis: files/sec, MB/sec (dual axis), CPU%, memory%
- Scrolls or compresses as job runs longer than visible window
- Updated at the same 1 Hz rate as other dashboard metrics

### Job Control Bar

| Control | Enabled When | Action |
|---------|-------------|--------|
| Pause button | RUNNING | Suspends processing within 3 s |
| Resume button | PAUSED | Resumes processing |
| Stop button | RUNNING or PAUSED | Safely terminates job |
| Export State button | RUNNING or PAUSED | Triggers FR-015 export |

---

## Screen 3: End-of-Job Summary

Displayed when job reaches COMPLETED, FAILED, or STOPPED status.

### Required sections (all must be present)

**Files Summary**

| Metric | Value |
|--------|-------|
| Total Files Found | integer |
| Total Files Processed | integer |
| Total Files Copied | integer |
| Total Files Moved | integer |
| Total Files Skipped | integer |
| — Empty files | integer (sub-count) |
| — Small files | integer (sub-count) |
| — Unsupported format | integer (sub-count) |
| — Ignore rule matched | integer (sub-count) |
| — Metadata missing | integer (sub-count) |
| Total Files Failed | integer |
| Total Duplicate Files | integer |

**Data Summary**

| Metric | Value |
|--------|-------|
| Total Data Scanned | auto-scaled |
| Total Data Copied | auto-scaled |
| Total Data Moved | auto-scaled |
| Total Data Skipped | auto-scaled |
| Duplicate Data Saved | auto-scaled |

**Performance Summary**

| Metric | Value |
|--------|-------|
| Peak Files/sec | decimal |
| Average Files/sec | decimal |
| Peak MB/sec | decimal |
| Average MB/sec | decimal |
| Peak GB/sec | decimal (if applicable) |
| Average GB/sec | decimal (if applicable) |

**Infrastructure Summary**

| Metric | Value |
|--------|-------|
| Average CPU Utilization | percentage |
| Peak CPU Utilization | percentage |
| Average Memory Usage | GB |
| Peak Memory Usage | GB |

**Execution Summary**

| Metric | Value |
|--------|-------|
| Start Time | ISO 8601 |
| End Time | ISO 8601 |
| Total Duration | `HH:MM:SS` |
| Total Folders Created | integer |

### Controls

| Control | Action |
|---------|--------|
| Export Report button | Opens file picker; saves summary as JSON or plain text |
| Start New Job button | Returns to Main / Job Setup screen |
| View Failure Report button | Opens `/_failures/failure-report.json` in system default viewer |

---

## Screen 4: Startup Resume Dialog (Modal)

Appears within 5 seconds of launch when an interrupted job is detected.

**Required content**:
- Job ID and source/target paths of the interrupted job
- Checkpoint time (when the last checkpoint was written)
- Files processed at last checkpoint

**Required controls**:

| Control | Action |
|---------|--------|
| Resume button | Loads checkpoint, returns to Dashboard screen, resumes job |
| Start Fresh button | Clears the interrupted job state, returns to Main screen |

**Behavioral contract**: If the database is missing or corrupt (Q3 clarification), this dialog is replaced by a warning: "Hash cache lost — all previously indexed files will be re-hashed. Starting fresh." with only a single "Continue" button.

---

## Auto-Scaling Unit Rules (FR-027)

Applied consistently across all data volume displays:

| Threshold | Display Unit |
|-----------|-------------|
| < 1,024 bytes | B |
| < 1,048,576 bytes | KB (1 decimal place) |
| < 1,073,741,824 bytes | MB (2 decimal places) |
| < 1,099,511,627,776 bytes | GB (2 decimal places) |
| ≥ 1,099,511,627,776 bytes | TB (2 decimal places) |
