# Contract: SQLite Database Schema

**Feature**: MediaScanner Core Engine | **Date**: 2026-06-03

This contract defines the authoritative SQLite schema. All changes require a versioned migration script. No code may assume a schema state other than what is defined here.

---

## Database File Location

- **macOS**: `~/.mediascanner/mediascanner.db`
- **Windows**: `%USERPROFILE%\.mediascanner\mediascanner.db`

Database is global — shared across all jobs on the machine (Q5 clarification).

---

## Schema Version

Tracked via `PRAGMA user_version`. Current version: **1**.

Migration scripts stored at: `src/main/resources/db/migrations/V<NNN>__<description>.sql`

On startup, `Database.java`:
1. Runs `PRAGMA integrity_check`. If not `ok` or file missing: rename corrupt file, create fresh DB, show warning.
2. Reads `PRAGMA user_version`. Applies any migration scripts with version > current.

---

## Tables

### FILE_HASH_INDEX

Global hash cache. One record per unique source file path. Shared across all jobs.

```sql
CREATE TABLE FILE_HASH_INDEX (
    ID                    INTEGER PRIMARY KEY AUTOINCREMENT,
    FILE_PATH             TEXT    NOT NULL,
    FILE_NAME             TEXT    NOT NULL,
    FILE_SIZE             INTEGER NOT NULL,
    FILE_MODIFICATION_TS  TEXT    NOT NULL,   -- ISO 8601, cache invalidation key
    SHA256_HASH           TEXT    NOT NULL,
    MEDIA_DATE            TEXT,               -- ISO 8601, nullable
    CREATED_AT            TEXT    NOT NULL,   -- ISO 8601
    LAST_PROCESSED_AT     TEXT    NOT NULL    -- ISO 8601
);

CREATE UNIQUE INDEX idx_hash_sha256   ON FILE_HASH_INDEX(SHA256_HASH);
CREATE INDEX        idx_hash_filepath ON FILE_HASH_INDEX(FILE_PATH);
```

**Cache invalidation**: The cached SHA256_HASH is valid only when both `FILE_SIZE` and `FILE_MODIFICATION_TS` match the current file on disk. If either differs, delete the record and insert a new one with the fresh hash.

**Invariant**: No two records may share the same `SHA256_HASH` (UNIQUE constraint). A hash collision between two physically different files is treated as a duplicate.

---

### JOB_STATISTICS

One row per job. Updated on every checkpoint. `STATUS` is the authoritative job status.

```sql
CREATE TABLE JOB_STATISTICS (
    JOB_ID                TEXT    PRIMARY KEY,
    STATUS                TEXT    NOT NULL DEFAULT 'RUNNING',
    START_TIME            TEXT    NOT NULL,
    END_TIME              TEXT,               -- nullable until job ends
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
    PEAK_MEMORY_GB        REAL    NOT NULL DEFAULT 0
);
```

**Resume detection query**:
```sql
SELECT * FROM JOB_STATISTICS WHERE STATUS IN ('RUNNING', 'PAUSED') LIMIT 1;
```

**Valid STATUS values**: `RUNNING`, `PAUSED`, `COMPLETED`, `FAILED`, `STOPPED`

---

## WAL Mode

```sql
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;
PRAGMA synchronous = NORMAL;
```

WAL mode is set on every connection open. It enables concurrent reads during active job writes and reduces checkpoint write latency.

---

## Migration Contract

- Every schema change requires a new migration file: `V<NNN>__<description>.sql`
- Migrations are applied in numeric order, exactly once
- No migration may use DROP TABLE or DROP COLUMN without explicit written justification
- Migration scripts are idempotent where possible (`CREATE TABLE IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`)
