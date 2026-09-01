-- V004: Validation & Observability Hardening (feature 008)
-- Persists peak disk throughput so the end-of-job summary can report it (FR-008-008).
-- These were the two FR-030 metrics previously displayed as hardcoded zeros.
--
-- NOTE: keep every statement top-level. Database.splitStatements strips line comments and then
-- splits on the statement terminator, so avoid that character inside string literals.

ALTER TABLE JOB_STATISTICS ADD COLUMN PEAK_DISK_READ_MB_SEC REAL NOT NULL DEFAULT 0;

ALTER TABLE JOB_STATISTICS ADD COLUMN PEAK_DISK_WRITE_MB_SEC REAL NOT NULL DEFAULT 0;

PRAGMA user_version = 4;
