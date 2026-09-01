# Data Model: Job Reports & History

**Feature**: 005-job-reports-history | **Date**: 2026-09-01

Schema version `V002`. All changes are additive except the `FILE_HASH_INDEX` rebuild, which preserves
every existing row and its cached hash.

---

## New tables

### `JOB_EVENT`

One row per non-transferred file outcome. Successfully transferred files are deliberately *not* recorded
here — at 10M files that would be a 10M-row table per job with no reader, and the aggregate counts in
`JOB_STATISTICS` already cover them.

| Column | Type | Null | Description |
|--------|------|------|-------------|
| `ID` | INTEGER PK AUTOINCREMENT | no | Surrogate key |
| `JOB_ID` | TEXT | no | Owning job (`JOB_STATISTICS.JOB_ID`) |
| `OUTCOME` | TEXT | no | `SKIPPED` \| `FAILED` \| `DUPLICATE` |
| `FILE_PATH` | TEXT | no | Absolute source path |
| `FILE_NAME` | TEXT | no | Basename, denormalised for report readability |
| `FILE_SIZE` | INTEGER | no | Bytes; 0 for empty files |
| `REASON` | TEXT | no | `MediaFile.SkipReason` name, or the failure message |
| `SHA256_HASH` | TEXT | yes | Set for `DUPLICATE` only |
| `MATCHED_PATH` | TEXT | yes | Canonical path the duplicate matched (`DUPLICATE` only) |
| `DESTINATION_PATH` | TEXT | yes | Where it was written under Move-to-bucket / Keep-Both policies |
| `RECORDED_AT` | TEXT | no | ISO 8601 instant |

**Indexes**
- `idx_job_event_job_outcome` on `(JOB_ID, OUTCOME)` — the only access pattern the report writers use;
  each report is one indexed range scan streamed straight to disk.
- `idx_job_event_job` on `(JOB_ID)` — supports cascade delete from the Job History screen.

**Rules**
- Rows are written in batches of up to 500 in one transaction (research D2).
- `REASON` for `SKIPPED` is one of the `MediaFile.SkipReason` enum names, so reports stay machine-readable
  and the UI can group by reason.
- Deleting a job from history deletes its events (FR-005-012). No file in the archive is touched.

---

### `JOB_THROUGHPUT_SAMPLE`

One row per second per running job, backing FR-031.

| Column | Type | Null | Description |
|--------|------|------|-------------|
| `ID` | INTEGER PK AUTOINCREMENT | no | Surrogate key |
| `JOB_ID` | TEXT | no | Owning job |
| `SAMPLE_AT` | TEXT | no | ISO 8601 instant |
| `ELAPSED_SECONDS` | INTEGER | no | Seconds since job start; the chart's x-axis |
| `FILES_PER_SEC` | REAL | no | Instantaneous rate at this sample |
| `MB_PER_SEC` | REAL | no | Instantaneous rate at this sample |
| `CPU_PERCENT` | REAL | no | Process/system CPU load, 0–100 |
| `MEMORY_GB` | REAL | no | Heap in use |

**Indexes**
- `idx_throughput_job_elapsed` on `(JOB_ID, ELAPSED_SECONDS)` — ordered read and SQL downsampling.

**Rules**
- Written at 1 Hz by the existing `ResourceMonitor` scheduler, batched on the same flusher as `JOB_EVENT`.
- Read back downsampled: bucket by `ELAPSED_SECONDS / bucketWidth` and average, targeting ~600 points per
  series regardless of job duration (research D5).
- `ELAPSED_SECONDS` is stored rather than derived so the chart does not need the job's start time and
  downsampling is a pure integer division in SQL.

---

### `HASH_CANONICAL`

The atomic duplicate gate, moved out of `FILE_HASH_INDEX`.

| Column | Type | Null | Description |
|--------|------|------|-------------|
| `SHA256_HASH` | TEXT PRIMARY KEY | no | Content hash — the uniqueness constraint that serialises duplicate decisions |
| `CANONICAL_PATH` | TEXT | no | The first path seen holding this content; what duplicates are reported against |
| `FIRST_SEEN_AT` | TEXT | no | ISO 8601 instant |

**Rules**
- A file claims its hash with a single `INSERT OR IGNORE`. One affected row means this file is canonical;
  zero means it is a content duplicate, and the existing row supplies `MATCHED_PATH` for the report.
- This replaces the current check-then-act pair in `ScanEngine` (`findBySha256` followed by a path
  comparison), which is correct today only because the `UNIQUE` constraint happens to serialise it.
- Backfilled during migration from existing `FILE_HASH_INDEX` rows.

---

## Modified table

### `FILE_HASH_INDEX`

Becomes a pure per-path hash cache.

| Change | Before | After | Why |
|--------|--------|-------|-----|
| `UNIQUE(SHA256_HASH)` | present | **removed** | It rejected the insert for every duplicate path, so duplicates were never cached and were fully re-read on every run (FR-005-014, SC-007) |
| `UNIQUE(FILE_PATH)` | absent | **added** | One cache row per path is the actual invariant; `findByFilePath` already assumes it |
| `PARTIAL_HASH` | — | **added**, nullable TEXT | Gives FR-025's Stage 2 short-circuit something to compare against. Populated but not yet consulted — Stage 2 itself is out of scope for this feature |

Everything else is unchanged, and every existing row survives the rebuild with its hash intact, so the
first run after upgrading does not re-hash the archive.

---

## Migration `V002__job_reports.sql`

Applied by `Database.applyMigrations()` once `"V002__job_reports.sql"` is added to the `MIGRATIONS`
constant.

```text
1. CREATE TABLE JOB_EVENT + its two indexes
2. CREATE TABLE JOB_THROUGHPUT_SAMPLE + its index
3. CREATE TABLE HASH_CANONICAL
4. INSERT OR IGNORE INTO HASH_CANONICAL
     SELECT SHA256_HASH, FILE_PATH, CREATED_AT FROM FILE_HASH_INDEX
5. Rebuild FILE_HASH_INDEX:
     CREATE TABLE FILE_HASH_INDEX_NEW (... UNIQUE(FILE_PATH), PARTIAL_HASH TEXT NULL)
     INSERT INTO FILE_HASH_INDEX_NEW SELECT ..., NULL FROM FILE_HASH_INDEX
     DROP TABLE FILE_HASH_INDEX
     ALTER TABLE FILE_HASH_INDEX_NEW RENAME TO FILE_HASH_INDEX
     recreate idx_hash_filepath
6. PRAGMA user_version = 2
```

Step 5 is the standard SQLite table rebuild — SQLite cannot drop an index-backed constraint in place.
Step 4 must run before step 5, since it reads the old table.

**Note for the implementer**: `Database.splitStatements` splits on `;`, so the migration must not contain
a semicolon inside a string literal or a trigger body. Keep every statement top-level.

---

## Entity mapping

| Java type | Table | Notes |
|-----------|-------|-------|
| `model.JobEvent` | `JOB_EVENT` | Replaces the unused `SkippedRecord` and `FailureRecord` (research D6) |
| `model.ThroughputSample` | `JOB_THROUGHPUT_SAMPLE` | New |
| `model.FileHashRecord` | `FILE_HASH_INDEX` | Gains a `partialHash` field |
| *(none)* | `HASH_CANONICAL` | Accessed through `HashIndexDao` as a claim/lookup pair; no model class needed |
| `model.JobStatistics` | `JOB_STATISTICS` | Unchanged; `JobStatisticsDao` gains `findAll()` and `deleteJob()` |

---

## Report document shapes

All three share an envelope so one streaming writer serves them all.

```json
{
  "jobId": "job-20260901-142233",
  "outcome": "SKIPPED",
  "generatedAt": "2026-09-01T14:31:07",
  "sourcePath": "D:\\Photos",
  "targetPath": "E:\\Archive",
  "totalCount": 1284,
  "truncated": false,
  "entries": [
    {
      "filePath": "D:\\Photos\\2019\\IMG_0042.jpg",
      "fileName": "IMG_0042.jpg",
      "fileSize": 4096,
      "reason": "SMALL_FILE",
      "recordedAt": "2026-09-01T14:22:41"
    }
  ]
}
```

The duplicate report adds `sha256Hash`, `matchedPath`, `destinationPath`, and a top-level
`totalBytesSaved`. `totalCount` is the true count even when `truncated` is `true` and `entries` is
capped (research D7).
