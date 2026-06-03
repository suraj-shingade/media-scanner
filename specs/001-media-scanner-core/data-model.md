# Data Model: MediaScanner Core Engine

**Branch**: `001-media-scanner-core` | **Date**: 2026-06-03

---

## Entity Overview

```
Job ──────────────────────────────────────────────────────────┐
│ has many → MediaFile                                         │
│ has one  → CheckpointState (persisted snapshot)             │
│ has one  → JobStatistics (aggregated counters)              │
│ has many → FailureRecord                                    │
│ has many → SkippedRecord                                    │
└─────────────────────────────────────────────────────────────┘

MediaFile ────────────────────────────────────────────────────┐
│ references → FileHashRecord (global, shared across jobs)    │
│ belongs to → Job                                            │
└─────────────────────────────────────────────────────────────┘

FileHashRecord ───────────────────────────────────────────────┐
│ Global index — one record per unique file path              │
│ Shared across ALL jobs on the machine                       │
│ Cache valid only when size + mtime match                    │
└─────────────────────────────────────────────────────────────┘
```

---

## Entities

### Job

Represents one complete scan-and-organize operation from start to finish.

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `jobId` | String | NOT NULL, unique, format `JOB-YYYYMMDD-NNN` | Stable identifier across sessions |
| `sourcePath` | String | NOT NULL | Absolute path to source directory |
| `targetPath` | String | NOT NULL, ≠ sourcePath | Absolute path to target directory |
| `transferMode` | Enum | NOT NULL, values: COPY, MOVE | User-selected transfer mode |
| `folderPattern` | Enum | NOT NULL, default: YYYY_MMM | One of: YYYY_MM, YYYY_MMM, YYYY_MMM_DD, YYYY_MM_DD |
| `duplicatePolicy` | Enum | NOT NULL, default: SKIP | One of: SKIP, MOVE_TO_BUCKET, KEEP_BOTH |
| `status` | Enum | NOT NULL | One of: RUNNING, PAUSED, COMPLETED, FAILED, STOPPED |
| `startTime` | LocalDateTime | NOT NULL | When the job began |
| `endTime` | LocalDateTime | nullable | Set on COMPLETED, FAILED, or STOPPED |
| `imageSizeThresholdKb` | int | NOT NULL, default: 10 | Minimum valid image size in KB |
| `videoSizeThresholdKb` | int | NOT NULL, default: 100 | Minimum valid video size in KB |
| `workerThreadCount` | int | NOT NULL, default: cores × 2 | Parallel worker thread count |
| `highPriorityMode` | boolean | NOT NULL, default: false | Whether OS scheduling priority is elevated |
| `ignoreRules` | List\<IgnoreRule\> | NOT NULL, min 1 (defaults) | Active ignore patterns for this job |

**State transitions**:
```
(new) → RUNNING → PAUSED → RUNNING → COMPLETED
                        → STOPPED
              → FAILED
              → STOPPED
```

**Invariants**:
- `sourcePath` and `targetPath` MUST differ.
- A COMPLETED or FAILED job MUST have a non-null `endTime`.
- Only one job may be in RUNNING state at any time.

---

### MediaFile

Represents a single candidate file discovered during scanning. Ephemeral during processing; outcomes persisted via FailureRecord or SkippedRecord.

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `absolutePath` | String | NOT NULL | Full path at source |
| `fileName` | String | NOT NULL | File name with extension |
| `extension` | String | NOT NULL, lowercase | e.g., `jpg`, `mp4` |
| `fileType` | Enum | NOT NULL | IMAGE or VIDEO |
| `sizeBytes` | long | ≥ 0 | File size at time of processing |
| `modificationTimestamp` | Instant | NOT NULL | Last-modified timestamp from filesystem |
| `validationStatus` | Enum | NOT NULL | PENDING, VALID, SKIPPED, FAILED |
| `skipReason` | Enum | nullable | EMPTY_FILE, SMALL_FILE, UNSUPPORTED_FORMAT, IGNORE_RULE_MATCHED, METADATA_MISSING |
| `failureReason` | String | nullable | Human-readable failure description |
| `extractedDate` | LocalDateTime | nullable | Best available date after extraction |
| `dateSource` | Enum | nullable | EMBEDDED_CAPTURE, FILE_CREATION, FILE_MODIFIED |
| `destinationPath` | String | nullable | Computed target path after date extraction |
| `outcome` | Enum | NOT NULL, default: PENDING | PENDING, TRANSFERRED, SKIPPED, FAILED, DUPLICATE |
| `hashRecord` | FileHashRecord | nullable | Set after hash lookup or computation |

**Validation rules**:
- `sizeBytes == 0` → `validationStatus = SKIPPED`, `skipReason = EMPTY_FILE`
- `fileType == IMAGE && sizeBytes < imageSizeThresholdKb * 1024` → SKIPPED, SMALL_FILE
- `fileType == VIDEO && sizeBytes < videoSizeThresholdKb * 1024` → SKIPPED, SMALL_FILE
- File matches any `IgnoreRule.pattern` → SKIPPED, IGNORE_RULE_MATCHED
- Media cannot be decoded → FAILED (goes to failure bucket)
- All three date sources unavailable → SKIPPED, METADATA_MISSING

---

### FileHashRecord

Entry in the global persistent hash index. **Shared across all jobs on the machine.** Stored in `FILE_HASH_INDEX` SQLite table.

| Field | Type | SQLite Column | Constraints | Description |
|-------|------|---------------|-------------|-------------|
| `id` | long | ID BIGINT | PK, auto-increment | Surrogate key |
| `filePath` | String | FILE_PATH TEXT | NOT NULL, indexed | Absolute source path |
| `fileName` | String | FILE_NAME TEXT | NOT NULL | File name with extension |
| `fileSizeBytes` | long | FILE_SIZE BIGINT | NOT NULL | File size at last hash |
| `fileModificationTs` | Instant | FILE_MODIFICATION_TS TIMESTAMP | NOT NULL | mtime at last hash (cache validity key) |
| `sha256Hash` | String | SHA256_HASH VARCHAR(64) | NOT NULL, UNIQUE index | Hex-encoded SHA-256 |
| `mediaDate` | LocalDateTime | MEDIA_DATE TIMESTAMP | nullable | Extracted capture date |
| `createdAt` | Instant | CREATED_AT TIMESTAMP | NOT NULL | When record was first inserted |
| `lastProcessedAt` | Instant | LAST_PROCESSED_AT TIMESTAMP | NOT NULL | Updated on each cache hit or rehash |

**Cache invalidation rule**: A cached hash is valid only if `currentFile.size == record.fileSizeBytes AND currentFile.mtime == record.fileModificationTs`. If either differs, the file is re-hashed and the record updated.

**Duplicate detection**: Before any transfer, `HashIndexDao.findBySha256(hash)` is called. A non-null result with a different `filePath` means a content duplicate.

---

### JobStatistics

Aggregated counters for a job. Persisted in `JOB_STATISTICS` SQLite table. Updated atomically on each checkpoint.

| Field | Type | SQLite Column | Description |
|-------|------|---------------|-------------|
| `jobId` | String | JOB_ID VARCHAR | FK to Job |
| `filesProcessed` | long | FILES_PROCESSED BIGINT | Successfully transferred |
| `filesFailed` | long | FILES_FAILED BIGINT | In failure bucket |
| `filesSkipped` | long | FILES_SKIPPED BIGINT | In skipped bucket |
| `duplicatesFound` | long | DUPLICATES_FOUND BIGINT | Content duplicates detected |
| `filesCopied` | long | FILES_COPIED BIGINT | Transferred via Copy mode |
| `filesMoved` | long | FILES_MOVED BIGINT | Transferred via Move mode |
| `emptyFilesCount` | long | EMPTY_FILES_COUNT BIGINT | Zero-byte files skipped |
| `smallFilesCount` | long | SMALL_FILES_COUNT BIGINT | Below-threshold files skipped |
| `corruptFilesCount` | long | CORRUPT_FILES_COUNT BIGINT | Unreadable media files |
| `totalBytesProcessed` | long | TOTAL_BYTES_PROCESSED BIGINT | Sum of all transferred file sizes |
| `totalBytesMoved` | long | TOTAL_BYTES_MOVED BIGINT | Move-mode bytes |
| `totalBytesCopied` | long | TOTAL_BYTES_COPIED BIGINT | Copy-mode bytes |
| `totalBytesSkipped` | long | TOTAL_BYTES_SKIPPED BIGINT | Skipped file size total |
| `duplicateByteSavings` | long | DUPLICATE_BYTE_SAVINGS BIGINT | Size saved by not transferring dupes |
| `avgMbPerSec` | double | AVG_MB_PER_SEC DOUBLE | Rolling job average |
| `peakMbPerSec` | double | PEAK_MB_PER_SEC DOUBLE | Maximum observed in job |
| `avgFilesPerSec` | double | AVG_FILES_PER_SEC DOUBLE | Rolling job average |
| `peakFilesPerSec` | double | PEAK_FILES_PER_SEC DOUBLE | Maximum observed in job |
| `avgCpuPercent` | double | AVG_CPU_PERCENT DOUBLE | Rolling average CPU utilization |
| `peakCpuPercent` | double | PEAK_CPU_PERCENT DOUBLE | Maximum observed CPU |
| `avgMemoryGb` | double | AVG_MEMORY_GB DOUBLE | Rolling average memory usage |
| `peakMemoryGb` | double | PEAK_MEMORY_GB DOUBLE | Maximum observed memory |
| `totalFoldersCreated` | long | TOTAL_FOLDERS_CREATED BIGINT | New date folders created at target |

---

### CheckpointState

JSON snapshot of a job. Written every 1,000 files or 60 seconds. Also the format for FR-015 import/export.

| Field | Type | Description |
|-------|------|-------------|
| `jobId` | String | Job identifier |
| `status` | String | RUNNING, PAUSED, etc. |
| `sourcePath` | String | Source directory |
| `targetPath` | String | Target directory |
| `processedFiles` | long | Count at checkpoint time |
| `failedFiles` | long | Count at checkpoint time |
| `skippedFiles` | long | Count at checkpoint time |
| `emptyFiles` | long | Count at checkpoint time |
| `smallFiles` | long | Count at checkpoint time |
| `checkpointTime` | String | ISO 8601 timestamp |

**Stored at**: `~/.mediascanner/jobs/<jobId>/checkpoint.json`

---

### IgnoreRule

A pattern applied during file discovery before any processing.

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `pattern` | String | NOT NULL | Glob pattern (e.g., `Thumbs.db`, `._*`) |
| `source` | Enum | NOT NULL | DEFAULT or USER_DEFINED |

**Default patterns**: `Thumbs.db`, `.DS_Store`, `desktop.ini`, `._*`, `.cache`, `.tmp`, `.temp`

**Matching**: Case-insensitive glob via `java.nio.file.PathMatcher` with `glob:` syntax.

---

### FailureRecord

Written to `/_failures/failure-report.json` at the target directory.

| Field | Type | Description |
|-------|------|-------------|
| `filePath` | String | Absolute source path of failed file |
| `reason` | String | Human-readable failure reason |
| `timestamp` | String | ISO 8601 time of failure |

---

### SkippedRecord

Tracked in memory during the job; written to summary at job end.

| Field | Type | Description |
|-------|------|-------------|
| `filePath` | String | Absolute source path |
| `reason` | Enum | EMPTY_FILE, SMALL_FILE, UNSUPPORTED_FORMAT, IGNORE_RULE_MATCHED, METADATA_MISSING |

---

## SQLite Schema (Full DDL)

```sql
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS FILE_HASH_INDEX (
    ID                    INTEGER PRIMARY KEY AUTOINCREMENT,
    FILE_PATH             TEXT    NOT NULL,
    FILE_NAME             TEXT    NOT NULL,
    FILE_SIZE             INTEGER NOT NULL,
    FILE_MODIFICATION_TS  TEXT    NOT NULL,
    SHA256_HASH           TEXT    NOT NULL,
    MEDIA_DATE            TEXT,
    CREATED_AT            TEXT    NOT NULL,
    LAST_PROCESSED_AT     TEXT    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_hash_sha256
    ON FILE_HASH_INDEX(SHA256_HASH);

CREATE INDEX IF NOT EXISTS idx_hash_filepath
    ON FILE_HASH_INDEX(FILE_PATH);

CREATE TABLE IF NOT EXISTS JOB_STATISTICS (
    JOB_ID                TEXT    PRIMARY KEY,
    FILES_PROCESSED       INTEGER NOT NULL DEFAULT 0,
    FILES_FAILED          INTEGER NOT NULL DEFAULT 0,
    FILES_SKIPPED         INTEGER NOT NULL DEFAULT 0,
    DUPLICATES_FOUND      INTEGER NOT NULL DEFAULT 0,
    FILES_COPIED          INTEGER NOT NULL DEFAULT 0,
    FILES_MOVED           INTEGER NOT NULL DEFAULT 0,
    EMPTY_FILES_COUNT     INTEGER NOT NULL DEFAULT 0,
    SMALL_FILES_COUNT     INTEGER NOT NULL DEFAULT 0,
    CORRUPT_FILES_COUNT   INTEGER NOT NULL DEFAULT 0,
    TOTAL_BYTES_PROCESSED INTEGER NOT NULL DEFAULT 0,
    TOTAL_BYTES_MOVED     INTEGER NOT NULL DEFAULT 0,
    TOTAL_BYTES_COPIED    INTEGER NOT NULL DEFAULT 0,
    TOTAL_BYTES_SKIPPED   INTEGER NOT NULL DEFAULT 0,
    DUPLICATE_BYTE_SAVINGS INTEGER NOT NULL DEFAULT 0,
    TOTAL_FOLDERS_CREATED INTEGER NOT NULL DEFAULT 0,
    AVG_MB_PER_SEC        REAL    NOT NULL DEFAULT 0,
    PEAK_MB_PER_SEC       REAL    NOT NULL DEFAULT 0,
    AVG_FILES_PER_SEC     REAL    NOT NULL DEFAULT 0,
    PEAK_FILES_PER_SEC    REAL    NOT NULL DEFAULT 0,
    AVG_CPU_PERCENT       REAL    NOT NULL DEFAULT 0,
    PEAK_CPU_PERCENT      REAL    NOT NULL DEFAULT 0,
    AVG_MEMORY_GB         REAL    NOT NULL DEFAULT 0,
    PEAK_MEMORY_GB        REAL    NOT NULL DEFAULT 0,
    STATUS                TEXT    NOT NULL DEFAULT 'RUNNING',
    START_TIME            TEXT    NOT NULL,
    END_TIME              TEXT
);
```

**Migration strategy**: `PRAGMA user_version` tracks schema version. `Database.java` runs pending SQL migration scripts from classpath resources on startup. WAL mode enabled for better concurrent read performance during active jobs.
