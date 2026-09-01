# Feature Specification: True Resume

**Feature Branch**: `007-true-resume`

**Created**: 2026-09-01

**Status**: Implemented

**Input**: Make resume real. A job interrupted by a crash, a stop, or a power loss must be completable without re-copying what it already transferred, and without polluting the archive with `IMG001(1).jpg` copies of its own output.

---

## Summary

Resume has been cosmetic since feature 001. `CheckpointManager` faithfully writes `checkpoint.json`
every 1 000 files or 60 seconds, and the application detects an interrupted job on startup — and then
the OK branch of that dialog sets a label reading "Import the job state file to resume." Nothing
resumes. `ScanEngine.start()` has no notion of prior work: it re-walks the whole tree and reprocesses
every file.

Worse, because `resolveCollisionFreePath` runs before anything checks whether the file already at the
destination *is this same file*, a re-run writes a second copy of everything it already transferred
under collision names. On a 50 000-file archive that silently doubles it.

This feature makes resume work, using the archive itself as the ledger: after a successful transfer the
engine records where the file landed, and a later run that meets the same content skips it if that
destination still holds it. No per-file resume table, no new storage proportional to the archive.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Resume After a Crash (Priority: P1)

A user runs an overnight job over 400 000 photos. The machine reboots at 3 a.m. with 280 000 
transferred. They relaunch MediaScanner, are told a job did not finish, and choose Resume. The job
completes the remaining 120 000 files. The 280 000 already in the archive are left exactly as they are.

**Why this priority**: This is the constitution's Principle II — "Every long-running operation MUST be
checkpointable and resumable with zero data loss" — and it is the single largest unmet requirement in
the product. Multi-hour jobs that must restart from scratch are the failure mode the whole checkpoint
subsystem exists to prevent.

**Independent Test**: Transfer 5 of 10 files to an archive. Run the full 10-file job against the same
archive. Verify the archive ends with exactly 10 files, that the first 5 were not rewritten, and that
no `(1)`-suffixed file exists.

**Acceptance Scenarios**:

1. **Given** an archive already holding files from an interrupted run, **When** the job is resumed,
   **Then** those files are recognised and not transferred again.
2. **Given** a resumed job, **When** it completes, **Then** the archive contains each source file
   exactly once, with no collision-suffixed duplicates of its own output.
3. **Given** the application starts and finds a job left in RUNNING or PAUSED state, **When** the user
   is prompted, **Then** the prompt shows the source and target paths and the count already processed.
4. **Given** the user chooses Resume, **When** the scan starts, **Then** it runs against the original
   source and target without further input.
5. **Given** the user declines, **When** the dialog closes, **Then** the paths are restored into the
   form so they can start it later, and the stale job is not offered again on the next launch.
6. **Given** a resumed job, **When** it finishes, **Then** the summary distinguishes files transferred
   this run from files an earlier run had already placed.

---

### User Story 2 — Re-running an Identical Job Is a No-Op (Priority: P1)

A user re-runs the same source against the same archive, either by habit or to pick up newly added
files. Nothing is duplicated, nothing is re-copied, and the run completes quickly.

**Why this priority**: Same code path as US1, and the more common case. Before this feature a second
run reported every file as a duplicate of itself — a defect introduced in feature 005, where the
atomic `HASH_CANONICAL` claim returned "already claimed" without checking whether the claimant was the
same path.

**Independent Test**: Run a job to completion. Run the identical job again. Verify the archive file
count is unchanged, no `(1)` files appear, and no entry in the duplicate report has `filePath` equal to
`matchedPath`.

**Acceptance Scenarios**:

1. **Given** a completed job, **When** the identical job runs again, **Then** no file is copied and the
   archive does not grow.
2. **Given** a second run, **When** the duplicate report is written, **Then** no file is listed as a
   duplicate of itself.
3. **Given** a second run over a source with new files added, **When** it completes, **Then** only the
   new files are transferred.
4. **Given** a second run, **When** it completes, **Then** it is materially faster than the first,
   because no file content is re-read or re-written.

---

### User Story 3 — Partial and Damaged Output Is Repaired (Priority: P2)

A crash left a half-written file in the archive. On resume that file is detected as incomplete and
transferred again, rather than being trusted because a file of the right name exists.

**Why this priority**: A resume that trusts the destination blindly is worse than no resume — it would
leave truncated media in the archive permanently. Lower priority than US1/US2 only because the window
for it is narrow.

**Independent Test**: Transfer a file, truncate it in the archive, then re-run. Verify the file is
rewritten and its content matches the source.

**Acceptance Scenarios**:

1. **Given** a destination file whose size does not match the source, **When** the job runs, **Then**
   the file is transferred again.
2. **Given** a destination file the user deleted from the archive, **When** the job runs, **Then** it
   is transferred again.
3. **Given** a destination that matches in size, **When** the job runs, **Then** it is trusted and not
   re-read.

---

### Edge Cases

- **Genuine filename collisions must still work.** Two different files that resolve to the same
  destination name must still produce `IMG001(1).jpg` (FR-009). Only *self*-collisions are suppressed.
- **Move mode** resumes naturally: transferred files no longer exist in the source, so the remaining
  tree is exactly the remaining work. No destination bookkeeping is consulted.
- **The archive was moved or renamed** between runs. The recorded destination no longer exists, so
  every file is transferred again — correct, if slow.
- **The user changed the folder pattern** between runs. Files land in new paths; the old copies remain.
  This feature does not reorganise an existing archive.
- **Duplicate policy interaction.** A resumed run must not re-report duplicates it already reported,
  and must not count a file as a duplicate of itself.
- **A stale RUNNING job that can never be resumed** (its checkpoint is unreadable) must be marked so it
  stops being offered on every launch.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-007-001**: After a successful transfer the system MUST record the destination path and size for
  that content.
- **FR-007-002**: When the system encounters content it has already claimed, it MUST determine whether
  the claim belongs to the same source path (its own prior work) or a different one (a true duplicate).
- **FR-007-003**: Content whose recorded destination still exists with the recorded size MUST NOT be
  transferred again.
- **FR-007-004**: Content whose recorded destination is missing or the wrong size MUST be transferred
  again.
- **FR-007-005**: Collision renaming MUST apply only to genuinely different content (FR-009), never to
  a file the job itself already transferred.
- **FR-007-006**: The system MUST offer to resume a job found in RUNNING or PAUSED state on startup,
  showing its source, target, and progress.
- **FR-007-007**: A job that is offered and not resumed MUST be marked INTERRUPTED so it is not offered
  again.
- **FR-007-008**: A resumed run MUST report how many files were already present versus transferred.
- **FR-007-009**: Determining that a file was already transferred MUST NOT read the file's contents.

### Key Entities

- **Transferred Copy** — where a given content hash was written and how large it was; the record that
  makes resume possible.

---

## Success Criteria *(mandatory)*

- **SC-001**: A job interrupted at any point can be completed by re-running it, with every source file
  present in the archive exactly once.
- **SC-002**: Re-running a completed job copies zero bytes and does not change the archive.
- **SC-003**: A re-run of a 50 000-file job completes in under a quarter of the original run time.
- **SC-004**: No file is ever reported as a duplicate of itself.
- **SC-005**: Genuine filename collisions between different content are still renamed.
- **SC-006**: Determining "already transferred" costs one filesystem stat per file and no content reads.

---

## Assumptions

- The archive is the source of truth for what was transferred. If a user edits the archive by hand
  between runs, the next run reconciles it.
- Size matching is sufficient to trust a destination. The name, the date-derived folder, and the
  content hash all already agree at that point; requiring a content re-read would defeat FR-007-009.
- Resume re-walks the source tree. The cost of the walk is accepted; the cost of re-reading and
  re-writing file content is not.

## Dependencies

- Feature 005 supplied `HASH_CANONICAL`, which this feature extends with the destination columns.
- Requires schema migration `V003`.
