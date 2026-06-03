# Contract: Job State JSON Schema

**Feature**: MediaScanner Core Engine | **Date**: 2026-06-03

This contract defines the canonical JSON format for job checkpoint files (FR-014) and portable export/import files (FR-015).

---

## File Location

- **Checkpoint (auto-saved)**: `~/.mediascanner/jobs/<jobId>/checkpoint.json`
- **Export (user-initiated)**: User-chosen path, same schema

---

## Schema

```json
{
  "jobId": "JOB-20260603-001",
  "status": "RUNNING",
  "sourcePath": "/Volumes/Photos",
  "targetPath": "/Archive",
  "transferMode": "COPY",
  "folderPattern": "YYYY_MMM",
  "duplicatePolicy": "SKIP",
  "processedFiles": 150000,
  "failedFiles": 12,
  "skippedFiles": 125,
  "emptyFiles": 23,
  "smallFiles": 55,
  "duplicatesFound": 342,
  "totalBytesProcessed": 536870912000,
  "checkpointTime": "2026-06-03T22:15:44"
}
```

---

## Field Definitions

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `jobId` | string | yes | Format: `JOB-YYYYMMDD-NNN`. Unique per machine per day. |
| `status` | string | yes | One of: `RUNNING`, `PAUSED`, `COMPLETED`, `FAILED`, `STOPPED` |
| `sourcePath` | string | yes | Absolute path to source directory at time of job creation |
| `targetPath` | string | yes | Absolute path to target directory at time of job creation |
| `transferMode` | string | yes | `COPY` or `MOVE` |
| `folderPattern` | string | yes | `YYYY_MM`, `YYYY_MMM`, `YYYY_MMM_DD`, or `YYYY_MM_DD` |
| `duplicatePolicy` | string | yes | `SKIP`, `MOVE_TO_BUCKET`, or `KEEP_BOTH` |
| `processedFiles` | integer | yes | Count of successfully transferred files at checkpoint |
| `failedFiles` | integer | yes | Count of files in failure bucket at checkpoint |
| `skippedFiles` | integer | yes | Count of files in skipped bucket at checkpoint |
| `emptyFiles` | integer | yes | Count of zero-byte files skipped |
| `smallFiles` | integer | yes | Count of below-threshold files skipped |
| `duplicatesFound` | integer | yes | Count of content duplicates detected |
| `totalBytesProcessed` | integer | yes | Total bytes transferred (not skipped) at checkpoint |
| `checkpointTime` | string | yes | ISO 8601 timestamp of when this checkpoint was written |

---

## Write Triggers

The checkpoint file is written (overwritten atomically) when either condition is met:
1. `processedFiles` count has increased by 1,000 since the last write
2. 60 seconds have elapsed since the last write

Both triggers are active simultaneously. Whichever fires first causes the write.

---

## Atomicity

The checkpoint file is written to `<path>.tmp` first, then renamed to `checkpoint.json` via `Files.move(..., ATOMIC_MOVE)` where supported by the OS. This prevents a corrupt checkpoint file if the application crashes mid-write.

---

## Import/Export (FR-015)

An exported file uses the same schema. On import:
1. The `jobId` from the imported file is used to look up the corresponding `JOB_STATISTICS` row in SQLite.
2. If no matching row exists, a new `JOB_STATISTICS` row is created from the imported counters.
3. Resume proceeds from the `processedFiles` count in the imported JSON.

**Portability**: `sourcePath` and `targetPath` in an imported file may not exist on the importing machine. The user must re-configure these paths before resume is possible. The import UI validates path accessibility before enabling the Resume button.

---

## Versioning

The schema does not currently include a version field. If the schema changes in a future release, a `schemaVersion` integer field will be added. Readers MUST treat an absent `schemaVersion` as version 1 (this spec).
