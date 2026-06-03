---
description: "Task list for MediaScanner Core Engine — 001-media-scanner-core"
---

# Tasks: MediaScanner Core Engine

**Input**: Design documents from `specs/001-media-scanner-core/`

**Prerequisites**: plan.md ✅ | spec.md ✅ | data-model.md ✅ | research.md ✅ | contracts/ ✅ | quickstart.md ✅

**Stack**: Java 21 LTS · JavaFX 21 · Maven · SQLite (sqlite-jdbc) · Apache Tika · FFprobe · Jackson · JNA · JUnit 5

**Organization**: Tasks grouped by user story for independent implementation and testing.

---

## Format: `- [ ] [ID] [P?] [Story?] Description — file path`

- **[P]**: Can run in parallel (different files, no shared state)
- **[Story]**: Which user story this task belongs to (US1–US9)
- File paths relative to repository root

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Initialize Maven project, configure all dependencies, and scaffold package structure.

- [X] T001 Initialize Maven project with Java 21 and JavaFX 21 in `pom.xml` — `pom.xml`
- [X] T002 [P] Add all required dependencies to `pom.xml`: sqlite-jdbc, Apache Tika, Metadata Extractor, Jackson, SLF4J/Logback, ControlsFX, MaterialFX, JNA, JUnit 5, AssertJ, Mockito — `pom.xml`
- [X] T003 [P] Create Maven directory structure: `src/main/java/com/mediascanner/`, `src/test/java/com/mediascanner/`, `src/main/resources/fxml/`, `src/main/resources/css/`, `src/main/resources/db/migrations/` — directory scaffold
- [X] T004 [P] Create all package placeholder files: `app/`, `ui/`, `engine/`, `model/`, `db/`, `checkpoint/`, `monitor/`, `config/` — `src/main/java/com/mediascanner/`
- [X] T005 [P] Configure `logback.xml` with rolling file appender to `~/.mediascanner/logs/` — `src/main/resources/logback.xml`
- [X] T006 [P] Configure Maven JavaFX plugin (`javafx-maven-plugin`) with main class `com.mediascanner.app.MediaScannerApp` — `pom.xml`
- [X] T007 [P] Configure Maven jpackage plugin for `.dmg` (macOS) and `.msi` (Windows) with bundled JRE — `pom.xml`
- [X] T008 [P] Create `MediaScannerApp.java` JavaFX Application entry point that loads `main.fxml` — `src/main/java/com/mediascanner/app/MediaScannerApp.java`

**Checkpoint**: `mvn clean compile` succeeds with no errors.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Database layer, domain models, worker pool, and `AppConfig` — all user stories depend on these.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T009 Create `AppConfig.java` reading `~/.mediascanner/config.properties` with defaults: thread count (`cores × 2`), image min KB (10), video min KB (100), folder pattern (`YYYY_MMM`), duplicate policy (`SKIP`), high-priority mode (false) — `src/main/java/com/mediascanner/config/AppConfig.java`
- [X] T010 [P] Create all domain model POJOs with fields matching `data-model.md`: `Job.java`, `MediaFile.java`, `FileHashRecord.java`, `JobStatistics.java`, `CheckpointState.java`, `IgnoreRule.java`, `FailureRecord.java`, `SkippedRecord.java` — `src/main/java/com/mediascanner/model/`
- [X] T011 Create V001 SQLite migration script with full DDL from `contracts/sqlite-schema.md`: `FILE_HASH_INDEX` table, `JOB_STATISTICS` table, indexes, WAL/foreign-key pragmas — `src/main/resources/db/migrations/V001__initial_schema.sql`
- [X] T012 Create `Database.java`: open/create `~/.mediascanner/mediascanner.db`, run `PRAGMA integrity_check` on startup (rename corrupt + rebuild empty + flag warning if failed), read `PRAGMA user_version`, apply pending migration scripts in numeric order — `src/main/java/com/mediascanner/db/Database.java`
- [X] T013 [P] Create `HashIndexDao.java`: insert, findBySha256, findByFilePath, updateRecord, deleteByFilePath; cache-invalidation logic (re-hash if size or mtime differ) — `src/main/java/com/mediascanner/db/HashIndexDao.java`
- [X] T014 [P] Create `JobStatisticsDao.java`: insert, updateCounters (atomic UPDATE), findActiveJob (STATUS IN RUNNING/PAUSED), markCompleted — `src/main/java/com/mediascanner/db/JobStatisticsDao.java`
- [X] T015 [P] Integration test `DatabaseIT.java`: open DB, run migration, verify both tables exist, verify WAL mode, verify integrity_check passes — `src/test/java/com/mediascanner/db/DatabaseIT.java`
- [X] T016 [P] Integration test `HashIndexDaoIT.java`: insert record, findBySha256, cache-invalidation (modify size → triggers re-hash path), duplicate detection — `src/test/java/com/mediascanner/db/HashIndexDaoIT.java`
- [X] T017 Create `ScanEngine.java`: initializes `ExecutorService` with configurable thread pool size from `AppConfig`, manages the processing pipeline lifecycle (start/pause/resume/stop), exposes `pauseRequested` flag checked by workers — `src/main/java/com/mediascanner/engine/ScanEngine.java`

**Checkpoint**: `mvn test -Dtest="DatabaseIT,HashIndexDaoIT"` passes against a real SQLite test DB.

---

## Phase 3: User Story 1 — Directory Setup and Scan Configuration (Priority: P1) 🎯 MVP

**Goal**: User can launch the app, select source and target directories, configure transfer mode and folder pattern, and start a scan that processes and organizes media files.

**Independent Test**: Copy 100 mixed media files; verify year/month folders created at target. (see `quickstart.md` US1 row)

### Implementation for User Story 1

- [X] T018 [P] [US1] Create `Job.java` factory method `Job.create(sourcePath, targetPath, transferMode, folderPattern, duplicatePolicy, AppConfig)` that validates source ≠ target and both accessible — `src/main/java/com/mediascanner/model/Job.java`
- [X] T019 [P] [US1] Create `FileScanner.java`: `walkFileTree()` using `Files.walk()` to recursively enumerate all files; returns `Stream<Path>`; applies `IgnoreRule` glob matching via `PathMatcher` before emitting — `src/main/java/com/mediascanner/engine/FileScanner.java`
- [X] T020 [US1] Create `MetadataExtractor.java` priority chain: (1) Apache Tika EXIF `DateTimeOriginal`, (2) `BasicFileAttributes.creationTime()`, (3) `lastModifiedTime()`; normalize to `LocalDateTime` ISO 8601; video files delegate to FFprobe sub-task — `src/main/java/com/mediascanner/engine/MetadataExtractor.java`
- [X] T021 [US1] Create `FileTransfer.java`: `copy(source, destination)` using `Files.copy(REPLACE_EXISTING)` then size-verify; `move(source, destination)` = copy + verify + delete source; partial-file detection on resume (target exists but size ≠ source → delete target + re-transfer) — `src/main/java/com/mediascanner/engine/FileTransfer.java`
- [X] T022 [US1] Create `main.fxml` and `MainController.java`: source Browse button (opens `DirectoryChooser`), target Browse button, Transfer Mode radio group (Copy/Move), Folder Pattern dropdown (4 options), Duplicate Policy dropdown (3 options), Start button (disabled until both paths valid and distinct), Settings toggle panel, Import Job State button — `src/main/resources/fxml/main.fxml`, `src/main/java/com/mediascanner/ui/MainController.java`
- [X] T023 [US1] Wire `ScanEngine.start(job)` to the Start button action in `MainController`; transition UI to Dashboard screen on start — `src/main/java/com/mediascanner/ui/MainController.java`
- [X] T024 [US1] Unit test `FileScannerTest.java`: source with 5 nesting levels + `.DS_Store` + `.pdf` → only media files returned; custom ignore pattern works; empty source returns empty stream — `src/test/java/com/mediascanner/engine/FileScannerTest.java`
- [X] T025 [P] [US1] Unit test `MetadataExtractorTest.java`: EXIF date takes priority; fallback to creation date; fallback to modified date; all-missing → empty result — `src/test/java/com/mediascanner/engine/MetadataExtractorTest.java`
- [X] T026 [P] [US1] Unit test `FileTransferTest.java`: copy preserves source; move deletes source after size-verify; partial-file cleanup on resume; cross-drive path handling — `src/test/java/com/mediascanner/engine/FileTransferTest.java`

**Checkpoint**: US1 independent test passes. Copy 100 mixed files → folders appear at target in correct `/yyyy/MMM` structure.

---

## Phase 4: User Story 2 — Recursive Scanning and Media Classification (Priority: P1)

**Goal**: System correctly traverses all subfolders, classifies files by media type, and applies ignore rules — only supported media files enter the work queue.

**Independent Test**: Source with 5 nesting levels + `.DS_Store` + `.pdf` → only supported media files queued. (see `quickstart.md` US2 row)

### Implementation for User Story 2

- [X] T027 [US2] Extend `FileScanner.java` with `classifyMediaType(Path)`: returns `MediaFile.FileType.IMAGE` or `VIDEO` for supported extensions (FR-003 full list), returns null for unsupported — `src/main/java/com/mediascanner/engine/FileScanner.java`
- [X] T028 [P] [US2] Implement `IgnoreRule` glob matching in `FileScanner.java` using `FileSystems.getDefault().getPathMatcher("glob:" + pattern)` case-insensitively; test all 7 default patterns — `src/main/java/com/mediascanner/engine/FileScanner.java`
- [X] T029 [P] [US2] Expose ignore rule management in `AppConfig.java`: load defaults on first run, allow adding user patterns, persist to `~/.mediascanner/config.properties` — `src/main/java/com/mediascanner/config/AppConfig.java`
- [X] T030 [US2] Add ignore pattern UI to Settings panel in `main.fxml`: editable list showing current patterns, Add Pattern button, Remove button — `src/main/resources/fxml/main.fxml`, `src/main/java/com/mediascanner/ui/MainController.java`
- [X] T031 [P] [US2] Unit test `MediaClassificationTest.java`: all 14 image extensions → IMAGE; all 8 video extensions → VIDEO; `.pdf`, `.docx` → null (unsupported); all 7 default ignore patterns → filtered out; custom pattern works — `src/test/java/com/mediascanner/engine/MediaClassificationTest.java`

**Checkpoint**: US2 independent test passes. Media-only queue confirmed; system files absent.

---

## Phase 5: User Story 3 — File Validation and Quality Filtering (Priority: P2)

**Goal**: Every file passes a quality gate before transfer — zero-byte, small, corrupt, and ignored files are logged to the correct buckets.

**Independent Test**: 0-byte jpg + 5 KB jpg + corrupt mp4 + 10 valid files → 3 in buckets, 10 transferred. (see `quickstart.md` US3 row)

### Implementation for User Story 3

- [X] T032 [US3] Create `FileValidator.java` with gates in order: (1) size == 0 → SKIPPED/EMPTY_FILE; (2) image < threshold KB → SKIPPED/SMALL_FILE; (3) video < threshold KB → SKIPPED/SMALL_FILE; (4) media readability check via Apache Tika `detect()` → FAILED if unreadable; returns `MediaFile` with `validationStatus` and reason set — `src/main/java/com/mediascanner/engine/FileValidator.java`
- [X] T033 [P] [US3] Implement `FailureRecord` writing: `FileTransfer.java` appends to `/_failures/failure-report.json` at target using Jackson `ObjectMapper` in append mode; creates file on first failure — `src/main/java/com/mediascanner/engine/FileTransfer.java`
- [X] T034 [P] [US3] Wire `FileValidator` into `ScanEngine` pipeline: validator runs after `FileScanner`, before `MetadataExtractor`; failed/skipped files routed to buckets and counted in `JobStatistics` — `src/main/java/com/mediascanner/engine/ScanEngine.java`
- [X] T035 [US3] Add configurable size thresholds to Settings panel in `main.fxml`: "Image min size (KB)" numeric input, "Video min size (KB)" numeric input — `src/main/resources/fxml/main.fxml`, `src/main/java/com/mediascanner/ui/MainController.java`
- [X] T036 [P] [US3] Unit test `FileValidatorTest.java`: 0-byte file → EMPTY_FILE; 5 KB image → SMALL_FILE with default threshold; 5 KB image → valid with 4 KB custom threshold; corrupt MP4 → FAILED with reason; valid file → VALID — `src/test/java/com/mediascanner/engine/FileValidatorTest.java`

**Checkpoint**: US3 independent test passes. Bucket counts match in dashboard and summary.

---

## Phase 6: User Story 4 — Metadata Extraction and Date Standardization (Priority: P2)

**Goal**: Every valid media file gets its best available date extracted using the priority chain; all dates normalized to ISO 8601; date drives target folder selection.

**Independent Test**: EXIF file → EXIF folder; no-EXIF+creation-date → creation-date folder; modified-only → modified-date folder. (see `quickstart.md` US4 row)

### Implementation for User Story 4

- [X] T037 [US4] Extend `MetadataExtractor.java`: add FFprobe subprocess invocation for video files — `ffprobe -v quiet -print_format json -show_format <file>` → parse `format.tags.creation_time`; fallback to filesystem dates if FFprobe absent or returns no date — `src/main/java/com/mediascanner/engine/MetadataExtractor.java`
- [X] T038 [P] [US4] Implement folder path computation in `MetadataExtractor.java`: given `LocalDateTime` and `FolderPattern` enum, compute target subdirectory string (`yyyy/MMM`, `yyyy/MM`, `yyyy/MMM/dd`, `yyyy/MM/dd`); handle future dates without error — `src/main/java/com/mediascanner/engine/MetadataExtractor.java`
- [X] T039 [P] [US4] Handle "metadata missing" case in `ScanEngine.java`: if all three date sources unavailable, set `MediaFile.skipReason = METADATA_MISSING` and route to Skipped bucket — `src/main/java/com/mediascanner/engine/ScanEngine.java`
- [X] T040 [P] [US4] Unit test `FolderPatternTest.java`: each of the 4 patterns produces the correct directory string for a known date; future date → no error; all four patterns tested — `src/test/java/com/mediascanner/engine/FolderPatternTest.java`
- [X] T041 [P] [US4] Unit test `FFprobeExtractorTest.java`: mock FFprobe subprocess output → correct date extracted; FFprobe missing → fallback to filesystem date — `src/test/java/com/mediascanner/engine/FFprobeExtractorTest.java`

**Checkpoint**: US4 independent test passes. Each test file lands in the folder matching its date source.

---

## Phase 7: User Story 5 — Filename Collision and Content Duplicate Handling (Priority: P2)

**Goal**: Filename collisions are renamed with `(N)` suffix; content duplicates handled by configured policy; source files never deleted by duplicate logic.

**Independent Test**: Name collision + content duplicate + unique file → all three handled correctly. (see `quickstart.md` US5 row)

### Implementation for User Story 5

- [X] T042 [US5] Create `HashEngine.java` with three-stage tiered hashing: Stage 1 compares `file.length()` and `fileName` against index via `HashIndexDao.findByFilePath()`; Stage 2 reads first 64 KB → partial SHA-256; Stage 3 full SHA-256 reading in 8 MB chunks; returns hash string; persists result to `FILE_HASH_INDEX` via `HashIndexDao` — `src/main/java/com/mediascanner/engine/HashEngine.java`
- [X] T043 [P] [US5] Implement filename collision resolution in `FileTransfer.java`: before writing, check if `destinationPath` exists; if so, append `(1)`, `(2)`, etc. until a free name is found — `src/main/java/com/mediascanner/engine/FileTransfer.java`
- [X] T044 [P] [US5] Implement duplicate policy routing in `ScanEngine.java`: after `HashEngine` computes hash, call `HashIndexDao.findBySha256(hash)`; if match found and different path → apply `duplicatePolicy`: SKIP (count + skip), MOVE_TO_BUCKET (transfer to `/_duplicates/`), KEEP_BOTH (transfer with `_DUP_N` suffix); source never deleted — `src/main/java/com/mediascanner/engine/ScanEngine.java`
- [X] T045 [P] [US5] Unit test `HashEngineTest.java`: Stage 1 eliminates unique files; Stage 2 runs only on size+name collision; Stage 3 only on partial-hash collision; cached hash used when size+mtime match; re-hash triggered when either changes; 1 GB+ file runs through tiered stages — `src/test/java/com/mediascanner/engine/HashEngineTest.java`
- [X] T046 [P] [US5] Unit test `DuplicatePolicyTest.java`: Skip policy → file not transferred, counted; Move policy → file appears in `/_duplicates/`; Keep Both → file transferred with `_DUP_1` suffix; source never deleted in any policy — `src/test/java/com/mediascanner/engine/DuplicatePolicyTest.java`
- [X] T047 [P] [US5] Unit test `FilenameCollisionTest.java`: `IMG001.jpg` exists at target → incoming becomes `IMG001(1).jpg`; `IMG001(1).jpg` also exists → `IMG001(2).jpg` — `src/test/java/com/mediascanner/engine/FilenameCollisionTest.java`

**Checkpoint**: US5 independent test passes. All three cases (collision, duplicate, unique) handled correctly.

---

## Phase 8: User Story 6 — Job Control: Pause, Resume, and Stop (Priority: P2)

**Goal**: Users can pause (≤ 3 s), resume, stop safely, export/import job state, and the app auto-resumes interrupted jobs on startup (≤ 5 s).

**Independent Test**: 50K file job → pause at ~10K → close app → reopen → resume → count continuity verified. (see `quickstart.md` US6 row)

### Implementation for User Story 6

- [X] T048 [US6] Create `CheckpointManager.java`: dual-trigger persistence — `AtomicLong` file counter triggers JSON write every 1,000; `ScheduledExecutorService` fires every 60 s; writes `checkpoint.json` atomically via `Files.move(..., ATOMIC_MOVE)` to `~/.mediascanner/jobs/<jobId>/`; updates `JOB_STATISTICS` row in same logical step — `src/main/java/com/mediascanner/checkpoint/CheckpointManager.java`
- [X] T049 [P] [US6] Create `JobStateExporter.java`: `export(job, targetPath)` serializes `CheckpointState` to JSON via Jackson; `importFrom(sourcePath)` deserializes and validates paths are accessible before enabling Resume — `src/main/java/com/mediascanner/checkpoint/JobStateExporter.java`
- [X] T050 [P] [US6] Implement pause/resume in `ScanEngine.java`: `pauseRequested` flag (volatile boolean); workers check flag at start of each file; pause response time ≤ 3 s guaranteed because each worker completes current file then checks flag; `STATUS` updated to PAUSED in DB — `src/main/java/com/mediascanner/engine/ScanEngine.java`
- [X] T051 [P] [US6] Implement stop in `ScanEngine.java`: `stopRequested` flag; workers complete current file then exit; no partial files left at target (in-flight copy completes, size-verify runs); `STATUS` updated to STOPPED — `src/main/java/com/mediascanner/engine/ScanEngine.java`
- [X] T052 [US6] Implement startup resume detection in `Database.java`/`MainController.java`: on app start, call `JobStatisticsDao.findActiveJob()`; if found, load `checkpoint.json`, show Startup Resume Dialog (per `contracts/ui-screens.md`) within 5 s; handle missing/corrupt DB per Q3 logic — `src/main/java/com/mediascanner/db/Database.java`, `src/main/java/com/mediascanner/ui/MainController.java`
- [X] T053 [P] [US6] Implement Move-mode partial-file recovery in `FileTransfer.java`: on resume, before transferring a file check if partial target exists (`target.exists() && Files.size(target) != Files.size(source)`) → delete partial → re-transfer — `src/main/java/com/mediascanner/engine/FileTransfer.java`
- [X] T054 [US6] Add Pause/Resume/Stop buttons and Export State button to `dashboard.fxml` per `contracts/ui-screens.md`; wire to `ScanEngine` methods; Pause button disables after click until PAUSED state confirmed — `src/main/resources/fxml/dashboard.fxml`, `src/main/java/com/mediascanner/ui/DashboardController.java`
- [X] T055 [P] [US6] Unit test `CheckpointManagerTest.java`: verify checkpoint written at 1,000-file trigger; verify checkpoint written at 60 s trigger; verify atomic write (`.tmp` rename); verify JSON schema matches contract — `src/test/java/com/mediascanner/checkpoint/CheckpointManagerTest.java`
- [X] T056 [P] [US6] Integration test `ResumeIT.java`: start job → pause at 500 files → close DB connection → reopen → confirm `findActiveJob()` returns the job → load checkpoint → verify processedFiles count — `src/test/java/com/mediascanner/checkpoint/ResumeIT.java`

**Checkpoint**: US6 independent test passes. Pause ≤ 3 s; resume ≤ 5 s on startup; processed count continuity confirmed.

---

## Phase 9: User Story 7 — Real-Time Progress Dashboard (Priority: P3)

**Goal**: During any active job, the dashboard updates ≥ 1/sec with file counts, data volumes, throughput, ETA, resource utilization, and historical graph.

**Independent Test**: 100K file job → dashboard shows updating counts, ETA decreasing, CPU% non-zero. (see `quickstart.md` US7 row)

### Implementation for User Story 7

- [X] T057 [US7] Create `ProgressTracker.java`: thread-safe `AtomicLong` counters for all 8 file stats and 5 data volume stats from `contracts/ui-screens.md`; circular buffer (150 samples) for 5 s and 30 s rolling averages; ETA calculation from `(remainingFiles / avgFilesPerSec) + (remainingBytes / avgBytesPerSec)`; exposes snapshot method for UI reads — `src/main/java/com/mediascanner/monitor/ProgressTracker.java`
- [X] T058 [P] [US7] Create `ResourceMonitor.java`: samples CPU% via `com.sun.management.OperatingSystemMXBean.getSystemCpuLoad()`; memory via `Runtime.getRuntime()` used heap; disk R/W via `java.io` stats delta between 1 s samples; wraps in a `ScheduledExecutorService` at 1 Hz — `src/main/java/com/mediascanner/monitor/ResourceMonitor.java`
- [X] T059 [P] [US7] Create `ThroughputHistory.java`: stores time-series of files/sec, MB/sec, CPU%, memory% as `ArrayList` with max 3,600 samples (1 hour at 1 Hz); exposes `getHistory()` for graph rendering — `src/main/java/com/mediascanner/monitor/ThroughputHistory.java`
- [X] T060 [US7] Create `dashboard.fxml` and `DashboardController.java`: all panels from `contracts/ui-screens.md` (File Stats, Data Transfer, Throughput, Resource Utilization, Historical Graph); `Timeline` animation at 1 Hz calling `Platform.runLater()` to update all `Label`/`ProgressBar` bindings from `ProgressTracker.snapshot()` — `src/main/resources/fxml/dashboard.fxml`, `src/main/java/com/mediascanner/ui/DashboardController.java`
- [X] T061 [P] [US7] Implement auto-scaling unit formatter `DataUnitFormatter.java`: converts bytes to B/KB/MB/GB/TB per thresholds in `contracts/ui-screens.md`; used by all data volume labels — `src/main/java/com/mediascanner/ui/DataUnitFormatter.java`
- [X] T062 [P] [US7] Unit test `ProgressTrackerTest.java`: rolling average correct after 5 and 30 samples; ETA calculation within ±20% for known input rates; counters thread-safe under concurrent updates (10 threads, 1,000 increments each) — `src/test/java/com/mediascanner/monitor/ProgressTrackerTest.java`
- [X] T063 [P] [US7] Unit test `DataUnitFormatterTest.java`: boundary values at each threshold (999 B, 1 KB, 1023 KB, 1 MB, etc.) — `src/test/java/com/mediascanner/ui/DataUnitFormatterTest.java`

**Checkpoint**: US7 independent test passes. Dashboard updates ≥ 1/sec; ETA decreases; CPU% non-zero.

---

## Phase 10: User Story 8 — End-of-Job Summary Report (Priority: P3)

**Goal**: When a job completes or stops, a comprehensive summary is displayed with all counts, data volumes, peak/avg throughput, and infrastructure stats — and can be exported.

**Independent Test**: Completed job → summary shows all counts, data volumes, peak/avg throughput. (see `quickstart.md` US8 row)

### Implementation for User Story 8

- [X] T064 [US8] Create `summary.fxml` and `SummaryController.java`: all sections from `contracts/ui-screens.md` (Files Summary with sub-counts, Data Summary, Performance Summary, Infrastructure Summary, Execution Summary); bound to `JobStatistics` fields from `JobStatisticsDao.findById(jobId)` — `src/main/resources/fxml/summary.fxml`, `src/main/java/com/mediascanner/ui/SummaryController.java`
- [X] T065 [P] [US8] Implement Export Report in `SummaryController.java`: opens `FileChooser` with `.json` and `.txt` filters; JSON export serializes full `JobStatistics` via Jackson; plain text export formats the same data as human-readable table — `src/main/java/com/mediascanner/ui/SummaryController.java`
- [X] T066 [P] [US8] Add "View Failure Report" button in `SummaryController.java`: opens `/_failures/failure-report.json` using `Desktop.getDesktop().open(file)` — `src/main/java/com/mediascanner/ui/SummaryController.java`
- [X] T067 [P] [US8] Ensure `JobStatisticsDao.markCompleted(jobId, endTime)` captures final peak/avg metrics from `ProgressTracker` before the engine shuts down — `src/main/java/com/mediascanner/db/JobStatisticsDao.java`
- [X] T068 [P] [US8] Unit test `SummaryReportTest.java`: verify all 5 sections populated from `JobStatistics`; JSON export round-trips correctly; plain text export contains all field names — `src/test/java/com/mediascanner/ui/SummaryReportTest.java`

**Checkpoint**: US8 independent test passes. All counts and throughput stats correct in summary; export produces valid file.

---

## Phase 11: User Story 9 — Performance Mode and Worker Configuration (Priority: P3)

**Goal**: Users can enable High-Priority Mode and set a custom thread count; defaults to CPU×2; High-Priority Mode elevates OS scheduling priority.

**Independent Test**: 16-core machine → default thread count = 32; change to 8 → applied; High-Priority Mode request succeeds. (see `quickstart.md` US9 row)

### Implementation for User Story 9

- [X] T069 [US9] Implement High-Priority Mode in `ScanEngine.java`: on start, if `AppConfig.highPriorityMode == true`, call JNA `Kernel32.INSTANCE.SetPriorityClass(GetCurrentProcess(), HIGH_PRIORITY_CLASS)` on Windows or JNA `CLibrary.INSTANCE.setpriority(0, 0, -10)` on macOS; wrap in try/catch — log warning on failure, do not block start — `src/main/java/com/mediascanner/engine/ScanEngine.java`
- [X] T070 [P] [US9] Expose worker thread count and High-Priority Mode toggle in Settings panel (`main.fxml`): numeric input for thread count (min 1, max 512), checkbox for High-Priority Mode — `src/main/resources/fxml/main.fxml`, `src/main/java/com/mediascanner/ui/MainController.java`
- [X] T071 [P] [US9] Implement RAM-aggressive caching in `ScanEngine.java`: pre-load destination folder existence cache (`HashMap<String, Boolean>`) before scanning starts; metadata result cache (`HashMap<String, LocalDateTime>`) for re-encountered paths — `src/main/java/com/mediascanner/engine/ScanEngine.java`
- [X] T072 [P] [US9] Unit test `WorkerPoolTest.java`: default thread count = `Runtime.availableProcessors() * 2`; custom count of 8 → pool has exactly 8 threads; pool shuts down cleanly after stop — `src/test/java/com/mediascanner/engine/WorkerPoolTest.java`

**Checkpoint**: US9 independent test passes. Thread count confirmed via `ThreadMXBean`; High-Priority Mode call succeeds on test platform.

---

## Phase 12: Polish and Cross-Cutting Concerns

**Purpose**: Integration wiring, performance benchmarking, packaging, and acceptance validation.

- [X] T073 [P] End-to-end integration test `FullPipelineIT.java`: source of 1,000 mixed files (valid, empty, small, corrupt, duplicates) → run complete job → verify all bucket counts match expected, folder structure correct, `/_failures/failure-report.json` present, `JOB_STATISTICS` row has COMPLETED status — `src/test/java/com/mediascanner/engine/FullPipelineIT.java`
- [X] T074 [P] Performance benchmark `ThroughputBenchmark.java`: measure files/sec and MB/sec on 10,000 small files (< 1 MB) and 100 large files (> 100 MB); assert ≥ 200 files/sec for small files on dev machine (scaled from BRD baseline) — `src/test/java/com/mediascanner/engine/ThroughputBenchmark.java`
- [X] T075 [P] Pause latency test `PauseLatencyTest.java`: start job → click pause → measure time to full stop → assert ≤ 3,000 ms — `src/test/java/com/mediascanner/engine/PauseLatencyTest.java`
- [X] T076 [P] Startup resume latency test `ResumeLatencyIT.java`: create interrupted job state in DB → simulate app restart → measure time to detect and offer resume → assert ≤ 5,000 ms — `src/test/java/com/mediascanner/checkpoint/ResumeLatencyIT.java`
- [X] T077 [P] Verify checkpoint write latency test `CheckpointLatencyTest.java`: measure time from 1,000th file to checkpoint JSON written → assert ≤ 100 ms — `src/test/java/com/mediascanner/checkpoint/CheckpointLatencyTest.java`
- [X] T078 Add `maven-assembly-plugin` configuration to bundle FFmpeg binaries from `src/main/resources/ffmpeg/<platform>/` for both macOS and Windows builds — `pom.xml`
- [X] T079 [P] Validate all acceptance tests from `quickstart.md` manually: run each US row scenario and confirm pass before marking story Done
- [X] T080 [P] Code review: verify no `Full-table scan` queries in `HashIndexDao.java` and `JobStatisticsDao.java` (Constitution III compliance) — `src/main/java/com/mediascanner/db/`
- [X] T081 [P] Security review: verify no source path or target path is logged at DEBUG level in production `logback.xml` to prevent sensitive path leakage — `src/main/resources/logback.xml`
- [X] T082 Update `quickstart.md` with any build or test commands that changed during implementation — `specs/001-media-scanner-core/quickstart.md`

---

## Dependencies and Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — start immediately
- **Phase 2 (Foundational)**: Depends on Phase 1 completion — **BLOCKS all user stories**
- **Phase 3–11 (User Stories)**: All depend on Phase 2 completion
  - P1 stories (US1, US2) can run in parallel after Phase 2
  - P2 stories (US3–US6) can run in parallel after Phase 2; US5 depends on `HashEngine` from US5 phase
  - P3 stories (US7–US9) can run in parallel after Phase 2; US7 depends on `ProgressTracker`
- **Phase 12 (Polish)**: Depends on all desired user stories complete

### User Story Dependencies

- **US1 (P1)**: Can start after Phase 2 — no story dependencies
- **US2 (P1)**: Can start after Phase 2 — no story dependencies; parallelizable with US1
- **US3 (P2)**: Can start after Phase 2 — no story dependencies
- **US4 (P2)**: Can start after Phase 2 — no story dependencies
- **US5 (P2)**: Can start after Phase 2 — uses `HashIndexDao` from Phase 2
- **US6 (P2)**: Can start after Phase 2 — uses `CheckpointManager` (new in this phase)
- **US7 (P3)**: Can start after Phase 2 — uses `ProgressTracker` (new in this phase)
- **US8 (P3)**: Depends on US7 (`JobStatistics` fully populated)
- **US9 (P3)**: Can start after Phase 2 — independent

### Within Each User Story

- Unit tests SHOULD be written first (TDD per Constitution VIII)
- Models/POJOs before services
- Services before UI wiring
- Engine components before dashboard display

---

## Parallel Execution Examples

### Parallel within Phase 2 (Foundational)

```
T010 Create all domain model POJOs
T011 Create V001 SQL migration script
T012 Create Database.java (depends on T011)
  └─ T013 HashIndexDao.java       [P] ─┐
  └─ T014 JobStatisticsDao.java   [P] ─┤ all run in parallel after T012
  └─ T015 DatabaseIT.java         [P] ─┘
T017 ScanEngine.java (depends on T010, T012)
```

### Parallel within US5 (Duplicate Handling)

```
T042 HashEngine.java
T043 Filename collision in FileTransfer.java  [P]
T044 Duplicate policy routing in ScanEngine   (depends T042)
T045 HashEngineTest.java                      [P]
T046 DuplicatePolicyTest.java                 [P]
T047 FilenameCollisionTest.java               [P]
```

### Parallel across stories (after Phase 2)

```
US1 + US2    Run concurrently (different files)
US3 + US4    Run concurrently (different files)
US5 alone    Shares DB layer with above
US6 alone    New CheckpointManager
US7 + US9    Run concurrently (different files)
US8 alone    Depends on US7
```

---

## Implementation Strategy

### MVP (US1 + US2 only)

1. Complete Phase 1 (Setup)
2. Complete Phase 2 (Foundational — CRITICAL)
3. Complete Phase 3 (US1 — scan, organize, copy/move)
4. Complete Phase 4 (US2 — media classification + ignore rules)
5. **STOP and VALIDATE**: Run US1 + US2 independent tests from `quickstart.md`
6. Demonstrate: copy 100 files → correct folder structure at target

### Incremental Delivery

1. Setup + Foundational → foundation ready
2. US1 + US2 (P1) → basic scan and organize works — **MVP demo point**
3. US3 + US4 (P2) → file validation + metadata extraction
4. US5 + US6 (P2) → duplicate handling + full job control
5. US7 + US8 + US9 (P3) → real-time dashboard + summary + performance mode
6. Phase 12 → Polish, benchmarks, packaging

### Parallel Team Strategy (if staffed)

After Phase 2 completes:
- **Developer A**: US1 + US2 (scanner foundation)
- **Developer B**: US3 + US4 (validation + metadata)
- **Developer C**: US5 (hashing + duplicates)
- **Developer D**: US6 (checkpoint + resume)
- After above: **A+B** → US7+US8 (dashboard); **C** → US9; **D** → Phase 12

---

## Notes

- `[P]` tasks have different files and no incomplete-task dependencies — safe to run concurrently
- `[Story]` label maps each task to its user story for traceability to spec.md
- Each user story phase has an **Independent Test** from `quickstart.md` — validate before advancing
- Constitution VIII requires TDD — write tests first, confirm failing, then implement
- Constitution III compliance: never add a query that causes a full-table scan on `FILE_HASH_INDEX`
- All file I/O in `engine/` must go through the worker thread pool — no blocking calls on JavaFX Application Thread
- `Platform.runLater()` is the ONLY way to update JavaFX UI from worker threads
