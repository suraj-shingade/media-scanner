# Feature Specification: Job Reports & History

**Feature Branch**: `005-job-reports-history`

**Created**: 2026-09-01

**Status**: Draft

**Input**: Close the four FRs that are counted but never produced — FR-019 (failure bucket), FR-020 (skipped bucket), FR-023 (duplicate report), FR-031 (historical throughput) — and give every completed job a durable, reviewable record.

---

## Summary

MediaScanner currently processes a job, shows live counters, displays a summary screen, and then forgets
almost everything. The aggregate numbers survive in `JOB_STATISTICS`, but the per-file detail behind them
does not: a user who sees "1 284 skipped, 37 failed, 903 duplicates" has no way to learn *which* files
those were or why. The constitution requires all three reports on disk (FR-019, FR-020, FR-023) and a
historical throughput record (FR-031); none of the four exists today.

This feature makes every job leave an auditable trail. Per-file outcomes are recorded in SQLite as the
job runs, three JSON reports are written into the target archive at job end, past jobs become browsable
from a Job History screen, any job summary can be exported as JSON, CSV or HTML, and throughput samples
are captured and charted both live and after the fact.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Failure and Skipped Reports on Disk (Priority: P1)

After a job completes, a user opens the target archive and finds `_failures/failure-report.json` and
`_skipped/skipped-report.json`, each listing every affected file with its full source path, its size, and
the specific reason it was not transferred. They can act on the list — repair the corrupt originals,
lower a size threshold, adjust an ignore pattern — and re-run.

**Why this priority**: FR-019 and FR-020 are unambiguous constitutional requirements (Principle VI) that
are entirely unimplemented. Without them a skipped or failed file is invisible: the user is told a count
and nothing else. Every other story in this feature builds on the same per-file record.

**Independent Test**: Build a source set containing one 0-byte `.jpg`, one 5 KB `.jpg`, one file with a
`.mp4` extension whose contents are text **and which exceeds the 100 KB video size threshold**, one
`Thumbs.db`, and ten valid media files. Run a Copy job. Verify ten files land in the archive; verify
`_skipped/skipped-report.json` contains entries with reasons `EMPTY_FILE`, `SMALL_FILE` and
`IGNORE_RULE_MATCHED`; verify `_failures/failure-report.json` contains exactly one entry naming the
unreadable `.mp4` and its reason.

> The bogus `.mp4` must clear the size threshold on purpose: the small-file gate runs **before** the
> corrupt-media gate, so a short invalid file is correctly reported as `SMALL_FILE` rather than as a
> failure. Verified against the engine in `ScanReportsEndToEndIT`.

**Acceptance Scenarios**:

1. **Given** a job that skipped at least one file, **When** the job reaches a terminal state, **Then**
   `_skipped/skipped-report.json` exists at the target root and contains one entry per skipped file with
   source path, file size, skip reason, and timestamp.
2. **Given** a job that failed at least one file, **When** the job reaches a terminal state, **Then**
   `_failures/failure-report.json` exists at the target root and contains one entry per failed file with
   source path, file size, the specific failure reason, and timestamp.
3. **Given** a job that skipped and failed nothing, **When** the job completes, **Then** no empty report
   file and no empty bucket directory are created.
4. **Given** a job is stopped by the user partway through, **When** it terminates, **Then** both reports
   are still written covering everything processed up to the stop.
5. **Given** a job processes 100 000 files of which 20 000 are skipped, **When** the reports are written,
   **Then** report generation completes in under 10 seconds and total memory does not grow with the
   number of entries.
6. **Given** a target archive already containing reports from an earlier job, **When** a new job writes
   its reports, **Then** the earlier reports are preserved under their own job identifier rather than
   overwritten.

---

### User Story 2 — Duplicate Report (Priority: P1)

A user who ran a job with the default Skip duplicate policy wants to confirm what was deduplicated before
trusting it. They open `_duplicates/duplicate-report.json` and see every duplicate: the file that was
skipped, its SHA-256, its size, and the path of the already-indexed file it matched — plus a total of
bytes saved.

**Why this priority**: FR-023 explicitly requires the report to track "count, size saved, file locations,
and hash values". Count and size saved exist; locations and hashes do not. Deduplication that a user
cannot audit is deduplication a user cannot safely trust, and Principle V makes non-destructive,
reviewable duplicate handling a first-class requirement.

**Independent Test**: Create a source set with three byte-identical copies of one photo under different
names in different subfolders, plus five unique photos. Run a Copy job with policy Skip. Verify six files
land in the archive; verify `_duplicates/duplicate-report.json` lists exactly two entries, each carrying
the same SHA-256, each naming the canonical retained path, and a `totalBytesSaved` equal to twice the
photo's size.

**Acceptance Scenarios**:

1. **Given** a job detected content duplicates, **When** it completes, **Then**
   `_duplicates/duplicate-report.json` exists listing each duplicate with source path, size, SHA-256, and
   the canonical path it matched.
2. **Given** the report is written, **When** the user reads its header, **Then** it states total duplicate
   count and total bytes saved, and those match the values shown on the summary screen.
3. **Given** duplicate policy is Move to bucket, **When** the job completes, **Then** the report records
   both the original source path and the `_duplicates` destination path for each file.
4. **Given** duplicate policy is Keep Both, **When** the job completes, **Then** the report records the
   `_DUP_N` name each duplicate was written under.
5. **Given** a job detected no duplicates, **When** it completes, **Then** no duplicate report is written.

---

### User Story 3 — Job History Screen (Priority: P2)

A user opens View → Job History and sees every job the application has ever run, newest first, with its
date, source and target, status, and headline counts. Selecting one opens the full summary for that job,
including its reports and its throughput chart.

**Why this priority**: `JOB_STATISTICS` already persists every job, but nothing reads it back — the
summary screen only ever shows the job that just ran. Restarting the app discards all visible history.
This story turns data the system already stores into something a user can reach.

**Independent Test**: Run three jobs with different sources. Restart the application. Open View → Job
History and verify all three appear, newest first, with correct dates and counts. Select the second and
verify its summary matches what was shown when that job finished.

**Acceptance Scenarios**:

1. **Given** at least one job has run, **When** the user opens View → Job History, **Then** a list of all
   jobs appears sorted newest first with date, source path, target path, status, and files processed.
2. **Given** the history list is shown, **When** the user selects a job, **Then** the full summary for
   that job opens, populated from stored data rather than live engine state.
3. **Given** the application was restarted since a job ran, **When** the user opens Job History, **Then**
   that job is still listed with all of its data intact.
4. **Given** a job is currently running, **When** the user opens Job History, **Then** it appears with
   status Running and its counts update as the job progresses.
5. **Given** more than 200 jobs exist, **When** the history opens, **Then** the list renders within 2
   seconds.
6. **Given** the user selects a job and chooses Delete, **When** they confirm, **Then** that job's
   statistics and per-file records are removed and the list refreshes — and no file in the archive is
   touched.

---

### User Story 4 — Export Job Summary (Priority: P2)

A user who needs to hand a migration record to someone else — or keep one outside the app — exports any
job's summary as JSON, CSV, or a self-contained HTML page containing every figure the constitution's
end-of-job summary requires.

**Why this priority**: The end-of-job summary defined in Principle IV is comprehensive, and today it is
only ever visible on screen. Archival and TB-scale migration work routinely needs that record preserved
and shareable. It depends on US3's stored-summary reader.

**Independent Test**: Complete a job, open its summary, export as each of the three formats, and verify
each file contains the same figures shown on screen — file counts, byte totals, peak and average
throughput, CPU and memory, start/end time, duration, folders created.

**Acceptance Scenarios**:

1. **Given** a job summary is open, **When** the user chooses Export → JSON, **Then** a save dialog
   appears and the written file contains every summary field with ISO 8601 timestamps.
2. **Given** a job summary is open, **When** the user chooses Export → CSV, **Then** the written file has
   a header row and one row of values, openable in a spreadsheet application.
3. **Given** a job summary is open, **When** the user chooses Export → HTML, **Then** the written file
   opens in a browser with no external assets and renders every summary figure.
4. **Given** an export target path is not writable, **When** the user confirms the save, **Then** a clear
   error is shown and the application remains usable.

---

### User Story 5 — Historical Throughput Chart (Priority: P3)

A user diagnosing why one job ran at a third the speed of another opens either job's summary and compares
their throughput charts — files/sec, MB/sec, CPU % and memory over the life of the job — and can see the
stall.

**Why this priority**: FR-031 requires historical throughput tracking for exactly this purpose, and
`ThroughputHistory` is already written and correct but wired to nothing. Lowest priority because it is
diagnostic rather than corrective, and the reports in US1–US2 deliver more immediate user value.

**Independent Test**: Run a job over a source containing a mix of many small files and a few very large
videos. Open the summary and verify the chart shows a high files/sec region and a high MB/sec region that
do not coincide. Restart the app, reopen the same job from history, and verify the chart is unchanged.

**Acceptance Scenarios**:

1. **Given** a job is running, **When** the user views the dashboard, **Then** a live chart plots
   files/sec and MB/sec over elapsed time and updates at least once per second.
2. **Given** a job has completed, **When** the user opens its summary, **Then** the chart renders the
   job's full recorded history.
3. **Given** the application was restarted, **When** the user opens a past job from history, **Then** its
   throughput chart renders from stored samples.
4. **Given** a job ran for eight hours, **When** its chart is rendered, **Then** samples are aggregated so
   the chart renders in under 2 seconds and memory stays bounded.
5. **Given** a job ran for under 5 seconds, **When** its summary opens, **Then** the chart area shows an
   explanatory message rather than an empty axis.

---

### Edge Cases

- **Target archive is read-only or full when reports are written.** The job's own transfers have already
  succeeded; report writing must fail loudly in the log and on the summary screen without discarding the
  SQLite record, so the reports can be regenerated later from the Job History screen.
- **A job is killed by power loss.** Per-file records already committed to SQLite survive. On next launch
  the job appears in history with status Interrupted, and its reports can be generated on demand.
- **The same target archive is used by many jobs.** Reports are per-job and must not overwrite each
  other; report filenames carry the job identifier, with the most recent also available under the plain
  name for discoverability.
- **A source path contains characters that are invalid in JSON or CSV.** Paths must be escaped correctly,
  including Windows backslashes, non-ASCII filenames, and embedded quotes and newlines.
- **Millions of skipped files.** Reports must stream from SQLite rather than materialise a list in memory,
  and very large reports should be capped with an explicit truncation notice inside the file rather than
  silently cut off.
- **The user deletes a job from history while it is running.** Deletion must be refused with a clear
  message.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-005-001**: The system MUST record every non-transferred file outcome (skipped, failed, duplicate)
  to persistent storage as the job runs, with source path, file name, size, outcome, reason, and
  timestamp.
- **FR-005-002**: The system MUST write `_skipped/skipped-report.json` at the target root when a job
  reaches a terminal state and at least one file was skipped (FR-020).
- **FR-005-003**: The system MUST write `_failures/failure-report.json` at the target root when a job
  reaches a terminal state and at least one file failed (FR-019).
- **FR-005-004**: The system MUST write `_duplicates/duplicate-report.json` at the target root when a job
  reaches a terminal state and at least one content duplicate was detected, including each duplicate's
  SHA-256 and the canonical path it matched (FR-023).
- **FR-005-005**: Report generation MUST stream from persistent storage and MUST NOT hold all entries in
  memory simultaneously.
- **FR-005-006**: Reports from different jobs against the same target MUST NOT overwrite one another.
- **FR-005-007**: The system MUST record throughput samples (files/sec, MB/sec, CPU %, memory) at least
  once per second for the duration of a job, and persist them against the job (FR-031).
- **FR-005-008**: The system MUST provide a Job History view listing all recorded jobs newest first, with
  date, source, target, status, and headline counts.
- **FR-005-009**: The system MUST allow any recorded job's full summary to be reopened from history,
  populated entirely from stored data.
- **FR-005-010**: The system MUST allow any job summary to be exported as JSON, CSV, or self-contained
  HTML.
- **FR-005-011**: The system MUST render a throughput chart for a running job and for any recorded job.
- **FR-005-012**: The system MUST allow a recorded job to be deleted from history, removing its stored
  records without touching any file in the archive, and MUST refuse to delete a running job.
- **FR-005-013**: Every path written to a report MUST be correctly escaped for its format.
- **FR-005-014**: A file whose hash is already claimed by another path MUST still have its own hash cached
  so it is not re-read on subsequent runs (resolves the re-hash-every-run defect in the current index).

### Key Entities

- **Job Event** — one non-transferred file outcome within a job: which file, how big, what happened, why,
  when, and for duplicates the matched canonical path and hash.
- **Throughput Sample** — one point-in-time reading of files/sec, MB/sec, CPU % and memory for a job.
- **Job Summary** — the complete end-of-job record defined by Principle IV, reconstructable for any past
  job from stored statistics and samples.
- **Report** — a JSON document written into the target archive, derived entirely from Job Events.
- **Hash Canonical** — the single retained path for a given SHA-256, separating the duplicate gate from
  the per-path hash cache.

---

## Success Criteria *(mandatory)*

- **SC-001**: After any job that skips, fails, or deduplicates at least one file, a user can determine the
  full source path and specific reason for every such file without consulting application logs.
- **SC-002**: 100% of skipped, failed, and duplicate counts shown on the summary screen are reconcilable
  against the corresponding report entry counts.
- **SC-003**: Reports for a job with 100 000 non-transferred files are written in under 10 seconds with no
  growth in peak memory relative to a job with 100.
- **SC-004**: A user can retrieve the complete summary of any job run in the last 200 jobs after
  restarting the application, in under 2 seconds.
- **SC-005**: A job summary can be exported and opened in a spreadsheet or browser with no manual repair.
- **SC-006**: For any two completed jobs, a user can visually compare their throughput profiles without
  leaving the application.
- **SC-007**: Re-running an identical job over an unchanged source performs zero full-file re-reads for
  files already in the hash index, including duplicates.

---

## Assumptions

- Reports are written at terminal state (completed or stopped), not continuously — per-file durability is
  provided by SQLite, and the JSON files are a derived export.
- Bounded report size: entries beyond a configurable cap (default 100 000) are omitted with an explicit
  truncation notice in the file; the complete record remains queryable in SQLite.
- Throughput samples are stored at 1 Hz and downsampled for display; retention is bounded per job.
- The Job History screen reuses the existing summary view rather than introducing a second summary layout.
- No change to how files are selected, validated, organised, or transferred — this feature only observes
  and reports on work the engine already does.

## Dependencies

- Feature 001 (core engine) — supplies the outcomes being recorded.
- Feature 002 (menu bar) — supplies the View and Tools menus this feature adds items to.
- Requires a schema migration (`V002`), which requires the migration list in `Database` to be extended.
