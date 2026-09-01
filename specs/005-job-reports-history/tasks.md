# Tasks: Job Reports & History

**Input**: Design documents from `specs/005-job-reports-history/`

**Prerequisites**: spec.md ✅ | plan.md ✅ | research.md ✅ | data-model.md ✅ | quickstart.md ✅

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (no dependency on incomplete tasks)
- **[Story]**: User story this task belongs to (US1–US5)

---

## Phase 1: Setup

**Purpose**: Land the schema and the model types every later phase reads and writes.

- [x] T001 Create `src/main/resources/db/migrations/V002__job_reports.sql` implementing steps 1–6 from `specs/005-job-reports-history/data-model.md`: `JOB_EVENT` + two indexes, `JOB_THROUGHPUT_SAMPLE` + index, `HASH_CANONICAL`, backfill from `FILE_HASH_INDEX`, the `FILE_HASH_INDEX` rebuild adding `UNIQUE(FILE_PATH)` and nullable `PARTIAL_HASH`, and `PRAGMA user_version = 2`. Keep every statement top-level — `Database.splitStatements` splits on `;`
- [x] T002 Add `"V002__job_reports.sql"` to the `MIGRATIONS` constant in `src/main/java/com/mediascanner/db/Database.java`
- [x] T003 [P] Create `src/main/java/com/mediascanner/model/JobEvent.java` with fields id, jobId, outcome (enum `SKIPPED`/`FAILED`/`DUPLICATE`), filePath, fileName, fileSize, reason, sha256Hash, matchedPath, destinationPath, recordedAt
- [x] T004 [P] Create `src/main/java/com/mediascanner/model/ThroughputSample.java` with fields jobId, sampleAt, elapsedSeconds, filesPerSec, mbPerSec, cpuPercent, memoryGb
- [x] T005 [P] Add a nullable `partialHash` field with accessors to `src/main/java/com/mediascanner/model/FileHashRecord.java`
- [x] T006 Delete `src/main/java/com/mediascanner/model/SkippedRecord.java` and `src/main/java/com/mediascanner/model/FailureRecord.java`, and remove `appendFailureRecord` plus its now-unused Jackson and `FailureRecord` imports from `src/main/java/com/mediascanner/engine/FileTransfer.java` (research D6 — all three are currently dead code)

**Checkpoint**: Schema applies cleanly to both a fresh and an existing database; project compiles.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The persistence layer every user story depends on. Nothing below runs until these exist.

**⚠️ CRITICAL**: T007–T011 block all five user stories.

- [x] T007 Create `src/main/java/com/mediascanner/db/JobEventDao.java` with `insertBatch(List<JobEvent>)` in one transaction, `streamByOutcome(jobId, outcome, limit, Consumer<JobEvent>)` backed by a forward-only `ResultSet` that never materialises a list, `countByOutcome(jobId, outcome)`, `sumBytesByOutcome(jobId, outcome)`, and `deleteByJobId(jobId)`
- [x] T008 [P] Create `src/main/java/com/mediascanner/db/ThroughputSampleDao.java` with `insertBatch(List<ThroughputSample>)`, `findDownsampled(jobId, targetPoints)` bucketing on `ELAPSED_SECONDS` and averaging in SQL, and `deleteByJobId(jobId)`
- [x] T009 Add `claimCanonical(sha256, path)` returning true when this path claimed the hash (`INSERT OR IGNORE`, one affected row) and `findCanonicalPath(sha256)` to `src/main/java/com/mediascanner/db/HashIndexDao.java`; update `insert`/`updateRecord`/`mapRow` for the `PARTIAL_HASH` column
- [x] T010 [P] Add `findAll()` returning all jobs newest first and `deleteJob(jobId)` to `src/main/java/com/mediascanner/db/JobStatisticsDao.java`
- [x] T011 Create `src/main/java/com/mediascanner/report/JobEventRecorder.java`: a bounded `ArrayBlockingQueue` of `JobEvent`, a single daemon flusher thread draining to `JobEventDao.insertBatch` every 500 events or 5 s, `record(JobEvent)` that blocks rather than drops when the buffer is full (research D2), and `close()` that flushes the remainder
- [x] T012 [P] Create `src/test/java/com/mediascanner/db/JobEventDaoIT.java` covering batch insert, streaming read by outcome, counts, byte sums, and cascade delete against a temp database

**Checkpoint**: Events and samples can be written and read back. User stories can start in parallel.

---

## Phase 3: User Story 1 — Failure and Skipped Reports on Disk (Priority: P1) 🎯 MVP

**Goal**: Every job that skips or fails a file leaves `_skipped/skipped-report.json` and
`_failures/failure-report.json` in the target archive, each naming every affected file and why.

**Independent Test**: Source set of one 0-byte `.jpg`, one 5 KB `.jpg`, one text file named `.mp4`
**larger than the 100 KB video threshold**, one `Thumbs.db`, and ten valid media files. Run a Copy job.
Ten files land in the archive; the skipped report has entries with reasons `EMPTY_FILE`, `SMALL_FILE`,
`IGNORE_RULE_MATCHED`; the failure report has exactly one entry naming the unreadable `.mp4`.
Implemented as `ScanReportsEndToEndIT`. The bogus `.mp4` must clear the size threshold: the small-file
gate runs before the corrupt-media gate, so a short invalid file is reported as `SMALL_FILE`.

### Implementation for User Story 1

- [x] T013 [US1] Create `src/main/java/com/mediascanner/report/ReportWriter.java`: opens a Jackson `JsonGenerator`, writes the envelope from `data-model.md` (jobId, outcome, generatedAt, sourcePath, targetPath, totalCount, truncated), streams entries straight from `JobEventDao.streamByOutcome` without materialising them, and stops at the configured cap emitting `"truncated": true` with the true `totalCount` (research D7)
- [x] T014 [US1] Create `src/main/java/com/mediascanner/report/JobReportService.java` with `writeAll(jobId, targetRoot)`: for each outcome, skip entirely when `countByOutcome` is 0 (US1 AS-3), otherwise create the bucket directory, write `<bucket>/<name>-<jobId>.json`, then copy it to the plain `<name>.json` (research D4)
- [x] T015 [US1] Emit `JobEvent`s from `src/main/java/com/mediascanner/engine/ScanEngine.java`: in `validateAndRecord` record `SKIPPED` with the `SkipReason` name and `FAILED` with the failure message; in `extractAndRecord` record `SKIPPED` with `METADATA_MISSING`. Construct the recorder in `start()` and close it in the terminal block
- [x] T016 [US1] Record files rejected by an ignore rule as `SKIPPED` with reason `IGNORE_RULE_MATCHED` — these are filtered inside `FileScanner.walkFileTree` today and never reach a worker, so surface them via a callback on `FileScanner` rather than by moving the filter (spec US1 independent test requires this reason to appear)
- [x] T017 [US1] Call `JobReportService.writeAll` from the terminal block of `ScanEngine.start()` for both `COMPLETED` and `STOPPED` (US1 AS-4, research D8), logging and surfacing a failure without discarding the SQLite record
- [x] T018 [P] [US1] Create `src/test/java/com/mediascanner/report/ReportWriterTest.java` covering envelope correctness, entry shape, path escaping for Windows backslashes, non-ASCII names and embedded quotes (FR-005-013), and the truncation notice
- [x] T019 [P] [US1] Create `src/test/java/com/mediascanner/report/JobReportServiceIT.java` covering the full record-then-report round trip, the no-empty-report rule (AS-3), and per-job filenames not overwriting each other (AS-6)
- [x] T020 [US1] Create `src/test/java/com/mediascanner/report/ReportScaleIT.java`: 100 000 events, assert the report is written in under 10 s and that peak memory does not scale with entry count (AS-5, SC-003)

**Checkpoint**: US1 is independently shippable — FR-019 and FR-020 are closed.

---

## Phase 4: User Story 2 — Duplicate Report (Priority: P1)

**Goal**: Every deduplicated file is auditable — path, size, SHA-256, and the canonical file it matched.

**Independent Test**: Three byte-identical copies of one photo under different names in different
subfolders, plus five unique photos. Copy job, policy Skip. Six files land; the duplicate report lists
exactly two entries sharing one SHA-256, each naming the canonical retained path, with `totalBytesSaved`
equal to twice the photo's size.

### Implementation for User Story 2

- [x] T021 [US2] Replace the check-then-act duplicate decision in `ScanEngine.processFile` with a single `hashIndexDao.claimCanonical(hash, path)` call: a false return means duplicate, and `findCanonicalPath` supplies `MATCHED_PATH` (research D3). This removes the `findBySha256`-then-compare-paths pair
- [x] T022 [US2] Record a `DUPLICATE` `JobEvent` in `ScanEngine.handleDuplicate` carrying sha256Hash, matchedPath, and — for the Move-to-bucket and Keep-Both policies — the `destinationPath` actually written (US2 AS-3, AS-4)
- [x] T023 [US2] Extend `ReportWriter` to emit the duplicate envelope's extra fields and the top-level `totalBytesSaved` from `JobEventDao.sumBytesByOutcome` (US2 AS-2)
- [x] T024 [US2] Update `HashEngine.persistHash` to always cache the per-path hash now that `UNIQUE(SHA256_HASH)` is gone, and to populate `PARTIAL_HASH`; delete the constraint-violation catch and its explanatory comment, which no longer apply (FR-005-014)
- [x] T025 [P] [US2] Extend `src/test/java/com/mediascanner/engine/DuplicatePolicyTest.java` to assert a duplicate event is recorded for each of the three policies with the correct destination path
- [x] T026 [P] [US2] Add a test asserting a second run over an unchanged source performs zero full-file re-reads for duplicate paths (SC-007) — assert on `FILE_HASH_INDEX` cache hits rather than on wall-clock time
- [x] T027 [US2] Add a concurrency test: many worker threads claiming the same hash simultaneously yield exactly one canonical and N-1 duplicates

**Checkpoint**: US2 is independently shippable — FR-023 is closed and SC-007 is met.

---

## Phase 5: User Story 3 — Job History Screen (Priority: P2)

**Goal**: Every job the application has run is browsable after restart, and any one can be reopened.

**Independent Test**: Run three jobs with different sources, restart the app, open View → Job History,
verify all three appear newest first with correct dates and counts, and that selecting the second opens
the summary that job finished with.

### Implementation for User Story 3

- [x] T028 [US3] Create `src/main/resources/fxml/job-history.fxml` with a `TableView` (date, source, target, status, files processed, files skipped, files failed, duplicates) and Open, Delete, and Close buttons
- [x] T029 [US3] Create `src/main/java/com/mediascanner/ui/JobHistoryController.java` loading `JobStatisticsDao.findAll()` newest first, opening the selected job's summary, and deleting a job after confirmation via `JobStatisticsDao.deleteJob` + `JobEventDao.deleteByJobId` + `ThroughputSampleDao.deleteByJobId`, refusing deletion while that job is running (FR-005-012, AS-6)
- [x] T030 [US3] Add stored-job mode to `src/main/java/com/mediascanner/ui/SummaryController.java`: a `loadStoredJob(jobId)` path populating every field from `JOB_STATISTICS` instead of live engine state, so the same view serves both a just-finished and a historical job
- [x] T031 [US3] Add View → Job History to `src/main/java/com/mediascanner/ui/MenuBarController.java` and register the screen with `ScreenNavigator`
- [x] T032 [P] [US3] Add a test asserting the history list is ordered newest first and that deleting a job removes its statistics, events, and samples while leaving archive files untouched

**Checkpoint**: US3 is independently shippable — job history survives restart.

---

## Phase 6: User Story 4 — Export Job Summary (Priority: P2)

**Goal**: Any job summary can be exported as JSON, CSV, or self-contained HTML.

**Independent Test**: Complete a job, export in all three formats, and verify each contains the same
figures shown on screen — file counts, byte totals, peak/average throughput, CPU and memory, start/end
time, duration, folders created.

### Implementation for User Story 4

- [x] T033 [US4] Create `src/main/java/com/mediascanner/report/SummaryExporter.java` with `toJson`, `toCsv` (header row plus one value row) and `toHtml` (self-contained, inline CSS, no external assets), covering every field the constitution's end-of-job summary requires
- [x] T034 [US4] Add an Export control to `SummaryController` offering the three formats via a `FileChooser`, showing a clear error and staying usable when the target is not writable (US4 AS-4)
- [ ] T035 [P] [US4] Add Tools → Export Summary… to `MenuBarController`, enabled only when a summary is loaded — **not done**: the three Export buttons live on the summary screen itself, which is where a user is when they have a summary to export. A Tools entry would need to be disabled most of the time; revisit if users ask for it
- [x] T036 [P] [US4] Create `src/test/java/com/mediascanner/report/SummaryExporterTest.java` asserting all three formats carry identical figures, that CSV escapes embedded commas, quotes and newlines in paths, and that the HTML references no external URL

**Checkpoint**: US4 is independently shippable.

---

## Phase 7: User Story 5 — Historical Throughput Chart (Priority: P3)

**Goal**: Throughput, CPU and memory over the life of any job — live and historical — are visible and
comparable. Closes FR-031.

**Independent Test**: Run a job over a mix of many small files and a few very large videos. The chart
shows a high files/sec region and a high MB/sec region that do not coincide. Restart, reopen the job from
history, chart is unchanged.

### Implementation for User Story 5

- [x] T037 [US5] Replace the `ArrayList` + `remove(0)` ring in `src/main/java/com/mediascanner/monitor/ThroughputHistory.java` with an `ArrayDeque`, removing the O(n) shift per sample (audit M8)
- [x] T038 [US5] Feed `ThroughputHistory` from the `ResourceMonitor` 1 Hz scheduler combined with `ProgressTracker.snapshot()`, and persist each sample through `JobEventRecorder`'s flusher into `JOB_THROUGHPUT_SAMPLE` (FR-005-007). This is the first code to consume `ThroughputHistory`, which is dead today
- [x] T039 [US5] Create `src/main/java/com/mediascanner/ui/ThroughputChart.java`, a reusable JavaFX `LineChart` component plotting files/sec and MB/sec against elapsed seconds, with an explanatory placeholder when a job has fewer than 5 samples (AS-5)
- [x] T040 [US5] Embed the live chart in `src/main/java/com/mediascanner/ui/DashboardController.java`, updating at least once per second (AS-1)
- [x] T041 [US5] Render the stored chart in `SummaryController` from `ThroughputSampleDao.findDownsampled(jobId, 600)` (AS-2, AS-3)
- [x] T042 [US5] Add an inline-SVG chart to `SummaryExporter.toHtml` so the exported page carries the throughput profile with no external assets
- [x] T043 [P] [US5] Add a test asserting an 8-hour sample set (28 800 rows) downsamples to ~600 points and that `findDownsampled` returns in under 2 s (AS-4)

**Checkpoint**: All five user stories complete. FR-031 closed.

---

## Phase 8: Polish

- [x] T044 [P] Update `docs/INSTALL.md` and `src/main/resources/docs/user-guide.html` to document the three report files, their locations, and the Job History screen
- [x] T045 [P] Add Job History and Export Summary to the keyboard shortcuts list in `src/main/resources/fxml/shortcuts.fxml`
- [x] T046 Run the full suite (`mvn verify`) on a machine with Maven and confirm all pre-existing tests still pass alongside the new ones
- [ ] T047 Run the acceptance protocol in `quickstart.md` end to end against a real archive of at least 50 000 files, confirming SC-001 through SC-007 — **partially done**: SC-001/002/003/005/006/007 are covered by automated tests, and the GUI has now been driven by hand (Job History, row selection, stored-job summary, throughput charts — two UI defects found and fixed). **Still outstanding**: a run against a real 50 000+ file archive, and the export file dialogs
- [x] T048 Update `.specify/memory/tracker.md` with the session log entry and the next action per Constitution § Development Tracker Standards

---

## Dependencies

```
Phase 1 (T001–T006)  ──▶ Phase 2 (T007–T012)  ──┬──▶ Phase 3 US1 (T013–T020)
                                                 ├──▶ Phase 4 US2 (T021–T027)
                                                 ├──▶ Phase 5 US3 (T028–T032)
                                                 ├──▶ Phase 6 US4 (T033–T036) ── needs T030
                                                 └──▶ Phase 7 US5 (T037–T043)
                                                              │
                                                    Phase 8 (T044–T048)
```

- **T001 → T002** — the migration file must exist before it is registered.
- **T007, T011 → T013–T017** — the report writers need the DAO and the recorder.
- **T009 → T021** — the canonical claim must exist before the duplicate decision is rewritten.
- **T030 → T033, T041** — export and the stored chart both need stored-job mode.
- US1 and US2 both modify `ScanEngine.processFile`; sequence them or expect a merge conflict.
- Everything else marked **[P]** is genuinely independent.

## Implementation Strategy

**MVP is Phase 1 + Phase 2 + Phase 3.** That alone closes FR-019 and FR-020, the two most clearly unmet
constitutional requirements, and is independently shippable. Phase 4 closes FR-023 and delivers the SC-007
performance win. Phases 5–7 are additive UI value and can follow in any order.
