# Research: Job Reports & History

**Feature**: 005-job-reports-history | **Date**: 2026-09-01

Phase 0 design decisions. Every open question from the spec is resolved here before Phase 1 design.

---

## D1: Where does the authoritative per-file record live?

**Decision**: SQLite (`JOB_EVENT`), with the JSON reports as a derived export written at terminal state.

**Rationale**: Principle III makes SQLite the single source of truth for job state, and this is job
state. It also gives the properties the spec's edge cases demand for free: a power-loss crash preserves
every committed event; reports can be regenerated later from the Job History screen if the target archive
was read-only at job end; and streaming a `ResultSet` to a `JsonGenerator` satisfies FR-005-005's
constant-memory requirement without any additional machinery.

**Alternatives considered**:
- *Append to the JSON report as each file is processed.* This is what the existing
  `FileTransfer.appendFailureRecord` does, and it is unusable: it deserialises the entire array, appends
  one element, and rewrites the whole file — O(n²) total I/O — with no synchronisation across the worker
  threads that would be calling it. Rejected, and the method is deleted rather than wired up.
- *Append to a JSON Lines file, convert at the end.* Cheap per write and crash-safe, but it puts a second
  authoritative store next to SQLite, needs its own concurrent-append handling, and leaves orphan files
  when a job is deleted from history. Rejected in favour of one store.

---

## D2: How are events written without slowing the scan?

**Decision**: A bounded in-memory buffer (`JobEventRecorder`) drained by a single-threaded flusher every
500 events or 5 seconds, whichever comes first, inside one transaction per flush.

**Rationale**: Worker threads must never block on the database — Principle I forbids adding a synchronous
round trip to the per-file hot path. A concurrent enqueue is a few nanoseconds. Batching into one
transaction per 500 rows also avoids one `fsync` per file, which under `synchronous = NORMAL` in WAL mode
is the dominant cost of small writes. A single flusher thread means `JOB_EVENT` has exactly one writer and
never contends with hash-index writes for the WAL lock.

The 500/5 s cadence deliberately mirrors the existing `CheckpointManager` (1 000 files / 60 s) so the two
durability stories are easy to reason about together. Events are finer-grained because losing the tail of
a report is more visible to a user than losing 60 s of counters.

**Alternatives considered**:
- *Write each event synchronously.* Simple and maximally durable, but adds a DB round trip per
  non-transferred file. On a source that is 30% skipped files this is a measurable throughput regression
  for no user-visible benefit. Rejected.
- *Unbounded buffer, flush only at the end.* Fastest, but a 10M-file job with millions of skips holds
  every event in memory — the exact failure mode the audit found in the old `Future` list. Rejected.

**Buffer overflow**: if the flusher falls behind and the buffer hits its cap, `record()` blocks the
calling worker rather than dropping events. A dropped event means a silently incomplete report, which is
worse than brief backpressure.

---

## D3: How is the hash index split, and is the dedup gate still safe?

**Decision**: Add `HASH_CANONICAL(SHA256_HASH PRIMARY KEY, CANONICAL_PATH, FIRST_SEEN_AT)`. Move the
`UNIQUE(SHA256_HASH)` constraint there. `FILE_HASH_INDEX` becomes a pure per-path cache with
`UNIQUE(FILE_PATH)` and a new nullable `PARTIAL_HASH` column.

**Rationale**: The current schema uses one constraint for two jobs that conflict. `UNIQUE(SHA256_HASH)`
serialises concurrent duplicate decisions — which is what makes the present check-then-act sequence in
`ScanEngine` accidentally correct under concurrency — but it also *rejects* the insert for any second
path holding that content, so duplicate paths never cache their hash and are fully re-read on every
subsequent run.

Splitting keeps the atomic gate and fixes the cache. The duplicate decision becomes an explicit
`INSERT OR IGNORE INTO HASH_CANONICAL` whose affected-row count says whether this file claimed the hash
(0 rows = a duplicate, and the existing row names the canonical path for the report). That is a single
atomic statement rather than a check-then-act pair, so it is correct by construction rather than by
accident, and it stops logging a constraint violation for every duplicate.

It also makes room for `PARTIAL_HASH`, without which FR-025's Stage 2 short-circuit cannot be implemented
— there is nothing to compare a partial digest against. Implementing Stage 2 is explicitly *not* in this
feature's scope; the column is added so the next feature can.

**Alternatives considered**:
- *Keep one table, drop the unique constraint, dedup with a `SELECT`.* Restores the cache but removes the
  serialisation, reintroducing a genuine check-then-act race where two workers both conclude "not a
  duplicate" and both transfer. Rejected.
- *Leave the schema alone and accept re-hashing.* SC-007 exists precisely because this is expensive:
  re-reading every duplicate on every run over a TB-scale archive is hours of avoidable I/O. Rejected.

---

## D4: Report file naming, given many jobs share one target

**Decision**: Write `<bucket>/<name>-<jobId>.json` per job, and additionally copy the most recent to the
plain `<bucket>/<name>.json`.

**Rationale**: FR-019 names `failure-report.json` literally, and a user looking in `_failures` expects to
find that file. But FR-005-006 requires that a second job against the same archive not destroy the first
job's report. The per-job file is the durable record; the plain name is a convenience pointer to the
latest. Both are cheap.

**Alternatives considered**:
- *Plain name only, overwrite.* Satisfies FR-019 and violates FR-005-006. Rejected.
- *Per-job only.* Satisfies FR-005-006 but leaves `_failures/` with no `failure-report.json`, which
  contradicts the FR's wording and surprises users. Rejected.
- *A single append-only report across all jobs.* Unbounded growth, and deleting a job from history would
  require rewriting it. Rejected.

---

## D5: Charting library

**Decision**: JavaFX's built-in `javafx.scene.chart.LineChart`. For HTML export, a hand-emitted inline
SVG.

**Rationale**: The constitution locks the stack to JavaFX 21 + ControlsFX + MaterialFX; adding a charting
dependency would require a constitution amendment for something JavaFX already does. `LineChart` handles
the live dashboard and the stored-job summary. HTML export must be self-contained (US4 AS-3), which rules
out any JavaScript charting library, and an inline SVG polyline needs no dependency at all.

**Downsampling**: `LineChart` degrades badly past a few thousand points, and an 8-hour job at 1 Hz is
28 800 samples per series. `ThroughputSampleDao` downsamples in SQL to a target of ~600 points per series
by bucketing on time and averaging, which keeps rendering under the 2 s budget (US5 AS-4).

---

## D6: What happens to `SkippedRecord`, `FailureRecord`, and `appendFailureRecord`?

**Decision**: Delete all three. Replace with a single `JobEvent` model.

**Rationale**: `SkippedRecord` and `FailureRecord` are written by nothing and read by nothing today —
they are unreferenced model classes. They also model the same shape (path + reason) with gratuitously
different fields: `FailureRecord` carries a `String timestamp`, `SkippedRecord` carries no timestamp and
a typed enum reason. Carrying two near-identical dead classes forward, plus a third shape for duplicates,
would mean three writers and three readers for one concept.

One `JobEvent` with an `outcome` discriminator serves all three reports, needs one DAO, one streaming
writer parameterised by outcome, and one test suite. `appendFailureRecord` is deleted for the reasons in
D1.

**Risk**: none identified — nothing references these classes outside their own definitions. Confirmed by
search before this decision was recorded.

---

## D7: Report size cap

**Decision**: Stream at most 100 000 entries per report (configurable), then close the array and emit a
`"truncated": true` field with the full count.

**Rationale**: A pathological job could produce millions of skipped entries. A multi-gigabyte JSON file
helps nobody and may fail to write. Truncating *silently* would be worse than either extreme — the audit
flags silent truncation as a recurring anti-pattern — so the notice is inside the file, alongside the
true total, and the complete record stays queryable in SQLite via the Job History screen.

---

## D8: When are reports written for a stopped or interrupted job?

**Decision**: On any terminal state — `COMPLETED` and `STOPPED` both trigger `writeAll`. An `INTERRUPTED`
job (process died) generates its reports on demand from the Job History screen.

**Rationale**: US1 AS-4 requires a stopped job to still produce reports covering what it processed. A
crashed job cannot write anything at the time, but its events are already committed, so on-demand
generation loses nothing. This is also why report generation is a separate service taking a `jobId`
rather than something the engine does inline — it must be callable long after the engine is gone.

---

## Resolved unknowns

| Question | Resolution |
|----------|-----------|
| Authoritative store for per-file outcomes | SQLite `JOB_EVENT` (D1) |
| Avoiding hot-path DB writes | Batched buffer, 500 events / 5 s (D2) |
| Keeping the dedup gate atomic while fixing the cache | `HASH_CANONICAL` split (D3) |
| Multiple jobs, one target archive | Per-job filename plus latest-copy (D4) |
| Charting without a new dependency | JavaFX `LineChart`; inline SVG for HTML (D5) |
| Fate of `SkippedRecord` / `FailureRecord` | Deleted, replaced by `JobEvent` (D6) |
| Unbounded report size | Capped at 100 000 with an in-file truncation notice (D7) |
| Reports for stopped and crashed jobs | Terminal-state write; on-demand for crashed (D8) |
