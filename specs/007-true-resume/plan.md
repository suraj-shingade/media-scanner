# Implementation Plan: True Resume

**Branch**: `007-true-resume` | **Date**: 2026-09-01 | **Spec**: [spec.md](spec.md)

## Summary

Record where each canonical file was written, and consult that record before transferring. This turns
the archive itself into the resume ledger, so a resumed job costs one stat per file instead of a full
re-copy — and no storage grows with the size of the archive beyond the one row per distinct content
that `HASH_CANONICAL` already keeps.

## Technical Context

**Language/Version**: Java 21 LTS — unchanged.

**Primary Dependencies**: None added. This is two columns, one lookup, and a guard.

**Storage**: SQLite migration `V003` adds `DESTINATION_PATH` and `DESTINATION_SIZE` to
`HASH_CANONICAL`. Additive; no table rebuild.

**Testing**: `RerunAndResumeIT` covers all four behaviours end to end through the real engine. Verified
additionally by a 52 552-file acceptance run.

**Performance Goals**: Determining "already transferred" must cost one stat and zero content reads
(FR-007-009). A re-run of a 50 000-file job must complete in under a quarter of the original
(SC-003) — measured at 16 s against 145 s, a factor of 9.

**Constraints**: Must not weaken FR-009 filename-collision handling, and must not reintroduce the
self-duplicate defect.

## Constitution Check

| Gate | Status | Notes |
|------|--------|-------|
| I. Performance-First | ✅ PASS | Resume detection is one stat per file. No content reads, no new per-file table. |
| II. Context Preservation | ✅ PASS | This is the principle the feature exists to satisfy. |
| III. SQLite single source of truth | ✅ PASS | The ledger lives in `HASH_CANONICAL`; the archive is verified against it, not trusted blindly. |
| V. Duplicate Handling | ✅ PASS | Fixes a defect where a re-run reported every file as its own duplicate. Non-destructive throughout. |
| IX. Destructive Operations Safety | ✅ N/A | Feature 007 deletes nothing. Move mode's delete is unchanged and still follows a verified copy. |

**Result**: No violations.

## Design

### The defect being fixed, precisely

Two bugs were masking each other, which is why neither was visible in the test suite:

1. **Self-duplicate** (introduced in feature 005). `claimCanonical` returns false when the hash is
   already claimed — including when the claimant is *this same path*, as on any second run. The file
   was then routed to `handleDuplicate`, so a re-run reported every file as a duplicate of itself.
2. **Self-collision** (present since feature 001). `resolveCollisionFreePath` runs before anything
   checks whether the file at the destination is this same file, so a re-transfer lands as
   `IMG001(1).jpg`.

Bug 1 hid bug 2: because every file was misrouted as a duplicate, nothing reached the transfer path, so
nothing was re-copied. Fixing bug 1 alone made the archive double on a re-run — which is exactly what
the first run of `RerunAndResumeIT` demonstrated (8 files became 16).

### The mechanism

```
transfer succeeds
  └─> HASH_CANONICAL.DESTINATION_PATH / DESTINATION_SIZE := where it landed

later run meets the same content
  ├─ claimCanonical fails
  ├─ claimant is a different path      -> genuine duplicate, unchanged behaviour
  └─ claimant is this same path
        ├─ recorded destination exists with recorded size -> already transferred, skip
        └─ otherwise                                      -> transfer again (repairs partials)
```

The destination is *recorded* rather than recomputed because recomputation breaks as soon as a
collision suffix has been applied — the suffix is not derivable from the source file.

### Why not a per-file resume table

A `JOB_PROGRESS(job, path)` ledger would be the obvious design and is the wrong one at this scale: 10M
rows per job, ~1 GB, written on the hot path, for information the archive already encodes.
`HASH_CANONICAL` holds one row per *distinct content*, which is bounded by the archive rather than by
the number of files walked.

### UI

`MainController.offerResume` reads the interrupted job's `checkpoint.json` for its source and target,
shows them with the processed count, and on Resume starts the job directly. Either way the job is
marked `INTERRUPTED` so `findActiveJob` stops returning it forever.

## Files

```
src/main/resources/db/migrations/V003__resume_destination.sql   new
src/main/java/com/mediascanner/db/Database.java                 register V003
src/main/java/com/mediascanner/db/HashIndexDao.java             record/find destination
src/main/java/com/mediascanner/db/JobStatisticsDao.java         markInterrupted
src/main/java/com/mediascanner/engine/ScanEngine.java           self-claim guard, skip, record
src/main/java/com/mediascanner/ui/MainController.java           real resume dialog
src/test/java/com/mediascanner/engine/RerunAndResumeIT.java     new
src/test/java/com/mediascanner/db/DatabaseIT.java               V003 assertions
```
