<!--
SYNC IMPACT REPORT
==================
Version Change: 1.1.0 → 1.2.0 (MINOR — new principle added; no existing principle altered)
Modified Principles:
  - None. Principles I–VIII are unchanged in wording and in numbering.
Added Sections:
  - IX. Destructive Operations Safety — governs any operation whose purpose is to permanently
    remove user data. Introduced by feature 006 (Cleanup Tool), the first capability that deletes
    user files as its primary function rather than as the second half of a confirmed Move.
Updated Sections:
  - FR Traceability Matrix — FR-032 through FR-058 added and mapped
  - Quality Gates — G6 widened from "FR-001 through FR-031" to the full current FR set;
    G7 (Destructive Review) added, requiring an explicit Principle IX check and a protected-media
    survival test for any feature that can permanently remove user data
  - Compliance Review — PRs touching destructive paths must now pass a Check against IX
Updated Templates:
  - .specify/templates/plan-template.md  ✅ No change needed; its Constitution Check gate is derived
    from this file rather than enumerating principles inline
  - .specify/templates/spec-template.md  ✅ No structural changes required
  - .specify/templates/tasks-template.md ✅ No structural changes required
Numbering note:
  - IX is appended after VIII rather than inserted beside the file-handling principles (VI, VII) so
    that existing references to "Principle VIII" in specs and audit documents remain valid.
Deferred TODOs:
  - None remaining — FR-001–FR-022 fully captured from MediaScanner BRD.docx
  - Phase 2 enhancements (SHA-256 dedup, GPS, AI similarity, Watch Folder, Cloud) are explicitly
    deferred per BRD "Future Enhancements" section; no constitution principle covers them yet
-->

# MediaScanner Constitution

## Technology Stack Reference

This stack is locked from the Business Requirements Document and MUST be used for all implementation.
Proposing an alternative requires a constitution amendment with explicit justification.

| Layer | Technology |
|-------|-----------|
| Runtime | Java 21 LTS |
| Desktop UI | JavaFX 21 + ControlsFX + MaterialFX |
| Metadata Extraction | Apache Tika + Metadata Extractor |
| Video Metadata | FFmpeg + FFprobe |
| JSON Processing | Jackson |
| Database | SQLite (via JDBC) |
| Logging | SLF4J + Logback |
| Build | Maven |
| Packaging | jpackage |
| Target OS | Windows 10+, Windows 11, macOS |

## Core Principles

### I. Performance-First Architecture

All code paths that touch file I/O, hashing, or metadata extraction MUST be designed for
maximum throughput from the start. Retrofitting performance onto a correct-but-slow design
is not acceptable.

Non-negotiable rules:
- Parallel worker threads MUST be used for all disk-intensive operations (scan, hash, copy/move).
  Default thread count is `CPU cores × 2`; user override MUST be supported (NFR-003).
- SHA-256 computation MUST leverage parallel worker threads; results MUST be persisted in SQLite
  to avoid redundant recalculation across runs (FR-023, FR-024).
- Smart tiered hashing MUST be applied for large video files: Stage 1 (size + name) →
  Stage 2 (partial block hash) → Stage 3 (full SHA-256) to avoid unnecessary full-file reads
  (FR-025).
- Available RAM MUST be leveraged aggressively for metadata caching, file queueing, and
  destination caching (NFR-004).
- High-Priority Mode MUST be implemented: on Windows request `HIGH_PRIORITY_CLASS`; on macOS
  request increased scheduling priority where permitted (NFR-005).
- Target throughput baselines on reference hardware (16-core CPU, 64 GB RAM, NVMe SSD):
  - Small files:   200–1 000 files/sec
  - Mixed media:   100–500 files/sec
  - Large video:   1–20 GB/sec disk throughput
- Memory and CPU budgets MUST be tracked per-run; no operation may leak resources across
  job boundaries.

Rationale: The system targets 10 M+ file archives and 20+ TB datasets (NFR-001, NFR-002).
Any design that does not account for this scale from day one will require full rewrites.

### II. Context Preservation and Resume Support

Every long-running operation MUST be checkpointable and resumable with zero data loss.

Non-negotiable rules:
- Job state MUST be persisted to JSON every 1 000 files OR every 60 seconds, whichever
  comes first (NFR-006, FR-014). The JSON schema is the canonical checkpoint format:
  `{ "jobId", "status", "sourcePath", "targetPath", "processedFiles", "failedFiles",
  "skippedFiles", "emptyFiles", "smallFiles", "checkpointTime" }`.
- SQLite checkpoint MUST be written within 100 ms of each significant state change.
- On startup, the system MUST detect an interrupted prior run and offer automatic resume
  within 5 seconds (FR-022, NFR resume SLA).
- Users MUST be able to manually Pause (FR-016), Resume (FR-017), and Stop (FR-018) jobs
  at any time. Stop MUST terminate safely with no file corruption.
- Job state MUST be exportable and importable so sessions can be transferred across machines
  (FR-015).
- All processed-file state (path, hash, outcome) MUST be written atomically so a crash
  mid-job never leaves the index in a corrupt or ambiguous state.
- The application MUST continue running in the background while processing (FR-021).
- The development tracker (`.specify/memory/tracker.md`) MUST be updated at each milestone,
  phase boundary, and after each session so work can be resumed without loss of context.
- Agent context files MUST accurately reflect the current implementation state at all times.

Rationale: Users run multi-hour jobs over enormous archives. Any interruption that requires
restarting from scratch is unacceptable. The same discipline applies to development sessions:
losing AI coding context mid-feature costs time and introduces inconsistency.

### III. SQLite as the Single Source of Truth

All persistent job state MUST live in SQLite. No ephemeral in-memory-only state may be used
as the authoritative record for a running or resumable job.

Non-negotiable rules:
- `FILE_HASH_INDEX` table MUST contain: ID (BIGINT), FILE_PATH (TEXT), FILE_NAME (TEXT),
  FILE_SIZE (BIGINT), SHA256_HASH (VARCHAR 64), MEDIA_DATE (TIMESTAMP), CREATED_AT (TIMESTAMP)
  with a UNIQUE index on SHA256_HASH.
- `JOB_STATISTICS` table MUST contain: JOB_ID (VARCHAR), FILES_PROCESSED (BIGINT),
  FILES_FAILED (BIGINT), FILES_SKIPPED (BIGINT), DUPLICATES_FOUND (BIGINT),
  TOTAL_BYTES_PROCESSED (BIGINT), TOTAL_BYTES_MOVED (BIGINT), TOTAL_BYTES_COPIED (BIGINT),
  AVG_MB_PER_SEC (DOUBLE), PEAK_MB_PER_SEC (DOUBLE), AVG_FILES_PER_SEC (DOUBLE),
  PEAK_FILES_PER_SEC (DOUBLE).
- All schema changes MUST be versioned migrations; no destructive ALTER TABLE without a
  migration script.
- Queries against the index MUST use indexed columns; full-table scans on large datasets
  are a constitutional violation requiring explicit justification.

Rationale: SQLite provides atomic writes, crash recovery, and cross-session persistence
without the overhead of a server database. It is the right tool at this scale.

### IV. Observability and Real-Time Monitoring

The system MUST expose live operational metrics at all times during execution. Silent
processing with no feedback is not acceptable.

Non-negotiable rules:
- A real-time progress dashboard MUST display (FR-026):
  total files found, files processed, files remaining, files copied, files moved,
  files skipped, files failed, and duplicate files detected.
- Data transfer statistics MUST display in auto-scaled units (B/KB/MB/GB/TB) for:
  total data processed, total data copied, total data moved, total data skipped,
  and total duplicate data saved (FR-027).
- Throughput MUST be reported as files/sec AND MB/sec (or GB/sec) with rolling averages
  over 5 s, 30 s, and the entire job (FR-028).
- ETA MUST be calculated from current throughput × remaining files + bytes (FR-029).
- Resource utilization MUST be visible during a run: CPU %, memory GB, disk read/write
  MB/sec, and active worker thread count (FR-030).
- Historical throughput metrics (files/sec, MB/sec, CPU %, memory %) MUST be tracked for
  performance troubleshooting and optimization (FR-031).
- End-of-job summary MUST include: total files found/processed/copied/moved/skipped/failed/
  duplicate, total data scanned/copied/moved/skipped/duplicate-saved, peak and average
  files/sec, peak and average MB/sec, peak and average GB/sec, average and peak CPU %,
  average and peak memory, start time, end time, total duration, and total folders created.

Rationale: Users managing TB-scale archives need confidence the job is progressing correctly.
Silent failures or stalled progress discovered hours later are not acceptable outcomes.

### V. Duplicate Handling is a First-Class Feature

Duplicate detection MUST be correct, configurable, and non-destructive by default.
Two distinct duplicate types exist and MUST be handled separately.

Non-negotiable rules:
- **Filename collision** (FR-009): When a file with the same name exists at the target,
  rename with sequential suffix: `IMG001.jpg` → `IMG001(1).jpg` → `IMG001(2).jpg`.
- **Content duplicate** (hash-based, FR-023): SHA-256 determines true duplicates. Three
  configurable policies MUST be supported:
  - Skip (default) — do not write the duplicate.
  - Move to `/_duplicates` bucket.
  - Keep Both — rename with `_DUP_N` suffix.
- Duplicate report MUST track: count, size saved, file locations, and hash values (FR-023).
- The hash index MUST be consulted before any file is written to the target archive.
- No source file MUST ever be deleted as a side effect of duplicate detection.

Rationale: Data loss through silent deduplication is catastrophic for archival use cases.
Correctness of duplicate handling takes precedence over throughput.

### VI. Media Validation and File Quality Gates

Every file MUST pass a quality gate before being transferred. Silent acceptance of empty,
corrupt, or irrelevant files pollutes the archive.

Non-negotiable rules:
- **Empty file detection** (FR-010): Files with size = 0 bytes MUST be skipped, logged,
  and added to the skipped bucket with reason "empty file".
- **Small file detection** (FR-011): Images < 10 KB and videos < 100 KB MUST be skipped
  by default. Thresholds MUST be user-configurable. Reason: "small file".
- **Corrupt media detection** (FR-012): Files that cannot be read as valid media (invalid
  JPEG, damaged PNG, corrupted MP4, incomplete MOV) MUST be skipped and recorded in the
  failure bucket (FR-019) with the specific failure reason.
- **Ignore rules** (FR-013): User-configurable pattern list (default includes `Thumbs.db`,
  `.DS_Store`, `desktop.ini`, `._*`, `.cache`, `.tmp`, `.temp`) MUST be applied before
  any processing.
- **Failure bucket** (FR-019): All failed files MUST be recorded to `/_failures` with a
  `failure-report.json` listing path and reason.
- **Skipped bucket** (FR-020): All skipped files MUST be tracked separately with reasons:
  empty file, small file, unsupported format, ignore rule matched, metadata missing.
- All skipped and failed counts MUST appear in the real-time dashboard and end-of-job summary.

Rationale: Large media collections inevitably contain corrupt, empty, and system files.
Silently transferring them degrades archive quality and wastes storage.

### VII. Folder Organization and Transfer Discipline

File organization MUST follow a deterministic, date-based scheme. The transfer mechanism
MUST be safe, atomic where possible, and user-controlled.

Non-negotiable rules:
- Source directory selection (FR-001) and target directory selection (FR-002) MUST be
  user-provided via the desktop UI; no defaults that silently write to unexpected paths.
- Media type support MUST cover (FR-003):
  - Images: jpg, jpeg, png, gif, webp, bmp, tif, tiff, heic, raw, cr2, nef, arw, dng
  - Videos: mp4, mov, avi, mkv, webm, mts, m4v, 3gp
- Transfer mode MUST be user-selectable (FR-004): Copy (preserve originals) or Move
  (delete originals only after confirmed successful transfer).
- Scanning MUST be fully recursive through all subfolders (FR-005).
- Metadata extraction MUST attempt in priority order (FR-006): (1) Media capture date,
  (2) file creation date, (3) file modified date.
- All dates MUST be normalized to ISO 8601 format (e.g., `2025-04-15T18:22:01`) (FR-007).
- Default folder structure MUST be `/yyyy/MMM`; configurable alternatives MUST include
  `/yyyy/MM`, `/yyyy/MMM/dd`, `/yyyy/MM/dd` (FR-008).

Rationale: Deterministic folder structure is the core user value proposition. Ambiguity in
organization rules or destructive transfers without confirmation are unacceptable outcomes.

### VIII. Development Discipline and Incremental Delivery

Features MUST be built in independently testable, deployable increments. No big-bang
integration at the end of a cycle.

Non-negotiable rules:
- Each user story MUST have a defined independent test before implementation begins.
- TDD is STRONGLY RECOMMENDED (test written → confirmed failing → implement → green).
- No feature branch MUST be merged without passing its defined acceptance scenarios.
- Performance benchmarks MUST be run as part of acceptance for any feature touching
  the I/O or hashing subsystems.
- The development tracker MUST be updated at the end of every session.
  (see Development Tracker Standards below).

Rationale: At this system scale, regressions in throughput or correctness are expensive
to diagnose. Incremental delivery with per-story tests catches problems early.

### IX. Destructive Operations Safety

Any operation whose purpose is to permanently remove user data — files or directories — is a
destructive operation and MUST satisfy every rule below. This principle governs deliberate deletion.
It is distinct from Principle V, which forbids deletion as an unintended *side effect*, and from
Principle VII, which permits deletion only as the second half of a confirmed Move.

Non-negotiable rules:
- **Preview before delete**: the user MUST be shown the complete set of items a destructive operation
  would remove — path, size, and the reason each item qualified — before anything is removed.
  A destructive operation that cannot enumerate its targets in advance MUST NOT run.
- **Explicit confirmation**: removal MUST require an affirmative user action taken *after* the preview.
  The confirmation MUST state the exact item count, the total bytes, and that the action is permanent
  and cannot be undone. Abandoning at the confirmation step MUST leave the disk unmodified.
- **No default destructive scope**: no deletable category, group, or selection may be pre-selected.
  The user MUST opt in to every class of item that will be removed.
- **Media is never deletable**: any file whose *contents* are a recognized image or video is protected
  media and MUST NOT be removed by a destructive operation under any selection the user can make.
- **Contents decide, not names**: classification that determines whether an item is eligible for
  deletion MUST be derived from file contents. A file's name or extension MUST NOT influence the
  verdict, in either direction.
- **Re-verify immediately before acting**: a preview is a snapshot. Each item MUST be re-checked
  immediately before it is removed, and MUST be skipped if it no longer matches the classification it
  was previewed under. The skip MUST be recorded.
- **Never escape the selected tree**: symbolic links, junctions and other reparse points MUST NOT be
  followed, and no item outside the user-selected directory tree may be removed.
- **Refuse dangerous roots**: a drive root, an operating-system directory, or a user profile root MUST
  be refused as a target unless the user supplies an additional explicit override.
- **Continue past individual failures**: an item that cannot be removed — locked, read-only,
  permission-denied, path too long — MUST be recorded with its specific reason and MUST NOT abort the
  remaining work.
- **Cancellable**: analysis MUST be cancellable, and an in-progress removal MUST be stoppable. Items
  already removed stay removed and MUST be reported as such.
- **Durable report that outlives the data**: every run that removed at least one item MUST write a
  persistent report listing each removed path with its size, classification and timestamp, plus every
  skip and every failure with its reason. The report MUST remain readable after the application
  restarts. Once the files are gone, this record is the only remaining evidence of what happened.

Rationale: Every other safeguard in this constitution protects data that still exists — a bad transfer
can be re-run, a corrupt index can be rebuilt, a wrong duplicate policy can be reversed by copying the
file back. Permanent deletion has no such recovery path, and the blast radius is the user's irreplaceable
archive. The cost of a preview step and a re-verification read is trivial against the cost of being
wrong once.

## FR Traceability Matrix

| FR | Description | Governing Principle |
|----|-------------|---------------------|
| FR-001 | Source directory selection | VII |
| FR-002 | Target directory selection | VII |
| FR-003 | Media type support | VII |
| FR-004 | Transfer mode (copy/move) | VII |
| FR-005 | Recursive scanning | VII |
| FR-006 | Metadata extraction priority | VII |
| FR-007 | Date standardization | VII |
| FR-008 | Folder structure creation | VII |
| FR-009 | Filename collision handling | V |
| FR-010 | Empty file detection | VI |
| FR-011 | Small file detection | VI |
| FR-012 | Corrupt media detection | VI |
| FR-013 | Ignore rules | VI |
| FR-014 | Job state persistence (JSON) | II |
| FR-015 | Job import/export | II |
| FR-016 | Pause processing | II |
| FR-017 | Resume processing | II |
| FR-018 | Stop processing | II |
| FR-019 | Failure bucket | VI |
| FR-020 | Skipped bucket | VI |
| FR-021 | Background execution | II |
| FR-022 | Session recovery | II |
| FR-023 | Hash-based duplicate detection | V, III |
| FR-024 | Duplicate index management | III |
| FR-025 | Smart hashing optimization | I |
| FR-026 | Real-time progress dashboard | IV |
| FR-027 | Data transfer statistics | IV |
| FR-028 | Throughput monitoring | IV |
| FR-029 | ETA calculation | IV |
| FR-030 | Resource utilization monitoring | IV |
| FR-031 | Historical throughput graph | IV |
| FR-032 | Recursive discovery of all file types, not only media | IX |
| FR-033 | Content-based classification; filename ignored | IX |
| FR-034 | MIME group assignment | IX |
| FR-035 | Protected-media classification by contents | IX, VI |
| FR-036 | Definite classification outcome for every file | IX, VI |
| FR-037 | Cancellable analysis | IX, II |
| FR-038 | Grouped deletion preview | IX, IV |
| FR-039 | No destructive scope selected by default | IX |
| FR-040 | Preview-before-delete gate | IX |
| FR-041 | Confirmation states scope and irreversibility | IX |
| FR-042 | Abandon at confirmation leaves disk unmodified | IX |
| FR-043 | Permanent deletion, no quarantine or Recycle Bin | IX |
| FR-044 | Re-verify each item immediately before removal | IX |
| FR-045 | Protected media never deleted | IX, V |
| FR-046 | Continue past individual deletion failures | IX, VI |
| FR-047 | No link traversal outside the selected tree | IX |
| FR-048 | Refuse dangerous roots without override | IX |
| FR-049 | Deletion progress reporting and stop | IX, IV |
| FR-050 | Empty directory identification | IX |
| FR-051 | Bottom-up recursive pruning | IX |
| FR-052 | Prune confirmation before removal | IX |
| FR-053 | Selected root directory preserved | IX |
| FR-054 | Any file makes a directory non-empty | IX |
| FR-055 | Deletion report contents | IX, VI |
| FR-056 | Skips and failures recorded with reasons | IX, VI |
| FR-057 | Prune runs recorded in the same report format | IX |
| FR-058 | Reports survive deletion and application restart | IX, III |

FR-001 through FR-031 originate in the MediaScanner BRD. FR-032 through FR-058 were introduced by
feature 006 (Cleanup Tool) and are governed primarily by Principle IX.

## Development Tracker Standards

The file `.specify/memory/tracker.md` is the authoritative development progress record.

### Structure

The tracker MUST contain:
- **Project Status**: one-line current phase and overall completion estimate.
- **Active Feature**: current branch name and spec link.
- **Phase Progress**: checklist of all phases with status (Not Started / In Progress / Done).
- **Session Log**: reverse-chronological list of sessions with date, work done, and next action.
- **Blockers**: any open blockers with owner and target resolution date.
- **Context Snapshot**: current implementation decisions, key file paths, and any non-obvious
  state that would be needed to resume work cold.

### Update Protocol

The tracker MUST be updated:
1. **Before starting a session** — confirm last session's "next action" is still accurate.
2. **At each phase boundary** — mark phase Done, record what was delivered.
3. **When a blocker is identified or resolved** — add or close blocker entry.
4. **Before ending a session** — write Session Log entry with: date, tasks completed,
   decisions made, and the precise next action to resume from.
5. **After running `/speckit-agent-context-update`** — confirm CLAUDE.md reflects tracker state.

### Visibility Commands

- `/speckit-agent-context-update` — sync tracker state into CLAUDE.md for agent context.
- Check tracker before every `/speckit-specify`, `/speckit-plan`, `/speckit-tasks`,
  `/speckit-implement` to ensure no phase is being skipped or re-run unnecessarily.

### Tracker Rebuild Trigger

If the tracker becomes stale (session log > 7 days old with no update, or blockers unreviewed
for > 3 sessions), a full tracker rebuild MUST be performed:
1. Read current code state via `git log --oneline -20` and key source file inspection.
2. Re-derive actual completion percentage from implemented vs planned tasks.
3. Rewrite Context Snapshot to reflect current file paths and implementation decisions.
4. Add a Session Log entry flagging the rebuild with rationale.

## Quality Gates

These gates MUST be satisfied before advancing between phases. Violations require explicit
justification logged in the tracker under Blockers.

| Gate | Condition to Pass |
|------|-------------------|
| **G1: Spec Complete** | All FR sections mapped; no NEEDS CLARIFICATION tokens remaining |
| **G2: Plan Approved** | Constitution Check in plan.md passes; stack locked; performance targets defined |
| **G3: Tasks Ready** | All tasks have phase assignment, story label, and parallelism flag |
| **G4: Story Done** | Acceptance scenarios pass; benchmark within target for I/O stories |
| **G5: Tracker Current** | Session log entry exists for today with next-action defined |
| **G6: BRD Validated** | Every FR in the traceability matrix that is in scope for the feature is mapped to at least one user story in its spec. FR-001–FR-031 (BRD-derived) MUST all be mapped before v1.0; FR-032 and above are mapped by the feature that introduces them |
| **G7: Destructive Review** | Any feature containing a code path that permanently removes user files or directories has an explicit Principle IX check recorded in its plan, and an acceptance test proving that protected media survives a confirmed deletion |

## Governance

This constitution supersedes all other project conventions. Amendments require:

1. A pull request updating this file with a version bump per semantic versioning rules below.
2. The Sync Impact Report (HTML comment at top) updated to reflect what changed and why.
3. All dependent templates verified or updated (see checklist in execution flow above).
4. A tracker Session Log entry recording the amendment decision and rationale.

### Versioning Policy

- **MAJOR** (X.0.0): A principle is removed, renamed with incompatible meaning, or a
  Quality Gate is eliminated.
- **MINOR** (X.Y.0): A new principle or Quality Gate is added, or a section is materially
  expanded.
- **PATCH** (X.Y.Z): Wording clarification, typo fix, or non-semantic refinement.

### Compliance Review

All PRs that touch I/O paths, the hash index, SQLite schema, or the progress monitoring
subsystem MUST pass a Constitution Check against Principles I, III, IV, and V before merge.

All PRs that touch file validation, transfer mode, or folder organization MUST pass a
Constitution Check against Principles VI and VII before merge.

All PRs that add or modify any code path capable of permanently removing a user file or directory
MUST pass a Constitution Check against Principle IX before merge. This review is mandatory regardless
of diff size: a one-line change to a deletion predicate or a classification rule is precisely the
change Principle IX exists to catch. The review MUST confirm, at minimum, that the preview gate is
intact, that protected media cannot be selected, that re-verification still precedes removal, and
that the durable report still records every removal, skip and failure.

For runtime development guidance, refer to the active plan at `specs/[active-feature]/plan.md`
and the tracker at `.specify/memory/tracker.md`.

**Version**: 1.2.0 | **Ratified**: 2026-06-03 | **Last Amended**: 2026-09-01
