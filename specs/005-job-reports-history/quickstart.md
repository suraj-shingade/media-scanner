# Quickstart: Job Reports & History

**Feature**: 005-job-reports-history | **Date**: 2026-09-01

Developer guide for working on this feature: how the pieces fit, how to extend them, and the acceptance
protocol to run before calling it done.

---

## Prerequisites

- JDK 21 (`java -version` must report 21.x)
- Maven 3.9+ — **note**: not currently installed on the primary dev machine, and there is no `mvnw`
  wrapper in the repo (see `docs/ENGINEERING-AUDIT.md` P1). Install Maven, or add the wrapper first with
  `mvn -N wrapper:wrapper`
- A source folder of test media. The acceptance protocol below needs one with at least 50 000 files

```bash
mvn clean verify          # compile + run the full suite
mvn test -Dtest=Report*   # just this feature's tests
```

---

## Where things live

```
report/JobEventRecorder   buffers events off the worker hot path, flushes in batches
report/ReportWriter       streams one outcome from SQLite to one JSON file
report/JobReportService   orchestrates the three reports at terminal state
report/SummaryExporter    JSON / CSV / HTML export of a job summary
db/JobEventDao            batch insert, streaming read by outcome
db/ThroughputSampleDao    1 Hz samples in, downsampled series out
ui/JobHistoryController   the history TableView
ui/ThroughputChart        reusable LineChart, used live and for stored jobs
```

The rule that shapes all of it: **workers never touch the database directly for reporting.** They call
`recorder.record(...)`, which is a queue enqueue. One flusher thread owns all writes to `JOB_EVENT` and
`JOB_THROUGHPUT_SAMPLE`. Breaking this rule reintroduces a per-file DB round trip on the hot path and
puts a second writer in contention for the WAL lock.

---

## Cutting a new report type

Say you want a report of files whose metadata date fell back to the filesystem rather than EXIF.

1. **Add the outcome.** Extend `JobEvent.Outcome` with the new constant. No schema change — `OUTCOME` is
   a `TEXT` column, and `idx_job_event_job_outcome` already covers the lookup.
2. **Record it.** From `ScanEngine`, call `recorder.record(...)` at the point the condition is detected.
   Do not add a DB call.
3. **Write it.** Add one line to `JobReportService.writeAll` naming the bucket directory and file. The
   `countByOutcome` guard that suppresses empty reports comes for free.
4. **Test it.** Extend `JobReportServiceIT` — the round-trip harness is parameterised by outcome.

`ReportWriter` needs no change unless your report carries extra per-entry fields. If it does, follow how
`DUPLICATE` adds `sha256Hash` / `matchedPath`.

## Adding an export format

Implement one method on `SummaryExporter` taking the populated summary and a `Path`. Add it to the
`FileChooser` filter list in `SummaryController`. Add a case to `SummaryExporterTest`, which asserts every
format carries identical figures — that shared assertion is the point of the test, so add to it rather
than writing a parallel one.

Whatever the format, it must be **self-contained**: no external URLs, no CDN, no separate asset files.

## Adding a migration

`Database.MIGRATIONS` is an explicit ordered list, not a directory scan — classpath scanning is
unreliable inside the shaded JAR. Add the filename there or the script will never run.

`Database.splitStatements` splits naively on `;`, so a migration must not contain a semicolon inside a
string literal or a trigger body. Keep every statement top-level.

---

## Acceptance protocol

Run all seven before marking the feature done. These map to SC-001 through SC-007 in the spec.

### 1. Reports exist and are accurate (SC-001, SC-002)

Build a source set with: one 0-byte `.jpg`, one 5 KB `.jpg`, one text file named `broken.mp4`, one
`Thumbs.db`, three byte-identical copies of one photo, and ten unique valid media files.

Run a Copy job to an empty target.

- Sixteen valid files reduce to eleven transferred (ten unique + one canonical of the triplicate)
- `_skipped/skipped-report.json` has exactly three entries: `EMPTY_FILE`, `SMALL_FILE`,
  `IGNORE_RULE_MATCHED`
- `_failures/failure-report.json` has exactly one entry naming `broken.mp4`
- `_duplicates/duplicate-report.json` has exactly two entries sharing one SHA-256
- Every count in every report matches the corresponding figure on the summary screen

### 2. Reports scale (SC-003)

Run against a source with at least 100 000 files that will be skipped (e.g. set the image threshold above
their size). Assert report generation completes in under 10 s and that peak heap is comparable to the same
job with 100 skipped files. Watch for any code path that collects entries into a `List`.

### 3. History survives restart (SC-004)

Run three jobs. Restart the application. All three appear in View → Job History, newest first, with
correct dates and counts. Opening the second shows the summary it finished with. The list renders in under
2 s with 200 jobs present.

### 4. Export round-trips (SC-005)

Export a summary as all three formats. Open the CSV in a spreadsheet and the HTML in a browser with the
network disabled — both must render fully with no manual repair.

### 5. Charts compare (SC-006)

Run one job over many small files and one over a few large videos. Open both from history and confirm
their throughput profiles are visibly different and readable side by side.

### 6. No re-hashing (SC-007)

Run a job. Run the identical job again over the unchanged source. The second run must perform zero
full-file reads for hashing — every path, **including duplicate paths**, hits the `FILE_HASH_INDEX`
cache. This is the regression this feature fixes; assert on cache-hit counts, not wall-clock time.

### 7. Interruption is safe (US1 AS-4, research D8)

Start a job over a large source and press Stop partway. Both reports must still be written covering
everything processed up to the stop. Then kill the process outright mid-job, relaunch, and confirm the job
appears in history as Interrupted with its reports generatable on demand.

---

## Gotchas

- **`ScanEngine.processFile` is edited by both US1 and US2.** Sequence those phases or expect a conflict.
- **Ignore-rule skips never reach a worker.** `FileScanner.walkFileTree` filters them out during the walk,
  so recording them (T016) needs a callback on the scanner — not a filter moved into `processFile`, which
  would drag every ignored file through the whole pipeline.
- **Windows paths in JSON and CSV.** Backslashes need escaping in JSON; paths with commas, quotes or
  newlines need quoting in CSV. FR-005-013 is tested, not assumed.
- **`ThroughputHistory` is dead code today.** T038 is the first consumer. Do not assume it is exercised by
  any existing test — it is not.
- **Never let a report failure discard the SQLite record.** If the target archive is read-only or full,
  log it, surface it on the summary screen, and leave the events in place so the report can be regenerated
  from history later.
