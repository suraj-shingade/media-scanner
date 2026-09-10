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

**Phase**: Features 001–008 implemented and merged to `main`. Feature 006 (Cleanup Tool) landed in
Session 9 and was substantially reworked in Session 10 into **Delete Files & Folders**, which is on an
unmerged branch.
**Overall Completion**: ~94% of the full BRD. FR-019, FR-020, FR-023, FR-031 and true resume
(FR-017/FR-022) are closed. FR-032–FR-058 (Cleanup) are closed; FR-059–FR-063 (direct delete) are
implemented but **blocked on B008** — they conflict with Constitution Principle IX.
**Constitution Version**: 1.2.0 — **amendment decision pending, see B008**

---

## Active Feature

| Field | Value |
|-------|-------|
| Branch | `010-delete-discoverability` — **2 commits, not pushed, not merged** (`71e1175`, `f89e8da`) |
| Spec | `specs/006-cleanup-tool/spec.md` ✅ — extended in Session 10 with FR-059–FR-063 |
| Plan | `specs/006-cleanup-tool/plan.md` ✅ — **not yet updated for the direct-delete mode** |
| Tasks | No tasks file cut for the Session 10 rework; it was driven directly from user feedback |

**Build status**: `./mvnw -o verify` — **240 tests, 0 failures** (148 unit, 92 integration across 16 IT
classes). Verified 2026-09-05 on Windows only; CI has not run this branch.

**Outstanding on this branch**: B008 (constitution conflict), plan.md not updated, no tasks file, G7
Destructive Review not recorded for the direct-delete path, branch unpushed.

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
| B008 | **Direct-delete mode violates Constitution Principle IX.** Needs a decision, not a fix | Suraj | 🔴 **OPEN** | Next session |

**One blocker is open: B008.** The narrow remaining gaps from B007 (export file dialogs never driven
by hand) are still open but are not blocking.

### B008 — detail

The direct-delete mode built in Session 10 was requested explicitly and repeatedly by the user
("when job is going to get start we should ask and then directly delte when found"). It confirms the
*criteria* before the walk starts, then deletes matches as it finds them in one pass. Principle IX
requires the opposite, in two rules that are marked non-negotiable:

- *Preview before delete* — "the user MUST be shown the complete set of items a destructive operation
  would remove — path, size, and the reason each item qualified — before anything is removed.
  **A destructive operation that cannot enumerate its targets in advance MUST NOT run.**"
- *Explicit confirmation* — "The confirmation MUST state the exact item count, the total bytes..."

The shipped mode does neither. FR-060 in `specs/006-cleanup-tool/spec.md` records the narrowing, but a
feature spec cannot override the constitution — § Governance states the constitution "supersedes all
other project conventions".

**What is not in breach.** The other nine rules of IX all hold and are covered by tests: no default
destructive scope, media never deletable, contents decide not names, re-verify immediately before
acting, never escape the selected tree, refuse dangerous roots, continue past individual failures,
cancellable, durable report. `CleanupStreamingIT.testPhotosSurviveEvenWhenTheirExtensionIsNamed` is the
protected-media survival test G7 asks for.

**Two ways to resolve — this is the user's call, not the implementer's:**

1. **Amend the constitution to 1.3.0 (MINOR).** Permit a second confirmation shape for operations that
   cannot enumerate in advance, conditional on compensating controls: criteria stated in full, live
   progress, cancellable mid-run, per-file content re-check, durable report. Requires, per § Governance:
   a PR bumping the version, an updated Sync Impact Report, dependent templates verified, and a tracker
   entry recording the rationale. This keeps the behaviour the user asked for.
2. **Revert the direct mode**, keeping the discoverability rename, the extension selection and the
   activity indicators, and require the review-first flow for every deletion. This keeps the
   constitution intact and loses the feature.

Option 1 is the better engineering answer — the compensating controls are real and the enumerate-first
rule was written before a streaming mode existed — but amending a governance document is not a decision
to take unilaterally, and G7 exists precisely so this is not waved through. **Do not merge
`010-delete-discoverability` to `main` until B008 is resolved.**

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
- **The duplicate claim is now explicit, not accidental.** *(Updated Session 10 — the previous entry
  described a constraint the schema no longer has.)* `UNIQUE(SHA256_HASH)` on `FILE_HASH_INDEX` used to
  serialise the check-then-act in `processFile` by luck. Feature 005 dropped it in `V002` — deliberately,
  so a duplicate path can cache its own hash — and replaced it with `HashIndexDao.claimCanonical()`:
  an `INSERT OR IGNORE INTO HASH_CANONICAL` whose primary key does the serialisation. The winner of the
  insert owns the canonical copy. **Do not reintroduce a read-then-write duplicate check**; call
  `claimCanonical` and branch on whether the claim succeeded.

- **The Cleanup engine is deliberately isolated.** `CleanupEngine` runs no worker pool, takes no SQLite
  connection, and shares nothing with `ScanEngine`. This is blast-radius control, not an oversight — a
  bug in the concurrent transfer pipeline must have no route to a deletion. Do not "simplify" by giving
  it the engine's thread pool or a database handle.

### Known-wrong things not yet fixed (see `docs/ENGINEERING-AUDIT.md`)

*Rewritten Session 10. Every item on the previous list had been fixed and the list had not been updated —
it named resume as the largest open gap three sessions after feature 007 closed it. Verified against the
audit and the code on 2026-09-05.*

**Closed since the list was last accurate**: H5 / B005 resume (feature 007); FR-019, FR-020, FR-023,
FR-031 reports and history (feature 005); M3 truncated-media detection, M4 disk I/O rates, M5
`activeThreads`, M9 `JobStatistics` consistency (all feature 008); N8 idle-CPU spin (Session 10).

**Genuinely still open:**

- **B008** — direct-delete conflicts with Principle IX. Decision pending; blocks the merge.
- **P3** — `pom.xml` version hardcoded to `1.0.0`, so every build claims to be the same release.
- **P4** — feature 003 (installable builds) has no implementation commit; the work exists but is not
  attributable to a change.
- **B007 remainder** — the export file dialogs are the one UI path never driven by hand. Everything else
  in the GUI has now been exercised.
- **Session 10 process debt** — `specs/006-cleanup-tool/plan.md` was not updated for the direct-delete
  mode, no tasks file was cut, and no G7 Destructive Review is recorded for that path. All three are
  required by the constitution before this branch merges.

### Considered and not adopted

- **Native rebuild (Rust core + per-platform shells)** — proposed 2026-09-05, not scheduled. The finding
  worth keeping: the case for it is *access*, not speed. Copy-on-write clones (`clonefile(2)`, `FICLONE`,
  ReFS block cloning), volume change journals, native trash and NPU inference are unreachable from the
  JVM and are where the order-of-magnitude wins are. Rust itself buys ~2–4× on classification and
  ~8–10× on memory, and **nothing** on bandwidth-bound hashing and copying. Also recorded: the Delete
  module competes directly with czkawka and fclones, both free, both Rust, both faster — the
  differentiator is verified transfer with proof, not cleanup.

---

## Session Log

### 2026-09-03 → 09-05 — Session 10 (Delete Files & Folders rework, N8, tracker rebuild)

**Driven entirely by user feedback, in three rounds.** Worth recording because each round found a
failure the tests could not have.

1. *"i cannot see delete file module option we requested"* — feature 006 was complete, tested and
   invisible: it had never been committed (recorded as N7 in Session 9).
2. *"i cannot find delete file/folders feature requested"* — still not found after it shipped. The menu
   item said "Cleanup…" and sat under Tools. Renamed to **"Delete Files & Folders…"**, moved to the View
   menu, given `shortcut+5`. A feature named after its implementation is a feature nobody finds.
3. *"no option to add file formats, empty folders, mime types nothing… we don't need to analyse"* —
   the review-first flow was the only flow. Built the direct mode alongside it.
4. *"activity indicators with messages is not visible so user do not know what is happening"* — added
   the live activity row.
5. *"wait for user to confirm… when job is going to get start we should ask and then directly delte when
   found. and at last show the nice stats"* — moved confirmation ahead of the walk and made deletion
   streaming. **This is what created B008.**

**Engine** — `CleanupEngine.delete(run, groups, extensions)` overload plus `extensionOf()` and
`normaliseExtension()` (accepts `jpg`, `.jpg`, `*.jpg`, `JPG`); new `deleteWhileScanning()` returning
`StreamingResult`, reporting a `DeleteProgress` record per file. Selection by extension is strictly
additive and cannot widen what is deletable — `deleteOne` still re-classifies by content immediately
before removal.

**UI** — `cleanup.fxml` rebuilt around a `TabPane`: "Choose what to delete" (group checkboxes, extension
list, empty-folders) and "Review first" (the original analyse flow, unchanged). `CleanupController`
rewritten: confirm-before-start, streaming delete with progress throttled to every 20th file, and an
end-of-run stats grid (examined, deleted, freed, pruned, skipped, failed, elapsed, per-group breakdown).

**N8 — a hidden progress bar burned a full CPU core.** `<ProgressBar progress="-1.0"/>` in the FXML:
JavaFX runs the indeterminate animation from construction and does not stop it when the node is
`visible="false"`. The Delete screen sat at **1.01 cores** doing nothing. Now declared `0.0` and made
indeterminate only for the duration of an operation; measured **0.003 cores** idle afterwards.
Secondary lesson, recorded in the audit: my first measurement of this was wrong because I sampled a CPU
*percentage* at one instant and reported "4.6% of a core, not runaway". For CPU, measure the delta of
`TotalProcessorTime` over an interval, never the snapshot.

**Tests** — `CleanupByExtensionIT` (6) and `CleanupStreamingIT` (6) added. **240 total, 0 failures**
(148 unit, 92 integration). The load-bearing case in both is that naming a media extension deletes
nothing.

**Also**: `specs/006-cleanup-tool/spec.md` extended with FR-059–FR-063; README screens table corrected;
audit gained N8; a native-rebuild architecture proposal was produced and recorded above under
*Considered and not adopted*.

**Tracker rebuild.** This file had drifted badly: it claimed 228 tests (240), branch `main`
(`010-delete-discoverability`), feature 006 "being spec'd in a separate session" (landed in Session 9),
a load-bearing `UNIQUE(SHA256_HASH)` constraint (dropped in V002 and replaced), and a known-wrong list
on which every item was already fixed. All corrected against the code rather than against memory.

**Next action**: resolve **B008** — amend the constitution to 1.3.0 or revert the direct-delete mode.
Nothing else on this branch should merge first. After that: update `plan.md`, record the G7 Destructive
Review, push, and let CI run the branch on all three platforms.

### 2026-09-02 — Session 9 (Cleanup Tool landed, branding, job-id fix, CI diagnosability)

**The Cleanup Tool was missing because it was never committed.** Feature 006 had been built in a
parallel session — 14 source files, spec, plan, tasks, constitution amendment to v1.2.0 — but existed
only as uncommitted files in the working tree. It was absent from `main` and from any release build,
which is exactly why the delete option could not be found. Verified (22 tests, and the screen opened
and inspected in the running app) and committed. **Tools → Cleanup…** now ships.

**Branding.** A generated mark — navy badge, white photo tile, cyan scan beam — deliberately kept to
two shapes plus a beam so it survives 16px. Wired into the window and task-bar icon at six sizes, the
app header, the About wordmark, the Windows `.ico` and macOS `.icns` used by jpackage, and a new
README. The `.ico` and `.icns` containers are written directly; no new dependency.

**A real bug found by clicking a button.** Job IDs collided across application restarts: the counter
behind `JOB-yyyyMMdd-NNN` is a static starting at 1, so the first job of *every JVM* was
`JOB-<date>-001`. Restart the app, start a scan the same day, and it died instantly on a PRIMARY KEY
violation. Present since feature 001; no test could see it because no test restarts the JVM. IDs now
carry the time of day, pinned by `JobIdUniquenessTest`.

**T018 measured.** Deep validation costs **+145% on the validation stage, 0.11 ms per file**, and
detects **450 corrupt files** in a 53k corpus that the header-only gate passes as valid. Validation is
a small share of a job, so end to end it is a few percent. The first measurement claimed deep was 74%
*faster* — a cold page-cache artifact from whichever mode ran first. The benchmark now warms up and
measures both orders.

**macOS CI was red, and diagnosing it took three round trips** because job logs, step summaries and
artifacts all need repo-admin rights, which this machine does not have. The fix was to make CI
self-diagnosing: failing test names, run totals and forked-JVM dumps are now emitted as **annotations**,
which anyone who can see the repo can read. That immediately revealed the cause — **SIGSEGV after 212
passing tests**. JavaFX on macOS needs the toolkit on the process main thread, so starting it inside a
Surefire fork crashes the JVM instead of throwing, and the existing try/catch could not guard it.
`FxmlLoadIT` now skips on macOS; Linux (xvfb) and Windows still cover FXML loading.

**A self-inflicted lesson, twice.** Embedding newline escapes through the shell produced literal
newlines — once inside a Java string, once inside the CI workflow, where a line landing at column 0
terminated the YAML block scalar and the workflow stopped parsing entirely (zero jobs dispatched).
Both now avoid escapes: `chr(10)` in the workflow, and the Edit tool for Java.

**Next action**:
1. Confirm the macOS CI fix went green on `main` (the API rate limit blocked the final check)
2. Drive the export file dialogs by hand — the last untested UI path
3. Remaining audit findings: M3 partially closed by feature 008; M9 closed; M4/M5 closed

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
