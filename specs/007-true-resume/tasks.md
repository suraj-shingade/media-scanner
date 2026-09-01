# Tasks: True Resume

**Input**: Design documents from `specs/007-true-resume/`

**Prerequisites**: spec.md ✅ | plan.md ✅

---

## Phase 1: Prove the defect

- [x] T001 Create `src/test/java/com/mediascanner/engine/RerunAndResumeIT.java` with four failing cases: second run is idempotent, resume does not duplicate, genuine collisions still rename, Move mode resumes cleanly
- [x] T002 Confirm the failure. First observation: every file on a second run is reported as a duplicate **of itself** — a defect introduced by feature 005's atomic claim, which did not check whether the claimant was the same path

---

## Phase 2: Schema

- [x] T003 Create `src/main/resources/db/migrations/V003__resume_destination.sql` adding `DESTINATION_PATH` and `DESTINATION_SIZE` to `HASH_CANONICAL` and setting `user_version = 3`
- [x] T004 Register `V003__resume_destination.sql` in `Database.MIGRATIONS`
- [x] T005 Add `recordCanonicalDestination`, `findCanonicalDestination` and the `TransferredCopy` record to `HashIndexDao`

---

## Phase 3: Engine

- [x] T006 Guard the canonical claim in `ScanEngine.processFile`: a failed claim held by *this same path* is our own prior work, not a duplicate
- [x] T007 Confirm this alone makes the archive **double** on a re-run (8 files → 16) — bug 1 had been masking bug 2
- [x] T008 Add `alreadyTransferred(mediaFile, hash)`: one stat against the recorded destination, no content read (FR-007-009)
- [x] T009 Add `countAsAlreadyPresent`: counts toward processed so progress and ETA stay meaningful across a resume, but not toward copied or moved
- [x] T010 Record the destination after every successful transfer in `performTransfer`
- [x] T011 Expose `getFilesAlreadyPresent()` so a resumed run can report what it saved (FR-007-008)

---

## Phase 4: UI

- [x] T012 Add `JobStatisticsDao.markInterrupted` so a stale RUNNING job stops being offered forever (FR-007-007)
- [x] T013 Rewrite `MainController.offerResume`: read the interrupted job's `checkpoint.json`, show source/target/progress, and on Resume start the job directly. Replaces the dead-end label that said "Import the job state file to resume."

---

## Phase 5: Verification

- [x] T014 All four `RerunAndResumeIT` cases pass
- [x] T015 Update `DatabaseIT` and `MigrationV002IT` for `user_version = 3`; add assertions for the new columns
- [x] T016 Full suite green — 192 tests
- [x] T017 Acceptance run against a 52 552-file corpus (49 700 unique, 2 300 duplicates, 502 skipped, 50 failed). Pass 1: 145 s, 343 files/sec, 36 date folders. Pass 2: 16 s, **zero bytes copied**, archive unchanged, no self-copies. Peak heap 477 MB
- [ ] T018 Drive the resume dialog by hand in the GUI — the engine path is covered by tests and the scale run, but the new dialog itself has only been compile- and FXML-checked

---

## Notes

The two defects masking each other is the reason this went unnoticed through five features. A test that
only ever runs a job **once** cannot see either of them. `RerunAndResumeIT` runs every scenario twice,
which is what made both visible.
