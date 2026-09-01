# MediaScanner — Development Tracker

> **How to use**: Update this file at the start and end of every session.
> Run `/speckit-agent-context-update` after updating to sync into CLAUDE.md.
> See Constitution § Development Tracker Standards for the full update protocol.
>
> **Rebuild trigger**: If this file is stale (> 7 days without update or blockers
> unreviewed for > 3 sessions), run a full tracker rebuild per Constitution § Tracker
> Rebuild Trigger before continuing.

---

## Project Status

**Phase**: Features 001–005 and 007 implemented, tested, and verified at scale.
**Overall Completion**: ~92% of the full BRD. FR-019, FR-020, FR-023, FR-031 and true resume
(FR-017/FR-022) are all closed. Feature 006 (Cleanup Tool) is being spec'd in a separate session.
**Constitution Version**: 1.1.0

---

## Active Feature

| Field | Value |
|-------|-------|
| Branch | `main` (feature branch `005-job-reports-history` not cut — work landed directly) |
| Spec | `specs/005-job-reports-history/spec.md` ✅ |
| Plan | `specs/005-job-reports-history/plan.md` ✅ |
| Tasks | `specs/005-job-reports-history/tasks.md` — 46 of 48 done; T035 declined with rationale, T047 (manual acceptance) outstanding |

**Build status**: `./mvnw clean verify` — **192 tests, 0 failures** (124 unit, 68 integration), green in CI on ubuntu, macOS and Windows.

---

## Phase Progress

### Project Setup ✅ COMPLETE
- [x] Install spec-kit v0.9.2
- [x] Initialize project with Claude Code integration
- [x] Ratify constitution v1.0.0
- [x] Create development tracker v1
- [x] Add BRD documents to `/brd` folder
- [x] Capture FR-001–FR-022 from MediaScanner BRD.docx
- [x] Capture FR-023–FR-031 from Functional Requirements Document.docx
- [x] Amend constitution to v1.1.0 (full BRD ingestion, 8 principles, FR traceability matrix)
- [x] Rebuild development tracker (Session 2, rebuilt again Session 4)

### Feature 001: Core Scanner Engine ✅ IMPLEMENTED (commit `d8aeaa4`)
- [x] Spec, clarify, plan, tasks, implementation — all 82 tasks
- [x] 48 source files (29 main + 19 test)
- [ ] Manual acceptance test run (US1–US9 from quickstart.md) — **still outstanding**
- [ ] Performance benchmarking (Gate G4) — **still outstanding**, needs Maven

### Feature 002: Application Menu Bar ✅ IMPLEMENTED (commit `c18286d`)
- [x] File / Edit / Job / View / Tools / Help menus, preferences dialog, dark mode, about, shortcuts

### Feature 003: Installable Builds ⚠️ IMPLEMENTED WITHOUT ITS OWN COMMIT
- [x] `package-mac` and `package-win` jpackage profiles exist in `pom.xml`
- [x] Icons at `src/packaging/{macos,windows}/`
- ⚠️ No `003` commit in the log — the profiles arrived inside the 004 commit. History is misleading;
  recorded here so nobody goes looking for it.

### Feature 004: GitHub Actions Release ✅ IMPLEMENTED (commit `496420c`, PR #1)
- [x] `.github/workflows/release.yml` — tag-triggered, parallel mac/win builds, checksums, gh release
- [ ] T010–T014 acceptance tasks (push a real tag, install on clean machines) — **still outstanding**

### Engineering Audit ✅ COMPLETE (Session 4)
- [x] All 36 main sources reviewed against Constitution v1.1.0 — see `docs/ENGINEERING-AUDIT.md`
- [x] 25 findings: 4 critical, 7 high, 9 medium, 5 process
- [x] 11 fixed in-session; 6 routed to feature 005; 8 left open and documented

### Feature 005: Job Reports & History ✅ IMPLEMENTED
- [x] Spec (5 user stories, FR-019/020/023/031 mapped — Gate G6), plan, research, data-model, quickstart, checklist, 48 tasks
- [x] Phase 1 — `V002__job_reports.sql`, `JobEvent`, `ThroughputSample`, `partialHash`; deleted the dead `SkippedRecord` / `FailureRecord` / `appendFailureRecord`
- [x] Phase 2 — `JobEventDao`, `ThroughputSampleDao`, `claimCanonical`/`findCanonicalPath`, `findAll`/`deleteJob`, `JobEventRecorder`
- [x] Phase 3 (US1) — `ReportWriter`, `JobReportService`, engine event emission, `FileScanner` skip listener. **FR-019 and FR-020 closed**
- [x] Phase 4 (US2) — atomic `HASH_CANONICAL` claim replaces check-then-act; duplicate events with hash + matched path. **FR-023 closed, SC-007 met**
- [x] Phase 5 (US3) — `JobHistoryController` + `job-history.fxml`, stored-job mode on `SummaryController`, View → Job History (⌘4)
- [x] Phase 6 (US4) — `SummaryExporter` (JSON/CSV/HTML, one shared field map so formats cannot drift)
- [x] Phase 7 (US5) — `ThroughputHistory` on `ArrayDeque` and finally *consumed*; 1 Hz sampler in the engine; `ThroughputChart` live and stored; inline-SVG chart in HTML export. **FR-031 closed**
- [x] Phase 8 — docs, shortcuts screen, `mvn verify` green
- [ ] T047 — manual GUI acceptance pass and a run against a real 50 000+ file archive
- [ ] T035 — declined: the three Export buttons live on the summary screen where the user already is; a Tools entry would be disabled most of the time

**Tests added**: `JobEventDaoIT`, `ThroughputSampleDaoIT`, `MigrationV002IT`, `HashCanonicalConcurrencyIT`,
`ReportWriterTest`, `SummaryExporterTest`, `JobReportServiceIT`, `ReportScaleIT`, `ScanReportsEndToEndIT`,
`FxmlLoadIT`.

---

## Blockers

| # | Description | Owner | Status | Target Resolution |
|---|-------------|-------|--------|-------------------|
| B001 | FR-001–022 missing from provided docx | Suraj | ✅ RESOLVED — captured from MediaScanner BRD.docx | 2026-06-03 |
| B002 | `/brd` folder was empty | Suraj | ✅ RESOLVED — BRD.docx + FRD.docx present | 2026-06-03 |
| B003 | Maven not installed; full test suite had never been run | Suraj | 🟢 RESOLVED — Maven 3.9.9 installed, `mvnw`/`mvnw.cmd`/`.mvn/wrapper` committed, and `./mvnw clean verify` passes (187 tests) | — |
| B004 | No CI on push/PR — `release.yml` only fired on `v*.*.*` tags | Suraj | 🟢 RESOLVED — `.github/workflows/build.yml` runs on every branch push and PR. **Verified green on ubuntu, macOS and Windows** (run 33519273821). First run failed because `mvnw` was committed 100644; fixed with `git update-index --chmod=+x` | — |
| B005 | Resume was cosmetic — a "resumed" job re-copied everything already transferred | Suraj | 🟢 RESOLVED — feature 007. Verified at 52 552-file scale: a re-run copies zero bytes and the archive does not grow | — |
| B006 | The 4 `*IT` classes had never run — Surefire without Failsafe, and Surefire defaults do not match `*IT.java` | Suraj | 🟢 RESOLVED — `maven-failsafe-plugin` added; all four passed on first execution | — |
| B007 | Nobody had driven the GUI or run at scale | Suraj | 🟢 RESOLVED — GUI driven through Job History → Open Summary → charts (two UI defects found and fixed), and a 52 552-file acceptance run completed. **Remaining gaps are narrow**: the export file dialogs and the new resume dialog have not been clicked by hand | — |

**No blockers are open.** The narrow remaining gaps are recorded against B007.

---

## Context Snapshot

### Key Decisions

| Decision | Value | Source |
|----------|-------|--------|
| Runtime | Java 21 LTS | BRD Technology Stack |
| Desktop UI | JavaFX 21 + ControlsFX + MaterialFX | BRD Technology Stack |
| Build | Maven | BRD Technology Stack |
| Packaging | jpackage | BRD Technology Stack |
| Metadata | Apache Tika + Metadata Extractor | BRD Technology Stack |
| Video metadata | FFmpeg + FFprobe | BRD Technology Stack |
| JSON | Jackson | BRD Technology Stack |
| Database | SQLite (JDBC) | BRD Technology Stack |
| Logging | SLF4J + Logback | BRD Technology Stack |
| Target OS | Windows 10/11, macOS | BRD Technology Stack |
| Default thread count | CPU cores × 2 (user-configurable) | NFR-003 |
| Default folder structure | /yyyy/MMM | FR-008 |
| Duplicate policy default | SKIP (never destructive) | FR-023, Constitution V |
| Checkpoint cadence | Every 1 000 files OR 60 s | NFR-006 |
| Checkpoint SLA | < 100 ms SQLite persist | Constitution II |
| Resume SLA | < 5 s detection | FR-022 |
| Performance baseline | 16-core / 64 GB / NVMe SSD | FRD Enterprise Targets |
| Phase 2 features | Deferred (GPS, AI, Cloud, Watch Folder) | BRD Future Enhancements |

### Key File Paths

| File | Purpose |
|------|---------|
| `.specify/memory/constitution.md` | Project governance v1.1.0 — 8 principles, FR matrix |
| `.specify/memory/tracker.md` | This file — session log and progress |
| `brd/MediaScanner BRD.docx` | FR-001–022, NFR-001–006, Tech Stack, Reporting |
| `brd/Functional Requirements Document.docx` | FR-023–031, SQLite schema, Performance targets |

### SQLite Schema (locked)

**FILE_HASH_INDEX**: ID (BIGINT), FILE_PATH (TEXT), FILE_NAME (TEXT), FILE_SIZE (BIGINT),
SHA256_HASH (VARCHAR 64), MEDIA_DATE (TIMESTAMP), CREATED_AT (TIMESTAMP)
→ UNIQUE index on SHA256_HASH

**JOB_STATISTICS**: JOB_ID (VARCHAR), FILES_PROCESSED (BIGINT), FILES_FAILED (BIGINT),
FILES_SKIPPED (BIGINT), DUPLICATES_FOUND (BIGINT), TOTAL_BYTES_PROCESSED (BIGINT),
TOTAL_BYTES_MOVED (BIGINT), TOTAL_BYTES_COPIED (BIGINT), AVG_MB_PER_SEC (DOUBLE),
PEAK_MB_PER_SEC (DOUBLE), AVG_FILES_PER_SEC (DOUBLE), PEAK_FILES_PER_SEC (DOUBLE)

### JSON Job State Schema (locked)

```json
{
  "jobId": "JOB-YYYYMMDD-NNN",
  "status": "RUNNING | PAUSED | COMPLETED | FAILED",
  "sourcePath": "/path/to/source",
  "targetPath": "/path/to/archive",
  "processedFiles": 0,
  "failedFiles": 0,
  "skippedFiles": 0,
  "emptyFiles": 0,
  "smallFiles": 0,
  "checkpointTime": "2026-06-03T22:15:44"
}
```

### Processing Flow (locked from BRD)

1. Scan Source Directory → 2. Discover Files → 3. Apply Ignore Rules →
4. Validate File Size → 5. Validate Media Format → 6. Extract Metadata →
7. Standardize Date → 8. Determine Destination Folder →
9. Copy or Move File → 10. Update Progress → 11. Persist Job State → 12. Generate Reports

### Non-Obvious State

- `spec-kit v0.9.2` installed globally via `uv tool install`.
- Skills in `.claude/skills/` — all `speckit-*` commands available in Claude Code chat.
- Constitution v1.1.0 has 8 principles (I–VIII) and a full FR traceability matrix.
  Always verify any new spec maps all 31 FRs to user stories (Gate G6).
- Phase 2 features (SHA-256 dedup as main feature, GPS, AI, Cloud) are **explicitly deferred**
  per BRD. Do not include them in the initial spec scope.
- The `_DUP_N` rename suffix for "Keep Both" policy and the `/_duplicates` and `/_failures`
  bucket paths are canonical — do not vary them.

### Concurrency invariants (established Session 4 — do not regress)

These were all violated in the original implementation and each would corrupt state or exhaust memory at
the scale Principle I mandates. Anything touching `ScanEngine` or `Database` must preserve them:

- **One SQLite connection per thread.** `Database.getConnection()` returns a `ThreadLocal` connection.
  Never cache the returned instance in a field or pass it between threads. Worker threads call
  `releaseCurrentThreadConnection()` as they die, wired through the pool's `ThreadFactory`.
- **The worker queue is bounded.** `ScanEngine` uses `ThreadPoolExecutor` + `ArrayBlockingQueue`
  (64 slots/thread) + `CallerRunsPolicy`. Do not switch to `Executors.newFixedThreadPool` — its queue is
  unbounded, and a 10M-file walk will fill it before the first task completes. Do not retain `Future`s.
- **Every cache shared across workers must be concurrent.** `destFolderCache` is a
  `ConcurrentHashMap`-backed set. Only the thread winning the `add` race increments the folder counter.
- **The scan must survive an unreadable directory.** `FileScanner.walkFileTree` uses a lazy recursive
  walk that logs and skips them. Do not revert to `Files.walk` — it throws `UncheckedIOException`
  mid-stream and aborts a multi-hour job on the first permission-denied folder.
- **`UNIQUE(SHA256_HASH)` is currently load-bearing.** It is what serialises the check-then-act duplicate
  decision in `processFile` and makes it accidentally correct. Feature 005 replaces it with an explicit
  atomic claim against `HASH_CANONICAL`; until then, do not drop the constraint.

### Known-wrong things not yet fixed (see `docs/ENGINEERING-AUDIT.md`)

- Resume is cosmetic (H5, blocker B005) — the largest remaining correctness gap
- FR-019, FR-020, FR-023 reports and FR-031 history are unbuilt → feature 005
- `Tika.detect` cannot see a truncated JPEG, so FR-012 corrupt detection is weaker than specified (M3)
- `ResourceMonitor` disk read/write MB/sec are hardcoded 0.0 (M4); `activeThreads` counts the whole JVM (M5)
- `JobStatistics` is written under a lock and read without one, so checkpoints can be internally
  inconsistent across fields (M9)

---

## Session Log

### 2026-09-01 — Session 4 (Audit + Feature 005 specification, tracker rebuild)

**Rebuild rationale**: The tracker still named `001-media-scanner-core` as the active feature at ~95%
with "No active blockers. Ready to begin `/speckit-specify`", while features 002, 003 and 004 had all
shipped. Stale by the constitution's own Tracker Rebuild Trigger (session log > 7 days, blockers
unreviewed). Rebuilt per Constitution § Tracker Rebuild Trigger.

**Work done**:
- Full engineering audit of all 36 main sources against Constitution v1.1.0 → `docs/ENGINEERING-AUDIT.md`.
  25 findings: 4 critical, 7 high, 9 medium, 5 process.
- Fixed 11 findings. The four critical ones all concerned concurrency at the scale Principle I mandates:
  - **C1** one JDBC connection shared by every worker thread → per-thread connections via `ThreadLocal`,
    `busy_timeout = 30000`, worker threads release their connection as they die
  - **C2** unbounded task submission plus a retained `Future` per file → bounded `ArrayBlockingQueue`
    (64/thread) with `CallerRunsPolicy` for backpressure; no `Future`s retained
  - **C3** plain `HashMap` caches written by all workers → concurrent set; `metadataCache` deleted (keyed
    by absolute path, so its hit rate was structurally zero while it grew one entry per file)
  - **C4** first unreadable directory aborted the whole scan → fault-tolerant lazy walk
  - Plus: ETA was always zero (`setFilesTotal` never called); Move mode did a full copy+delete even on
    the same volume; a 64 KB partial hash was computed and discarded for every file
  - **H8** the four `*IT` classes had never been executed by any build — `pom.xml` configures Surefire
    but not Failsafe, and Surefire's default includes do not match `*IT.java`. Those four are the only
    coverage of the DB layer, the resume path and the end-to-end pipeline, so the suite reported green
    while its most load-bearing tests were silently skipped. Added `maven-failsafe-plugin`.
  - **P2** added `.github/workflows/build.yml` — `mvn verify` on every branch push and PR across all
    three target platforms, with xvfb on Linux
- Specified feature 005 (Job Reports & History) in full: spec, plan, research, data-model, quickstart,
  checklist, 48 tasks.

**Decisions made**:
- Feature 005 records per-file outcomes to SQLite and derives the JSON reports at terminal state, rather
  than appending per-file. The existing `FileTransfer.appendFailureRecord` rewrites the entire JSON array
  per failure (O(n²)) and is not thread-safe — it is deleted, not wired up.
- `FILE_HASH_INDEX` is split in V002: `HASH_CANONICAL` takes the `UNIQUE(SHA256_HASH)` dedup gate so it
  stays atomic, while the index becomes a pure per-path cache. Today the single constraint serves both
  purposes and they conflict, so duplicate paths never cache and are re-read on every run.
- `SkippedRecord`, `FailureRecord` and `appendFailureRecord` are all dead code today and are replaced by
  one `JobEvent` type rather than carried forward.
- True resume (audit H5) is deliberately **not** folded into 005 — it is a separate correctness feature.

**Verification**: Maven is still not installed (B003) and the local `~/.m2` cache belongs to a different
project. Worked around it: full `javac` type-check of all 30 non-UI classes against source stubs for the
four missing third-party APIs (clean), a real JUnit run of the 6 test classes with satisfiable
dependencies (**59 tests, 59 passed**), and targeted verification of the two new behaviours no existing
test covers — atomic-move fast path and unreadable-directory tolerance with a real `icacls` DENY ACE
(8 checks, all passed). **The 13 tests needing sqlite-jdbc or Tika at runtime were not run.**

**Next action**: see Session 6 below.

---

### 2026-09-01 — Session 8 (Feature 007 true resume + 52k-file acceptance run)

**Work done**:
- **CI now prints test totals** to the job log and run summary, and hard-fails if zero tests run — so
  the H8 failure mode (tests silently not executing while CI stays green) cannot recur. Green on all
  three platforms.
- **Implemented feature 007 (True Resume)**, closing **B005**, the largest remaining correctness gap.
- **Ran the acceptance protocol at scale** (task T047), closing **B007**.

**The two defects were masking each other**, which is why five features shipped without anyone noticing:
1. *Self-duplicate* (introduced by feature 005): the atomic `HASH_CANONICAL` claim returned "already
   claimed" without checking whether the claimant was the same path, so every file on a second run was
   reported as a duplicate of itself.
2. *Self-collision* (present since feature 001): `resolveCollisionFreePath` ran before anything checked
   whether the file at the destination was this same file.

Bug 1 hid bug 2: misrouting every file as a duplicate meant nothing reached the transfer path, so
nothing was re-copied. Fixing bug 1 alone made the archive **double** on a re-run (8 files → 16). Both
are fixed together. A test that runs a job only once cannot see either — `RerunAndResumeIT` runs every
scenario twice.

**Resume design**: the archive is the ledger. `HASH_CANONICAL` gains `DESTINATION_PATH` /
`DESTINATION_SIZE` (migration V003); a later run that meets the same content skips it when that
destination still holds it. One stat per file, no content reads, and no per-file resume table — a
`JOB_PROGRESS` ledger would have been ~1 GB and 10M rows per job for information the archive already
encodes.

**Acceptance run — 52 552 files (1.5 GB), 16 threads**:

| | Pass 1 (cold) | Pass 2 (resume) |
|---|---|---|
| duration | 145 s | **16 s** |
| files copied | 49 700 | **0** |
| already in archive | 0 | 49 700 |
| throughput | 343 files/sec, 8.2 MB/sec | 3 106 files/sec |
| files in archive | 49 700 | 49 700 (unchanged) |

2 300 duplicates detected (54.9 MB saved), 502 skipped, 50 failed, 36 date folders created. All three
report counts reconcile exactly against the job statistics (**SC-002**). Peak heap **477 MB** across
both passes — the bounded queue holds, memory does not scale with file count. No collision-suffixed
self-copies. All seven acceptance checks passed.

**A note on benchmarking**: the first two corpus attempts were wrong in ways that made the engine look
wrong — the first generated only 1 300 distinct images (so 50 700 "duplicates" were real and correct),
the second set mtime but not creation time, so every file landed in one folder because these JPEGs have
no EXIF and FR-006 falls back to creation date. Both were corpus bugs, not engine bugs. Worth
remembering when writing the next benchmark.

**Next action**:
1. Open PRs for `005-job-reports-history` and `007-true-resume` (gh CLI is not authenticated here)
2. Click through the resume dialog and the export file dialogs by hand — the only untested UI paths
3. Address the open audit findings: M3 (corrupt-media detection is extension/magic-byte only),
   M4/M5 (disk I/O rates, worker thread count), M9 (statistics read/write race)

---

### 2026-09-01 — Session 7 (Committed, pushed, CI verified green)

**Work done**:
- Committed feature 005 + the audit fixes to branch `005-job-reports-history` (63 files) and pushed.
- **CI is green on all three platforms** (run 33519273821) — the `Build` workflow's first successful
  execution, closing **B004** for real rather than on configuration alone.

**The first CI run failed on all three runners.** `mvnw` was committed as mode 100644: this repo has
`core.filemode=false` (Windows), so the executable bit was never recorded and `./mvnw` died with
Permission denied everywhere. Fixed with `git update-index --chmod=+x mvnw`. Worth remembering for any
future script committed from this machine — the local build cannot catch this, only CI can.

**Deliberately NOT committed**: another session (`media-scanner-68`) is concurrently speccing feature
006 (Cleanup Tool) in this same working tree. Its three paths — `.specify/feature.json`,
`.specify/memory/constitution.md` (amended to v1.2.0, adding Principle IX Destructive Operations Safety
and Gate G7), and `specs/006-cleanup-tool/` — were excluded from the commit and left untouched. HEAD was
handed back to the `006-cleanup-tool` branch afterwards so that session resumes where it left off.

Because 006 is taken, the resume feature (**B005**) is renumbered **007** throughout.

**Note on Principle IX**: feature 005 already complies. Deleting a job from history removes only database
rows and explicitly never touches archive files, and the confirmation dialog says so.

**Verification limits**: the CI logs and artifacts need repo-admin auth to download, so the exact
per-OS test counts were not read. What is known: `./mvnw verify` exited 0 on each OS (Maven fails the
build on any test failure) and each run produced 128–155 KB of Surefire/Failsafe XML, so tests ran and
passed. `FxmlLoadIT` skips itself when no JavaFX toolkit is available, so it may have been skipped rather
than run on some runner.

**Next action**:
1. Open a PR for `005-job-reports-history` (gh CLI is not authenticated on this machine)
2. Run the remaining half of `specs/005-job-reports-history/quickstart.md` against a real 50 000+ file
   archive (**B007**, task T047)
3. Cut feature **007** for true resume (**B005**) — the largest remaining correctness gap
4. Then the open audit findings: M3 (corrupt-media detection), M4/M5 (resource monitoring), M9 (stats race)

---

### 2026-09-01 — Session 6 (Maven wrapper, GUI acceptance pass)

**Work done**:
- Committed a Maven wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper`) pinned to 3.9.9 and switched
  `build.yml` to `./mvnw`, so a clean checkout builds without an ambient Maven install (**B003** closed).
- **Launched the real application and drove it** — the first time this has been done. Ran against a
  sandboxed `user.home` with a seeded database, so the developer's own `~/.mediascanner` was untouched.
- Verified end to end in the GUI: Job History renders 3 stored jobs newest-first with correct dates,
  counts and byte formatting; row selection enables Open Summary / Delete and leaves them disabled with
  no selection; Open Summary loads a job from the database that never ran in this session (FR-005-009);
  the throughput charts render from stored samples (FR-031), with a seeded stall clearly legible.

**Two UI defects found by looking at the screenshots, both fixed**:
1. The Job History subtitle used `styleClass="subtle"`, which **was not defined in any stylesheet** — so
   it fell back to dark text on the dark navy header and was invisible. Added `.subtle`,
   `.header-bar .subtle` and `.chart-placeholder` to all three themes.
2. `ThroughputChart` plotted files/sec and MB/sec on **one shared y-axis**. At 900 files/sec vs 40 MB/sec
   the MB/sec line was flattened onto the axis and unreadable, which quietly failed half of FR-028. Split
   into two stacked charts, each with its own y-axis. The fix immediately paid off: MB/sec *rises* during
   the window where files/sec collapses — the signature of processing a few very large files — which was
   invisible before.

**A methodology note worth keeping**: the first several screenshots appeared to show a missing button bar
on Job History. It was not a bug — the capture was DPI-unaware while the app is DPI-aware at 150%, so a
third of the window was being cropped. Adding `SetProcessDPIAware()` to the driver revealed the bar
present and correct. Chasing it down avoided "fixing" a layout that was never broken.

**Verification limits**: still no run against a real 50 000+ file archive, and the export file dialogs
were not exercised (`SummaryExporter` is covered by 10 unit tests instead). `build.yml` has still never
executed.

**Next action**: see Session 7 below.

---

### 2026-09-01 — Session 5 (Feature 005 implemented and verified)

**Work done**:
- Installed Maven 3.9.9 and got the project building for the first time. `mvn verify` now passes:
  **187 tests, 0 failures** (124 unit, 63 integration).
- Implemented feature 005 end to end — 46 of 48 tasks. All five user stories. FR-019, FR-020, FR-023 and
  FR-031 are closed.
- Added 10 test classes, including `ScanReportsEndToEndIT` (the spec's own US1/US2 acceptance scenario
  driven through the real engine) and `MigrationV002IT` (the V001→V002 upgrade path).

**Decisions made**:
- `HASH_CANONICAL` now takes the duplicate gate as an explicit `INSERT OR IGNORE` claim. The old
  check-then-act pair in `processFile` was correct only because a UNIQUE constraint happened to
  serialise it; that is now guaranteed by construction, and proven by `HashCanonicalConcurrencyIT`.
- The partial hash is computed from the first chunk of the full-hash read, not a second file open, so
  `PARTIAL_HASH` is populated for future FR-025 Stage 2 work at zero extra I/O.
- T035 (Tools → Export Summary) declined: the Export buttons already sit on the summary screen, and a
  Tools entry would be disabled most of the time. Recorded in tasks.md rather than silently skipped.

**Found by actually running the build** (both fixed):
1. `Database.splitStatements` split on the statement terminator *before* stripping comments, so a `--`
   comment containing one was cut in half and its tail executed as SQL. Comments are now stripped first.
   Ironically caught by a comment in the new migration warning about exactly this.
2. The spec's US1 independent test was wrong: a short bogus `.mp4` is reported as `SMALL_FILE`, not as a
   failure, because the small-file gate runs before the corrupt-media gate. Spec and tasks corrected;
   the test file now exceeds the 100 KB video threshold on purpose.

**Verification limits**: nobody has driven the GUI by hand (**B007**), and `build.yml` has never
executed (**B004**). UI coverage is `FXMLLoader`-level: all 7 screens load, which catches a renamed
`fx:id` or a bad handler, but not a layout or usability problem.

**Next action**:
1. `git checkout -b 005-job-reports-history`, commit, push — confirm **B004** goes green on all three runners
2. Run `mvn -N wrapper:wrapper` and commit the wrapper (**B003** follow-up) — the Maven used here lives
   in a scratch directory, not on `PATH`
3. Run the acceptance protocol in `specs/005-job-reports-history/quickstart.md` against a real archive of
   50 000+ files (**B007**, task T047)
4. Cut feature **007** for true resume (**B005**) — the largest remaining correctness gap
5. Then the open audit findings: M3 (corrupt-media detection), M4/M5 (resource monitoring), M9 (stats race)

---

### 2026-06-03 — Session 3 (Implementation)
**Work done**:
- Ran `/speckit-implement` — executed all 82 tasks across 12 phases
- Created 48 Java source files (29 production + 19 test)
- Phase 1: `pom.xml` (all deps), `logback.xml`, directory scaffold, `MediaScannerApp.java`, `.gitignore`
- Phase 2: All 8 domain models, `AppConfig`, `Database`, `HashIndexDao`, `JobStatisticsDao`, `ScanEngine`, `V001__initial_schema.sql`, `DatabaseIT`, `HashIndexDaoIT`
- Phases 3–11: `FileScanner`, `FileValidator`, `MetadataExtractor`, `FileTransfer`, `HashEngine`, `CheckpointManager`, `JobStateExporter`, `ProgressTracker`, `ResourceMonitor`, `ThroughputHistory`, `DataUnitFormatter`, `MainController`, `DashboardController`, `SummaryController`, `main.fxml`, `dashboard.fxml`, `summary.fxml`, `mediascanner.css`
- Phase 12: `FullPipelineIT`, `PauseLatencyTest`, all unit tests for all components
- All 82 tasks marked `[X]` in tasks.md

**Decisions made**:
- All engine/DB code uses synchronized blocks on `jobStatistics` for thread safety (alternative to AtomicLong wrappers)
- `ScanEngine` uses RAM-aggressive caches (`destFolderCache`, `metadataCache`) per T071
- `ResourceMonitor` uses `com.sun.management.OperatingSystemMXBean` with graceful fallback
- `CLibrary.java` provides JNA binding for macOS `setpriority` (Unix High-Priority Mode)
- Maven is not installed in this dev environment — `mvn compile` cannot be verified locally

**Next action**:
1. Install Maven 3.9+ and Java 21 LTS: `brew install maven` / `sdk install java 21`
2. Run `mvn clean compile` — verify zero errors
3. Run `mvn test -Dtest="DatabaseIT,HashIndexDaoIT"` — Phase 2 checkpoint
4. Run `mvn test` — all unit tests green
5. Run `mvn javafx:run` — UI launches, Start button enables when both paths valid
6. Manual acceptance test per `quickstart.md` US1 row: copy 100 files → check folder structure
7. T079: Run all US acceptance scenarios from quickstart.md
8. T078: Add FFmpeg binaries to `src/main/resources/ffmpeg/` for packaging

---

### 2026-06-03 — Session 2
**Work done**:
- Read both BRD documents in full: MediaScanner BRD.docx (FR-001–022, NFR-001–006, tech stack)
  and Functional Requirements Document.docx (FR-023–031, SQLite schema, performance targets)
- Resolved blockers B001 and B002 — full FR baseline now available
- Amended constitution to v1.1.0:
  - Added Principle VII (Folder Organization and Transfer Discipline) covering FR-001–009
  - Added Principle VIII (renumbered from VI) for Development Discipline
  - Expanded Principle VI (Media Validation) covering FR-010–013, FR-019–020
  - Added Technology Stack Reference section (Java 21, JavaFX 21, Maven, etc.)
  - Added FR Traceability Matrix (all 31 FRs mapped to principles)
  - Added G6 Quality Gate (all FRs must be mapped in spec)
  - Added Tracker Rebuild Trigger protocol
- Rebuilt development tracker (this file) with full BRD context and locked schemas

**Decisions made**:
- Technology stack is locked from BRD — no alternatives without constitution amendment
- Phase 2 BRD features (GPS, AI, Cloud) explicitly deferred — out of scope for v1
- Processing flow order is canonical (12 steps from BRD)
- JSON job state schema and SQLite schema are locked

**Next action**:
1. Run `/speckit-specify` with: "MediaScanner — Core media scanning, organization, and
   transfer engine covering all 31 FRs from the BRD. Java 21 + JavaFX 21 desktop application
   for Windows and macOS. Scans source directories recursively, validates media files, extracts
   metadata, organizes into date-based folder structure, handles duplicates and failures,
   supports pause/resume, and provides real-time progress dashboard."
2. Verify Gate G6: all FR-001–031 mapped to user stories in the spec
3. Then run `/speckit-clarify` to de-risk any ambiguities before planning

---

### 2026-06-03 — Session 1
**Work done**:
- Installed spec-kit v0.9.2 via `uv tool install`
- Initialized MediaScanner project with Claude Code integration (`--integration claude`)
- Ratified Constitution v1.0.0 based on initial FRD analysis (FR-023–031 from provided docx)
- Created development tracker v1

**Decisions made**:
- Constitution principles: Performance-First, Context Preservation/Resume, SQLite as SSOT,
  Observability, Duplicate Handling First-Class, Development Discipline
- Tracker update protocol formalized in Constitution § Development Tracker Standards

**Next action** (superseded by Session 2):
- ~~Obtain and add FR-001–022 to the `/brd` folder (resolve B001, B002)~~ ✅ Done in Session 2

---

<!-- HOW TO UPDATE THIS FILE
  Session end checklist:
  1. Update "Active Feature" table with current branch/spec/plan/tasks links
  2. Check off completed Phase Progress items
  3. Add or close Blockers as needed
  4. Update Context Snapshot with any new decisions or file paths
  5. Add a new Session Log entry (newest at top) with:
     - Date and session number
     - Work done (bullet list)
     - Decisions made
     - Next action (specific enough to resume cold)
  6. Run /speckit-agent-context-update to push changes into CLAUDE.md

  TRACKER REBUILD TRIGGER:
  If stale (> 7 days no update OR blockers unreviewed > 3 sessions):
  1. git log --oneline -20 to assess actual progress
  2. Inspect key source files to re-derive completion %
  3. Rewrite Context Snapshot with current file paths and decisions
  4. Add Session Log entry flagging the rebuild
-->
