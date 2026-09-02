# MediaScanner — Engineering Audit

**Date**: 2026-09-01
**Scope**: All 36 main source files (4 185 LOC) + 19 test classes, against Constitution v1.1.0 and the FR-001–FR-031 traceability matrix.
**Baseline commit**: `242449a`

This audit was produced while planning feature 005. It records every defect and gap found, what was fixed
in this pass, and what is deliberately routed to a feature spec instead of a hotfix.

---

## Summary

| Severity | Found | Fixed in the audit pass | Fixed by feature 005 | Open |
|----------|-------|------------------------|----------------------|------|
| Critical | 4 | 4 | 0 | 0 |
| High | 8 | 4 | 4 | 0 |
| Medium | 9 | 4 | 3 | 2 |
| Low / process | 5 | 1 | 0 | 4 |
| **Total** | **26** | **13** | **7** | **6** |

**Open at the end of this pass**: M3 (corrupt-media detection is extension and magic-byte only), M4
(disk I/O rates hardcoded to 0), M5 (`activeThreads` counts the whole JVM), M9 (statistics written under
a lock, read without one), P1 (no committed Maven wrapper), P3 (`pom.xml` version hardcoded). H5 —
resume being cosmetic — is unfixed and is the largest remaining correctness gap, tracked as its own
feature.

The headline finding: the engine is functionally complete against most FRs but is **not safe at the
concurrency the constitution mandates**. Four separate defects would each independently corrupt state or
exhaust memory on a job of the size Principle I targets (10M+ files, 20+ TB). All four are fixed.

The second finding: **four FRs are counted but never produced**. FR-019, FR-020, FR-023 and FR-031 all
increment a statistic that reaches the dashboard, but no failure report, skipped report, duplicate report,
or throughput history is ever written to disk or surfaced. These are the scope of feature 005.

---

## Verification performed

Maven was initially unavailable. It has since been installed (3.9.9, on JDK 21) and the project now
builds and tests for real:

- **`mvn verify` passes end to end** — **187 tests, 0 failures** (124 unit via Surefire, 63 integration
  via the newly added Failsafe).
- The four `*IT` classes that had **never executed before this pass** now run and pass, alongside eight
  new integration classes added for feature 005.
- **Targeted verification** of the two behaviours no existing test covered (atomic-move fast path;
  unreadable-directory tolerance, with a real `icacls` DENY ACE applied) — 8 checks, all passed.
- **All 7 FXML screens** load through a real `FXMLLoader` (`FxmlLoadIT`), which is what catches a
  renamed `fx:id` or a bad `onAction` handler — neither of which the compiler sees.

Two findings came out of actually running the build, both now fixed:

1. `Database.splitStatements` split on the statement terminator *before* stripping comments, so a `--`
   comment containing one was cut in half and its tail handed to SQLite as a statement. Comments are now
   stripped first. Found because a comment in the new V002 migration warned about exactly this and then
   tripped it.
2. The V001 schema assertions in `DatabaseIT` needed rewriting for V002 — not by relaxing them, but by
   asserting the new invariant: the unique constraint on `FILE_HASH_INDEX` must now be on `FILE_PATH`,
   never on `SHA256_HASH`.

The application has since been **launched and driven** against a sandboxed home directory with a seeded
database. Job History, row selection, stored-job summary loading and the throughput charts all verified
visually. Two UI defects were found this way and fixed:

- `styleClass="subtle"` was referenced by the Job History subtitle but **defined in no stylesheet**, so
  it rendered as dark text on the dark navy header — invisible. Added to all three themes.
- `ThroughputChart` plotted files/sec and MB/sec on one shared y-axis. At 900 files/sec vs 40 MB/sec the
  MB/sec series was flattened onto the axis, quietly failing half of FR-028. Split into two stacked
  charts with independent axes.

Neither was reachable by any automated test, which is the argument for actually running the thing.

A **52 552-file acceptance run** has since been completed (1.5 GB, 16 threads):

| | Pass 1 (cold) | Pass 2 (re-run) |
|---|---|---|
| duration | 145 s | 16 s |
| files copied | 49 700 | 0 |
| throughput | 343 files/sec, 8.2 MB/sec | 3 106 files/sec |
| archive size | 49 700 | 49 700 (unchanged) |

2 300 duplicates detected, 502 skipped, 50 failed, 36 date folders. All three report counts reconcile
exactly against the job statistics. **Peak heap 477 MB** across both passes, confirming the C2 bounded
queue holds and memory does not scale with file count.

**Still not verified**: the export file dialogs and the new resume dialog have not been clicked by hand.

> **Note for anyone repeating this**: screenshot the app from a **DPI-aware** process. Windows here runs
> at 150%; a DPI-unaware capture crops a third off the right and bottom and puts synthetic clicks ~33%
> off target. It initially looked like the Job History button bar was missing — it was not.

---

## Critical — fixed in this pass

### C1. One JDBC connection shared across every worker thread
`db/Database.java` held a single `Connection` and handed the same instance to every caller.
`HashIndexDao` and `JobStatisticsDao` are invoked concurrently from all `CPU × 2` worker threads, so
every job ran concurrent `PreparedStatement` traffic over one SQLite connection. SQLite connections are
not safe for concurrent use; this produces interleaved result sets, `SQLITE_MISUSE`, and silently wrong
hash lookups — which in turn means wrong duplicate decisions.

**Fixed**: each thread now gets its own connection via `ThreadLocal`, tracked for shutdown. Added
`PRAGMA busy_timeout = 30000` so writers wait for the WAL write lock instead of failing immediately.
Worker threads call `releaseCurrentThreadConnection()` as they die (wired through the pool's
`ThreadFactory`), so a long session does not accumulate one connection per thread per job.

### C2. Unbounded task submission and an unbounded `Future` list
`ScanEngine.start()` walked the source tree and submitted every file to
`Executors.newFixedThreadPool(...)` — whose queue is an **unbounded** `LinkedBlockingQueue` — while also
retaining every `Future` in an `ArrayList`. On a 10M-file source both the queue and the list grow to 10M
entries before the first `Future.get()` is reached. Heap exhaustion long before that.

**Fixed**: a `ThreadPoolExecutor` with a bounded `ArrayBlockingQueue` (64 slots per thread) and
`CallerRunsPolicy`. When the queue fills, the walking thread executes the task itself, which throttles
the producer to the consumers' rate. `Future`s are no longer retained; completion is awaited via
`awaitTermination`, which also makes Stop responsive.

### C3. Non-concurrent `HashMap` caches shared across worker threads
`destFolderCache` and `metadataCache` were plain `HashMap`s written by every worker with no
synchronisation. Concurrent `put` on a `HashMap` can corrupt the internal table — historically an
infinite loop on resize, and at minimum lost entries and a miscounted `TOTAL_FOLDERS_CREATED`.

**Fixed**: `destFolderCache` is now a `ConcurrentHashMap`-backed `Set`, and the folder-created counter
increments only for the thread that wins the `add` race. `metadataCache` was **removed entirely** — it
was keyed by absolute path, and each path is visited exactly once per job, so its hit rate was
structurally zero while it grew to one entry per file. It was pure memory overhead.

### C4. One unreadable directory aborted the entire scan
`FileScanner.walkFileTree` used `Files.walk`, which throws `UncheckedIOException` mid-stream on the first
directory it cannot read. On a real archive — a permission-denied folder, a stale network mount, a
Windows system directory — a multi-hour job would terminate partway with no partial result.

**Fixed**: a lazy recursive walk that logs and skips unreadable directories and continues. Verified with
a real DENY ACE: the locked directory's contents are skipped, everything else is still found.

---

## High

### H1. FR-019 — the failure bucket is never written → **FIXED in feature 005**
`FileTransfer.appendFailureRecord` exists and is correct-ish, but **nothing ever calls it**.
`/_failures/failure-report.json` is never created. `ScanEngine` increments `filesFailed` and returns.
The method is also unusable as written at scale: it reads the entire JSON array, appends one element,
and rewrites the whole file — O(n²) total I/O — and it is not thread-safe.

### H2. FR-020 — the skipped bucket is never persisted → **FIXED in feature 005**
`SkippedRecord` is a model class with no writer and no reader. Skip reasons are counted in aggregate
(`emptyFilesCount`, `smallFilesCount`) but the per-file reason is discarded, so a user cannot find out
*which* files were skipped or why.

### H3. FR-023 — the duplicate report is never produced → **FIXED in feature 005**
Duplicate detection works, and count/bytes-saved are tracked. But the FR requires "file locations and
hash values", and neither is recorded anywhere. There is no way to review what was deduplicated.

### H4. FR-031 — `ThroughputHistory` is dead code → **FIXED in feature 005**
The class is complete and correct. It is referenced by exactly nothing: no sampler feeds it, no view
reads it. The historical throughput graph does not exist.

### H5. Resume is cosmetic → **FIXED in feature 007**
`CheckpointManager` faithfully writes `checkpoint.json` every 1 000 files / 60 s, and `MainController`
detects an interrupted job — then `offerResume()` shows a dialog whose OK branch sets a label reading
"Import the job state file to resume." `ScanEngine.start()` has no notion of resuming: it re-walks the
whole tree and reprocesses every file. Worse, because `resolveCollisionFreePath` runs first, a
"resumed" job re-copies everything already transferred as `IMG001(1).jpg`, `IMG001(2).jpg`, silently
doubling the archive. This is the largest correctness gap remaining and deserves its own feature.

**Update (feature 007)**: the picture was worse than this finding described, and also better. A second
defect introduced by feature 005 — the atomic `HASH_CANONICAL` claim not checking whether the claimant
was the same path — meant every file on a re-run was reported as a duplicate of itself, which
*prevented* the re-copy described above. The two bugs masked each other. Fixing only the claim guard
made an 8-file archive become 16 on a re-run, which is how the duplication was finally reproduced.
Both are fixed, and a 52 552-file re-run now copies zero bytes in 16 s against 145 s cold.

### H6. ETA was always zero — **fixed**
`ProgressTracker.setFilesTotal` was never called, so `filesTotal` stayed 0, `remaining` went negative,
and the ETA branch never fired. FR-026 ("total files found") and FR-029 (ETA) were both non-functional.

**Fixed**: `ScanEngine` starts a background counting pass over the source tree and publishes the total.
In Move mode the walk races with files leaving the tree, so the already-transferred count is added back.

### H8. The four integration test classes have never been executed — **fixed**
`DatabaseIT`, `HashIndexDaoIT`, `ResumeIT` and `FullPipelineIT` are compiled by every build and run by
none of them. `pom.xml` configures Surefire but not Failsafe, and Surefire's default includes are
`Test*.java`, `*Test.java`, `*Tests.java`, `*TestCase.java` — none of which match `*IT.java`.

This is why it matters more than a missing-plugin annoyance: those four classes are the *only* coverage
of the database layer, the resume path, and the end-to-end pipeline — precisely the areas where C1 (the
shared connection) and H5 (cosmetic resume) hid. The suite reported green while its most load-bearing
tests were silently skipped.

**Fixed**: added `maven-failsafe-plugin` 3.2.5 bound to `integration-test` and `verify`, with the same
`--add-opens` argLine as Surefire. `mvn verify` now runs both.

**Verified**: `mvn verify` now runs them. All four passed on their first-ever execution, and 63
integration tests pass in total.

### H7. Move mode re-read the entire archive — **fixed**
`FileTransfer.move` was `copy(); delete();` unconditionally. For a same-volume Move — the common case —
that reads and rewrites every byte instead of performing a metadata-only rename. On a 20 TB archive this
is the difference between minutes and days.

**Fixed**: try `Files.move(..., ATOMIC_MOVE)` first, fall back to copy+delete only on
`AtomicMoveNotSupportedException` (genuinely cross-volume) or a pre-existing destination.

---

## Medium

### M1. Dead Stage-2 partial hash — **fixed**
`HashEngine.computeHash` computed a 64 KB partial digest into a local variable and never read it, then
computed the full hash anyway. That is an extra file open and 64 KB read for every single file, for
nothing. Removed, with a comment explaining that the real FR-025 short-circuit needs a `PARTIAL_HASH`
column to compare against — which arrives with the feature 005 migration.

### M2. Duplicate files are re-hashed on every run → **FIXED in feature 005**
`FILE_HASH_INDEX` has `UNIQUE(SHA256_HASH)`. When a second path holds the same content, `persistHash`'s
insert is rejected, so that path never gets a cached hash and is fully re-read on every subsequent run.
The constraint is doing double duty as both the dedup gate and the path cache, and the two needs
conflict. Feature 005 splits them: a `HASH_CANONICAL(SHA256_HASH → path)` table keeps the atomic dedup
gate, and `FILE_HASH_INDEX` drops the unique constraint so every path caches.

The exception was also logged at WARN for every duplicate, making a normal dedup run look like a fault.
Lowered to DEBUG with an explanation of why it is expected.

### M3. Corrupt-media detection is weaker than FR-012 requires — **FIXED in feature 008**
`FileValidator` calls `Tika.detect(File)`, which classifies by filename and leading magic bytes. A
truncated JPEG, a half-written MP4, or a file with a valid header and corrupt payload — exactly the
"incomplete MOV / damaged PNG" cases FR-012 names — all return `image/jpeg` or `video/mp4` and pass the
gate. Genuine detection needs a decode attempt (`ImageIO.read` / an `ffprobe` probe), which costs real
time per file and should be an explicit, configurable trade-off rather than a silent one.

### M4. `ResourceMonitor` reports disk I/O as a constant 0.0 — **FIXED in feature 008**
`getDiskReadMbSec()` and `getDiskWriteMbSec()` return fields that are declared, never assigned, and
exposed to the dashboard. FR-030 requires disk read/write MB/sec. The JDK has no portable API for this;
it needs OSHI or per-platform native calls.

### M5. `ResourceMonitor.activeThreads` measures the wrong thing — **FIXED in feature 008**
`Thread.activeCount()` counts every thread in the JVM's current thread group — JavaFX, logging, the
checkpoint scheduler — not the scan's worker threads. FR-030 asks for "active worker thread count".
`ThreadPoolExecutor.getActiveCount()` on the engine's pool is the correct source.

### M6. Deprecated CPU API — **fixed**
`getSystemCpuLoad()` has been deprecated since Java 14; switched to `getCpuLoad()`.

### M7. Hardcoded migration list — **fixed** (explicit list, plus comment-safe splitting)
`Database.discoverMigrations()` returned a hand-built `ArrayList` containing one literal filename, so
adding `V002` required editing Java. Classpath directory scanning is unreliable inside a shaded JAR, so
this is now an explicit `MIGRATIONS` constant with a comment directing where to add new versions —
honest about the constraint rather than pretending to discover.

### M8. `ThroughputHistory.addCapped` is O(n) per sample — **FIXED in feature 005** (now `ArrayDeque`)
`ArrayList.remove(0)` shifts 3 600 elements once per second per series once the buffer is full. Harmless
in isolation, but it should be an `ArrayDeque` or a ring buffer before feature 005 starts reading it.

### M9. `JobStatistics` is written under a lock and read without one — **FIXED in feature 008**
`ScanEngine` mutates `jobStatistics` inside `synchronized (jobStatistics)` blocks, but the dashboard and
`CheckpointManager` read the same fields with no synchronisation. The fields are plain `long`s, so reads
are not torn, but they may be arbitrarily stale and mutually inconsistent — a checkpoint can record
`filesProcessed` from one instant and `totalBytesProcessed` from another. Converting the counters to
`LongAdder`, or snapshotting under the same lock, would make checkpoints internally consistent.

---

## Low / process

### P1. No Maven wrapper — **fixed**
There is no `mvnw` / `mvnw.cmd`. Every build depends on whatever Maven happens to be installed — and on
this machine, none is. `mvn -N wrapper:wrapper` would pin the version and make a clean checkout buildable.

### P2. No CI on push or pull request — **fixed**
`.github/workflows/release.yml` was the only workflow and it triggers solely on `v*.*.*` tags. Nothing
compiled the project or ran the 19 test classes on a branch or PR — so a change could only be discovered
to be broken at release time.

**Fixed**: added `.github/workflows/build.yml` running `mvn verify` on every branch push, every PR to
`main`, and on demand. It runs on all three target platforms, with `xvfb` on Linux for the UI tests, and
uploads Surefire and Failsafe reports as artifacts. Paired with the H8 Failsafe fix, this is the first
time the integration tests will run anywhere.

**Verified**: green on ubuntu-latest, macos-latest and windows-latest (run 33519273821).

The first run failed on all three runners, and the cause is worth recording: `mvnw` was committed as
mode `100644`. This repo has `core.filemode=false` (it lives on Windows), so the executable bit was
never recorded and `./mvnw` died with `Permission denied` on every platform. Fixed with
`git update-index --chmod=+x mvnw`. A local build cannot catch this — only CI can, which is a neat
argument for having added it.

### P3. `pom.xml` version is hardcoded to `1.0.0`
Intentional per the 004 plan (CI overrides it with `versions:set`), but it means a locally built
installer always claims 1.0.0. Worth a note in `docs/INSTALL.md`.

### P4. Feature 003 has no implementation commit
`specs/003-installable-builds/` has a full task list and the `package-mac` / `package-win` profiles exist
in `pom.xml`, but there is no `003` commit in the log — the profiles arrived inside the 004 commit. The
tracker should record this so the history is not misleading.

### P5. Tracker is stale
`.specify/memory/tracker.md` still reports `001-media-scanner-core` as the active feature at ~95%, with
"No active blockers. Ready to begin `/speckit-specify`." Features 002, 003 and 004 have shipped since.
Per the constitution's own Tracker Rebuild Trigger this warranted a rebuild; done as part of this pass.

---

---

## Found after the original audit

These surfaced while verifying the work, not during the code read. Each is recorded because the *way*
it was found is the point.

### N1. Job IDs collided after an application restart — **FIXED**
The counter behind `JOB-yyyyMMdd-NNN` is a static `AtomicInteger` starting at 1, so the first job of
every JVM was `JOB-<date>-001`. Restart the application, start a scan on the same calendar day, and it
died immediately on a PRIMARY KEY violation against `JOB_STATISTICS` — before processing a single
file. Present since feature 001.

No test could have caught it: the collision needs a *second JVM*, and every test runs in one. It was
found by clicking Resume in the running application. IDs now carry the time of day.

### N2. `ImageIO.read` does not detect corrupt images — **shaped feature 008**
Probed before designing the FR-012 gate: `ImageIO.read` returns a well-formed 300x300 image for a JPEG
truncated to 40% of its bytes, without throwing. A gate built on "did it decode" would have passed
review and detected nothing. The decoder's warning stream is the real signal.

### N3. `FullPipelineIT` asserted that 20 KB of zeros was valid media — **FIXED**
The fixture wrote zero bytes and called them valid images. The header-only gate accepted that, so for
four features the test asserted the opposite of FR-012. Exposed the moment real validation landed.

### N4. Two integration tests were flaky under load — **FIXED**
`RerunAndResumeIT` and `ScanReportsEndToEndIT` kept their SQLite database inside the JUnit `@TempDir`.
On Windows the WAL and SHM files linger briefly after close while JUnit deletes the directory
immediately, so both passed in isolation and failed under full-suite load. Found by running the suite
three times instead of once.

### N5. JavaFX crashes the JVM on macOS CI — **FIXED**
macOS CI failed with SIGSEGV after 212 passing tests. JavaFX there requires the toolkit to own the
process main thread; starting it inside a Surefire fork takes the JVM down rather than throwing, so the
existing try/catch guard was useless. `FxmlLoadIT` now skips on macOS.

### N6. CI failures were undiagnosable without repo-admin rights — **FIXED**
Job logs, step summaries and artifacts all require admin scope. A macOS-only failure was therefore
invisible from this machine, and diagnosing N5 took three CI round trips. Failing test names, run
totals and forked-JVM dumps are now emitted as **annotations**, which anyone who can see the repo can
read.

### N7. Feature 006 was complete but never committed — **FIXED**
The Cleanup Tool existed only as uncommitted files in the working tree, so it was absent from `main`
and from every release build. It had passing tests and a working screen the whole time. Worth a habit:
a feature is not delivered until it is committed.

## Recommended order of work

1. ~~Install Maven and run `mvn verify`~~ — **done.** 187 tests pass.
2. ~~Feature 005 — Job Reports & History~~ — **implemented.** FR-019, FR-020, FR-023 and FR-031 are
   closed; M1, M2, M7 and M8 were resolved along the way.
3. **Push the branch and confirm `build.yml` goes green** on all three runners. Cheapest open item.
4. **Manual acceptance pass** — drive the GUI, and run against a real archive of 50 000+ files. The
   protocol is in `specs/005-job-reports-history/quickstart.md`. This is the main gap left in 005.
5. **Feature 007 — True Resume (H5).** The largest remaining correctness gap in the product. (006 is taken by the Cleanup Tool, spec'd concurrently in another session.)
6. **M3 / M4 / M5** — corrupt-media detection, disk I/O rates, worker thread count.
7. **M9, P1** — the statistics read/write race, and a committed Maven wrapper.
