# Implementation Plan: MediaScanner Core Engine

**Branch**: `001-media-scanner-core` | **Date**: 2026-06-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-media-scanner-core/spec.md`

---

## Summary

MediaScanner is a high-performance JavaFX 21 desktop application for Windows and macOS that recursively scans, validates, and date-organizes image and video collections into configurable `/yyyy/MMM`-style folder structures. It covers 31 functional requirements spanning directory configuration, media classification, file validation, metadata extraction, SHA-256 deduplication with smart tiered hashing, job persistence/resume (checkpoint every 1,000 files or 60 s), real-time progress dashboard, and a comprehensive end-of-job summary — all backed by SQLite as the single source of truth and a parallel worker pool defaulting to `CPU cores × 2` threads.

---

## Technical Context

**Language/Version**: Java 21 LTS

**Primary Dependencies**:
- JavaFX 21 + ControlsFX + MaterialFX (desktop UI)
- Apache Tika + Metadata Extractor (image EXIF/metadata extraction)
- FFmpeg + FFprobe (video metadata, invoked as external process)
- Jackson (JSON job state serialization/deserialization)
- SQLite via sqlite-jdbc driver (persistent global hash index and job state)
- SLF4J + Logback (structured logging)
- JUnit 5 + AssertJ + Mockito (testing framework)

**Storage**: SQLite — single `.mediascanner.db` file in user home directory; global hash index shared across all jobs on the machine

**Testing**: JUnit 5 + AssertJ + Mockito; integration tests against a real SQLite test database (no mocking of DB layer — see project discipline)

**Target Platform**: Windows 10+, Windows 11, macOS — packaged via jpackage as native installer

**Project Type**: Desktop application (JavaFX, single-user, fully offline)

**Performance Goals**:
- Small files: 200–1,000 files/sec sustained
- Mixed media: 100–500 files/sec sustained
- Large video: 1–20 GB/sec disk throughput
- Pause response: ≤ 3 seconds after user action
- Checkpoint write: ≤ 100 ms per trigger
- Resume detection on startup: ≤ 5 seconds

**Constraints**:
- All I/O paths MUST use parallel worker threads (default: CPU cores × 2, user-configurable)
- SQLite is the SSOT — no in-memory-only state for any resumable job
- No source file deleted except in confirmed-success Move mode
- Application continues running in background during processing
- Hash index is global (one SQLite DB for all jobs on the machine)
- Hash cache valid only when file size AND modification timestamp match indexed values

**Scale/Scope**: 10 M+ files, 20 TB+ archives, single-user desktop

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Requirement | Status |
|-----------|------------|--------|
| **I. Performance-First** | Parallel workers for all I/O; tiered hashing; RAM caching; High-Priority Mode | ✅ PASS |
| **II. Context Preservation** | Checkpoint ≤ 100 ms; resume ≤ 5 s; JSON state; pause ≤ 3 s; background exec | ✅ PASS |
| **III. SQLite as SSOT** | FILE_HASH_INDEX + JOB_STATISTICS; indexed queries; versioned migrations; global index | ✅ PASS |
| **IV. Observability** | Real-time dashboard; rolling averages 5 s/30 s/job; ETA; CPU/mem/disk; historical graph | ✅ PASS |
| **V. Duplicate Handling** | Filename collision rename; hash 3-policy; source never deleted | ✅ PASS |
| **VI. Media Validation** | Empty/small/corrupt gates; failure + skipped buckets; counts in dashboard | ✅ PASS |
| **VII. Folder Organization** | Date-based structure; Move confirmed-success-before-delete | ✅ PASS |
| **VIII. Development Discipline** | TDD; independent story tests defined; I/O benchmarks in acceptance | ✅ PASS |

**Result**: ALL GATES PASS — proceed to Phase 0.

---

## Project Structure

### Documentation (this feature)

```text
specs/001-media-scanner-core/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   ├── job-state-schema.md      # JSON checkpoint contract
│   ├── ui-screens.md            # JavaFX screen/component contracts
│   └── sqlite-schema.md         # Database schema contract
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
src/
└── main/
    ├── java/
    │   └── com/mediascanner/
    │       ├── app/                        # JavaFX Application entry point
    │       │   └── MediaScannerApp.java
    │       ├── ui/                         # JavaFX controllers + FXML
    │       │   ├── MainController.java
    │       │   ├── DashboardController.java
    │       │   └── SummaryController.java
    │       ├── engine/                     # Core processing pipeline
    │       │   ├── ScanEngine.java         # Pipeline orchestrator
    │       │   ├── FileScanner.java        # Recursive directory traversal
    │       │   ├── FileValidator.java      # Empty/small/corrupt/ignore checks
    │       │   ├── MetadataExtractor.java  # EXIF + filesystem date fallback
    │       │   ├── HashEngine.java         # SHA-256 + tiered hashing (FR-025)
    │       │   └── FileTransfer.java       # Copy/Move with partial-file recovery
    │       ├── model/                      # Domain entities (POJOs)
    │       │   ├── Job.java
    │       │   ├── MediaFile.java
    │       │   ├── FileHashRecord.java
    │       │   ├── JobStatistics.java
    │       │   ├── CheckpointState.java
    │       │   ├── IgnoreRule.java
    │       │   ├── FailureRecord.java
    │       │   └── SkippedRecord.java
    │       ├── db/                         # SQLite persistence layer
    │       │   ├── Database.java           # Connection pool + migration runner
    │       │   ├── HashIndexDao.java       # FILE_HASH_INDEX CRUD + invalidation
    │       │   └── JobStatisticsDao.java   # JOB_STATISTICS CRUD
    │       ├── checkpoint/                 # Job state persistence
    │       │   ├── CheckpointManager.java  # Every 1,000 files or 60 s
    │       │   └── JobStateExporter.java   # Portable JSON import/export (FR-015)
    │       ├── monitor/                    # Real-time metrics
    │       │   ├── ProgressTracker.java    # Counters, rolling averages, ETA
    │       │   ├── ResourceMonitor.java    # CPU%, memory GB, disk R/W
    │       │   └── ThroughputHistory.java  # Historical graph data (FR-031)
    │       └── config/                     # User settings, ignore rules, thresholds
    │           └── AppConfig.java
    └── resources/
        ├── fxml/                           # JavaFX layout files
        ├── css/                            # MaterialFX/ControlsFX styling
        └── logback.xml

src/
└── test/
    └── java/
        └── com/mediascanner/
            ├── engine/                     # Unit + integration tests per engine component
            ├── db/                         # DB layer tests (real SQLite, no mocks)
            ├── checkpoint/
            └── monitor/
```

**Structure Decision**: Single Maven project. JavaFX MVC pattern with clearly separated `engine`, `model`, `db`, `checkpoint`, and `monitor` packages. No multi-module overhead needed for a single-user desktop tool at this scope.

---

## Complexity Tracking

> No constitution violations. No complexity justifications required.
