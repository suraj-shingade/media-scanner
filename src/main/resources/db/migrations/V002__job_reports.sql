-- V002: Job Reports & History (feature 005)
-- Adds per-file outcome recording, throughput sampling, and splits the duplicate gate
-- out of FILE_HASH_INDEX so duplicate paths can cache their hash.
--
-- NOTE: keep every statement top-level. Database.splitStatements strips line comments and
-- then splits on the statement terminator, so avoid that character inside string literals.

-- 1. Per-file non-transferred outcomes (FR-019, FR-020, FR-023)
CREATE TABLE IF NOT EXISTS JOB_EVENT (
    ID                INTEGER PRIMARY KEY AUTOINCREMENT,
    JOB_ID            TEXT    NOT NULL,
    OUTCOME           TEXT    NOT NULL,
    FILE_PATH         TEXT    NOT NULL,
    FILE_NAME         TEXT    NOT NULL,
    FILE_SIZE         INTEGER NOT NULL DEFAULT 0,
    REASON            TEXT    NOT NULL,
    SHA256_HASH       TEXT,
    MATCHED_PATH      TEXT,
    DESTINATION_PATH  TEXT,
    RECORDED_AT       TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_job_event_job_outcome
    ON JOB_EVENT(JOB_ID, OUTCOME);

CREATE INDEX IF NOT EXISTS idx_job_event_job
    ON JOB_EVENT(JOB_ID);

-- 2. Throughput samples (FR-031)
CREATE TABLE IF NOT EXISTS JOB_THROUGHPUT_SAMPLE (
    ID               INTEGER PRIMARY KEY AUTOINCREMENT,
    JOB_ID           TEXT    NOT NULL,
    SAMPLE_AT        TEXT    NOT NULL,
    ELAPSED_SECONDS  INTEGER NOT NULL,
    FILES_PER_SEC    REAL    NOT NULL DEFAULT 0,
    MB_PER_SEC       REAL    NOT NULL DEFAULT 0,
    CPU_PERCENT      REAL    NOT NULL DEFAULT 0,
    MEMORY_GB        REAL    NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_throughput_job_elapsed
    ON JOB_THROUGHPUT_SAMPLE(JOB_ID, ELAPSED_SECONDS);

-- 3. The atomic duplicate gate, moved out of FILE_HASH_INDEX
CREATE TABLE IF NOT EXISTS HASH_CANONICAL (
    SHA256_HASH    TEXT PRIMARY KEY,
    CANONICAL_PATH TEXT NOT NULL,
    FIRST_SEEN_AT  TEXT NOT NULL
);

-- 4. Backfill the gate from existing index rows. Must run before step 5 rebuilds the source table.
INSERT OR IGNORE INTO HASH_CANONICAL (SHA256_HASH, CANONICAL_PATH, FIRST_SEEN_AT)
    SELECT SHA256_HASH, FILE_PATH, CREATED_AT FROM FILE_HASH_INDEX;

-- 5. Rebuild FILE_HASH_INDEX as a pure per-path cache.
--    SQLite cannot drop an index-backed constraint in place, hence create then copy then rename.
--    Dropping UNIQUE(SHA256_HASH) is what lets a duplicate path cache its own hash instead of
--    being fully re-read on every subsequent run.
CREATE TABLE IF NOT EXISTS FILE_HASH_INDEX_NEW (
    ID                    INTEGER PRIMARY KEY AUTOINCREMENT,
    FILE_PATH             TEXT    NOT NULL UNIQUE,
    FILE_NAME             TEXT    NOT NULL,
    FILE_SIZE             INTEGER NOT NULL,
    FILE_MODIFICATION_TS  TEXT    NOT NULL,
    SHA256_HASH           TEXT    NOT NULL,
    PARTIAL_HASH          TEXT,
    MEDIA_DATE            TEXT,
    CREATED_AT            TEXT    NOT NULL,
    LAST_PROCESSED_AT     TEXT    NOT NULL
);

INSERT OR IGNORE INTO FILE_HASH_INDEX_NEW
    (FILE_PATH, FILE_NAME, FILE_SIZE, FILE_MODIFICATION_TS, SHA256_HASH,
     PARTIAL_HASH, MEDIA_DATE, CREATED_AT, LAST_PROCESSED_AT)
    SELECT FILE_PATH, FILE_NAME, FILE_SIZE, FILE_MODIFICATION_TS, SHA256_HASH,
           NULL, MEDIA_DATE, CREATED_AT, LAST_PROCESSED_AT
      FROM FILE_HASH_INDEX;

DROP TABLE FILE_HASH_INDEX;

ALTER TABLE FILE_HASH_INDEX_NEW RENAME TO FILE_HASH_INDEX;

CREATE INDEX IF NOT EXISTS idx_hash_filepath
    ON FILE_HASH_INDEX(FILE_PATH);

CREATE INDEX IF NOT EXISTS idx_hash_sha256_lookup
    ON FILE_HASH_INDEX(SHA256_HASH);

PRAGMA user_version = 2;
