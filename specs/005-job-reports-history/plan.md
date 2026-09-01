# Implementation Plan: Job Reports & History

**Branch**: `005-job-reports-history` | **Date**: 2026-09-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/005-job-reports-history/spec.md`

## Summary

Add a reporting and history layer over the existing scan engine. Per-file non-transfer outcomes
(skipped, failed, duplicate) and 1 Hz throughput samples are written to SQLite as a job runs; at terminal
state three JSON reports are streamed out of SQLite into the target archive; a new Job History screen
reads `JOB_STATISTICS` back so any past job's summary and throughput chart can be reopened and exported.
The same `V002` migration also splits the hash index so duplicate paths cache their hash instead of being
re-read every run.

No change to file selection, validation, organisation, or transfer. This feature only observes and
reports on work the engine already performs.

## Technical Context

**Language/Version**: Java 21 LTS — matches the existing module; no new language features required.

**Primary Dependencies**: All already present in `pom.xml`.
- Jackson (`jackson-databind`, `jackson-datatype-jsr310`) — JSON report writing, via streaming
  `JsonGenerator` rather than `ObjectMapper.writeValue` on a materialised list
- `sqlite-jdbc` — the new `JOB_EVENT` and `JOB_THROUGHPUT_SAMPLE` tables
- JavaFX 21 `LineChart` (`javafx.scene.chart`) — throughput visualisation; no charting library needed
- JavaFX `TableView` — the Job History list

**Storage**: SQLite, schema `V002`. Three changes: add `JOB_EVENT`, add `JOB_THROUGHPUT_SAMPLE`, and split
the duplicate gate out of `FILE_HASH_INDEX` into `HASH_CANONICAL`. See [data-model.md](data-model.md).

**Testing**: JUnit 5 + AssertJ, matching the existing suite. Unit tests for the report writers and the
event DAO; integration tests (`*IT`) for the full record-then-report round trip against a temp database;
a scale test asserting constant memory over a 100 000-event report.

**Target Platform**: Windows 10+, macOS — unchanged.

**Project Type**: Feature addition to the existing JavaFX desktop application.

**Performance Goals**: Recording an event must not measurably slow the scan (batched writes, no
per-file synchronous commit). Report generation for 100 000 entries under 10 s with flat memory
(SC-003). Job History renders 200 rows in under 2 s (SC-004). Chart renders 8 h of samples in under
2 s (US5 AS-4).

**Constraints**: Reports are derived artefacts written at terminal state, not a live append log — the
authoritative record is SQLite (Principle III). Report size is capped with an explicit in-file truncation
notice. No new third-party dependency is introduced.

**Scale/Scope**: Two new tables, one migration, one new DAO, three report writers, one new screen with
controller and FXML, one chart component, three export formats.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked post-design.*

| Gate | Status | Notes |
|------|--------|-------|
| Runtime: Java 21 LTS | ✅ PASS | Unchanged |
| Desktop UI: JavaFX 21 | ✅ PASS | `TableView` + `LineChart`, both in JavaFX core |
| Build: Maven | ✅ PASS | No new dependencies |
| Packaging: jpackage | ✅ PASS | Unchanged |
| Target OS: Windows 10+, macOS | ✅ PASS | Unchanged |
| I. Performance-First Architecture | ✅ PASS | Batched event writes off the worker hot path; reports stream; charts downsample. Also *improves* FR-025 by fixing the duplicate re-hash defect. |
| II. Context Preservation | ✅ PASS | Events are committed as the job runs, so a crash preserves everything up to that point |
| III. SQLite as single source of truth | ✅ PASS | SQLite is authoritative; JSON reports are explicitly derived exports |
| IV. Observability | ✅ PASS | Directly implements the unmet half of FR-031 and the end-of-job summary persistence |
| V. Duplicate Handling | ✅ PASS | Delivers the FR-023 report; `HASH_CANONICAL` preserves the atomic dedup gate |
| VI. Media Validation Gates | ✅ PASS | Delivers the FR-019 and FR-020 buckets |
| VII. Folder Organization | ✅ N/A | No change to organisation or transfer |
| VIII. Development Discipline | ✅ PASS | Five user stories, each with an independent test defined in the spec |

**Result**: No violations. Proceed.

### Post-design re-check

The `HASH_CANONICAL` split touches the hash index, which Principle III governs and the Compliance Review
section requires a Constitution Check for. The design preserves the invariant that made the current code
correct — a `UNIQUE` constraint serialising concurrent duplicate decisions — by moving it to
`HASH_CANONICAL.SHA256_HASH` rather than removing it. `FILE_HASH_INDEX` gains `UNIQUE(FILE_PATH)`, which
it should always have had. Migration is additive and backfillable. ✅ PASS.

## Project Structure

### Documentation (this feature)

```text
specs/005-job-reports-history/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 — design decisions
├── data-model.md        # Phase 1 — schema and entities
├── quickstart.md        # Phase 1 — developer guide
├── tasks.md             # Phase 2 output (/speckit-tasks)
└── checklists/
    └── requirements.md  # Specification quality checklist
```

### Source Code (repository root)

```text
src/main/java/com/mediascanner/
├── db/
│   ├── Database.java                  # Modified: register V002 in MIGRATIONS
│   ├── JobEventDao.java               # New: batched insert + streaming read of JOB_EVENT
│   ├── ThroughputSampleDao.java       # New: sample insert + downsampled read
│   ├── JobStatisticsDao.java          # Modified: findAll(), deleteJob()
│   └── HashIndexDao.java              # Modified: HASH_CANONICAL claim/lookup
├── model/
│   ├── JobEvent.java                  # New: replaces the unused SkippedRecord/FailureRecord pair
│   └── ThroughputSample.java          # New
├── report/                            # New package
│   ├── JobEventRecorder.java          # Batching buffer between engine and JobEventDao
│   ├── ReportWriter.java              # Streaming JSON writer shared by all three reports
│   ├── JobReportService.java          # Orchestrates the three reports at terminal state
│   └── SummaryExporter.java           # JSON / CSV / HTML export of a job summary
├── engine/
│   ├── ScanEngine.java                # Modified: emit events, drive the recorder, sample throughput
│   └── FileTransfer.java              # Modified: remove appendFailureRecord (superseded)
├── monitor/
│   ├── ThroughputHistory.java         # Modified: ArrayDeque ring buffer; now actually consumed
│   └── ResourceMonitor.java           # Modified: expose samples to the recorder
└── ui/
    ├── JobHistoryController.java      # New
    ├── ThroughputChart.java           # New: reusable LineChart component
    ├── SummaryController.java         # Modified: load stored job, add Export menu
    ├── DashboardController.java       # Modified: embed live chart
    └── MenuBarController.java         # Modified: View → Job History, Tools → Export Summary

src/main/resources/
├── db/migrations/V002__job_reports.sql   # New
└── fxml/job-history.fxml                 # New

src/test/java/com/mediascanner/
├── db/JobEventDaoIT.java              # New
├── report/ReportWriterTest.java       # New
├── report/JobReportServiceIT.java     # New
├── report/SummaryExporterTest.java    # New
└── report/ReportScaleIT.java          # New: 100k entries, constant memory
```

## Phase 0: Research

See [research.md](research.md) — all design decisions resolved. The three that shape the code most:

1. **Record to SQLite, derive JSON at the end** — not per-file JSON appends. The existing
   `FileTransfer.appendFailureRecord` reads and rewrites the whole array per failure (O(n²)) and is not
   thread-safe; it is removed rather than called.
2. **Batch event writes** — a bounded buffer flushed every 500 events or 5 s, off the worker hot path, so
   recording does not add a synchronous DB round trip per file.
3. **Split the hash index** — `HASH_CANONICAL` takes the `UNIQUE(SHA256_HASH)` dedup gate;
   `FILE_HASH_INDEX` becomes a pure per-path cache. Fixes FR-005-014 / SC-007 and unblocks real FR-025
   tiered hashing by making room for a `PARTIAL_HASH` column.

## Phase 1: Design

### Recording path

```
ScanEngine worker ──emit──▶ JobEventRecorder (bounded buffer)
                                   │ flush every 500 events / 5 s
                                   ▼
                              JobEventDao ──▶ JOB_EVENT (SQLite)

ResourceMonitor (1 Hz) ──▶ ThroughputHistory ──▶ ThroughputSampleDao ──▶ JOB_THROUGHPUT_SAMPLE
```

Workers call `recorder.record(...)` — an enqueue onto a concurrent buffer, never a DB call. The recorder
owns a single-threaded flusher, so writes to `JOB_EVENT` are serialised on one connection and never
contend with the hash-index writers.

### Reporting path

```
job reaches terminal state
        │
        ▼
JobReportService.writeAll(jobId, targetRoot)
        │
        ├─▶ ReportWriter.stream(SKIPPED)   ──▶ <target>/_skipped/skipped-report-<jobId>.json
        ├─▶ ReportWriter.stream(FAILED)    ──▶ <target>/_failures/failure-report-<jobId>.json
        └─▶ ReportWriter.stream(DUPLICATE) ──▶ <target>/_duplicates/duplicate-report-<jobId>.json
```

Each writer opens a Jackson `JsonGenerator`, streams a `ResultSet` cursor straight to the file, and never
materialises the rows. A bucket directory and its report are created only when that outcome has at least
one row (US1 AS-3, US2 AS-5). The most recent report is additionally copied to the plain name
(`failure-report.json`) for discoverability, satisfying FR-019's literal filename while FR-005-006 keeps
per-job copies.

### History and export path

`JobHistoryController` reads `JobStatisticsDao.findAll()` into a `TableView`. Selecting a row opens
`SummaryController` in stored-job mode, which populates from `JOB_STATISTICS` plus a downsampled
`JOB_THROUGHPUT_SAMPLE` series instead of live engine state — this is the one structural change to
`SummaryController`, which currently only knows how to read a running engine.

`SummaryExporter` takes the same populated summary object and writes JSON, CSV, or a self-contained HTML
page with inline CSS and an inline SVG chart (no external assets, per US4 AS-3).

### Migration strategy

`V002__job_reports.sql` is additive and safe to apply to an existing database:

1. `CREATE TABLE JOB_EVENT` + indexes on `(JOB_ID, OUTCOME)`.
2. `CREATE TABLE JOB_THROUGHPUT_SAMPLE` + index on `(JOB_ID, SAMPLE_AT)`.
3. `CREATE TABLE HASH_CANONICAL` and backfill it from the existing `FILE_HASH_INDEX` rows.
4. Rebuild `FILE_HASH_INDEX` without `UNIQUE(SHA256_HASH)`, with `UNIQUE(FILE_PATH)` and a new nullable
   `PARTIAL_HASH` column.
5. `PRAGMA user_version = 2`.

Step 4 requires the SQLite table-rebuild dance (create new, copy, drop, rename) since SQLite cannot drop
an index-backed constraint in place. `Database.MIGRATIONS` must gain the `V002` entry — the list is
explicit by design (see the audit's M7).

### Backward compatibility

- `SkippedRecord` and `FailureRecord` are superseded by `JobEvent`. Both are currently written by nothing
  and read by nothing, so they are deleted rather than migrated.
- `FileTransfer.appendFailureRecord` is removed; it is dead code today and its approach does not scale.
- Existing hash index rows survive the rebuild with their cached hashes intact, so the first run after
  upgrade does not re-hash the archive.

## Artifacts Generated

- [research.md](research.md) — design decisions and rejected alternatives
- [data-model.md](data-model.md) — `V002` schema and entity definitions
- [quickstart.md](quickstart.md) — how to add a new report type or export format
- [checklists/requirements.md](checklists/requirements.md) — specification quality gate
