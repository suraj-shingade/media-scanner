# Feature Specification: Archive Integrity Verification

**Feature Branch**: `011-archive-integrity`

**Created**: 2026-09-06

**Status**: Draft

**Input**: Prove that an archive MediaScanner built is still intact. Re-check every file recorded as
transferred against the SHA-256 and size captured at transfer time, classify each as intact, missing,
resized, corrupted or unreadable, surface archive files the database has no record of, and write a
durable report. Strictly read-only.

---

## Summary

MediaScanner can already prove what happened *during* a job: every skip, failure and duplicate is written
to a report inside the archive, and the job history keeps it after restart. What it cannot answer is the
question a user actually asks six months later — **"is my archive still intact?"**

That gap matters because the failure modes it covers are silent. Bit rot does not raise an error. A file
truncated by a failed NAS sync looks like a file. Something that reached into the archive and re-encoded a
photo leaves no trace in any log MediaScanner writes. The archive is the user's irreplaceable data, and
today the only evidence it is still correct is that nothing has visibly gone wrong.

The information needed to answer the question is already stored. `HASH_CANONICAL` records, for every file
the application transferred, the SHA-256 it had at transfer time, the path it was written to, and its size
(`V002`, `V003`). This feature reads that record back and checks it against the disk.

Two modes, because the honest answer costs real I/O:

- **Quick** — existence and size only. Seconds on a large archive. Catches deletions and truncations.
- **Deep** — full re-hash of every file. Bandwidth-bound. Catches silent corruption, which is the only
  failure mode that matters and the only one quick mode cannot see.

The distinction is load-bearing and the report always records which mode produced it, because a quick pass
that found nothing is *not* proof of integrity and must never be mistaken for one.

> **Governance note**: this feature is the mirror image of Feature 006. Principle IX governs operations
> that permanently remove user data; this one is bound by the opposite constraint — it must be incapable
> of changing anything at all. FR-069 and FR-070 state that as a requirement rather than leaving it as an
> implementation habit, and Principle V (no deletion as a side effect) applies unchanged.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Verify the Archive Is Still What Was Written (Priority: P1)

A user who ran a migration months ago opens Verify Archive, points it at the archive root, and runs a deep
verification. MediaScanner re-reads every file it recorded transferring, re-computes its SHA-256, and
compares it against the value stored at transfer time. It reports how many files are intact, and lists
every one that is not — with the expected value and the observed value side by side.

**Why this priority**: This is the whole feature, and it is the differentiator. Verified-offload tools
check at transfer time and never again; photo managers do not check at all. Nothing else can tell the user
that a file which still opens is no longer the file that was archived.

**Independent Test**: Build an archive through a normal transfer job. Then, outside the application:
delete one file, truncate a second, overwrite a third with different bytes of exactly the same length, and
add a fourth file the application never saw. Run a deep verification. Expect exactly one MISSING, one
RESIZED, one CORRUPTED, one UNTRACKED, and every other file INTACT.

**Acceptance Scenarios**:

1. **Given** an archive whose files are unchanged since transfer, **When** a deep verification runs,
   **Then** every recorded file is reported INTACT and the report records zero findings.
2. **Given** a file overwritten with different content of identical length, **When** a deep verification
   runs, **Then** it is reported CORRUPTED with both the expected and the observed hash.
3. **Given** the same file, **When** a *quick* verification runs, **Then** it is reported INTACT, and the
   report states that the mode was quick and that quick mode cannot detect content change.
4. **Given** a file deleted from the archive, **When** any verification runs, **Then** it is reported
   MISSING.
5. **Given** a file whose size differs from the recorded size, **When** any verification runs, **Then** it
   is reported RESIZED with the expected and observed sizes.
6. **Given** a verification of any mode over any archive, **When** it completes, **Then** no file in the
   archive has been created, modified, moved, renamed or removed, and the hash index is unchanged.

---

### User Story 2 — Find What the Archive Does Not Account For (Priority: P2)

The same run also reports files sitting in the archive that MediaScanner has no record of writing. These
are not errors — a user may have deliberately dropped files in — but they are the other half of an honest
answer, because a verification that only checks its own records cannot see what it never wrote.

**Why this priority**: Independently useful and independently testable, but the archive is still verifiable
without it.

**Independent Test**: Add two files to a verified archive by hand. Run verification. Expect both listed as
UNTRACKED, the run not marked as failed, and the intact count unchanged.

**Acceptance Scenarios**:

1. **Given** a file in the archive with no matching transfer record, **When** verification runs, **Then**
   it is reported UNTRACKED and does not count as a failure.
2. **Given** an archive containing only untracked files, **When** verification runs, **Then** the run
   completes normally and reports zero verified files.

---

### User Story 3 — Progress, Cancellation and a Durable Record (Priority: P3)

Deep verification of a 20 TB archive is a long job. The user sees files checked, bytes read and findings so
far, can stop it at any point, and keeps whatever the run established up to that moment.

**Why this priority**: Required for the feature to be usable at the scale Principle I mandates, but the
verification logic is correct without it.

**Acceptance Scenarios**:

1. **Given** a verification in progress, **When** the user cancels, **Then** it stops promptly, the results
   already computed are reported, and the report records that the run was cancelled and therefore
   incomplete.
2. **Given** a file that cannot be read, **When** verification reaches it, **Then** it is reported
   UNREADABLE with the specific reason and the run continues.
3. **Given** a completed run, **When** the application restarts, **Then** the report is still readable.

---

### Edge Cases

- An archive root that does not exist, or is empty, completes with zero verified files rather than failing.
- A transfer record whose destination path is null (recorded before `V003`) is skipped as unverifiable and
  counted separately, not reported as MISSING.
- A file locked by another process is UNREADABLE, not CORRUPTED — the distinction between "I could not
  check this" and "this is wrong" must never be blurred.
- Zero-byte files verify normally; a zero-byte file has a well-defined SHA-256.
- Deep verification must not consult the hash cache. A cache hit would return the value recorded at
  transfer time and report every file intact, verifying nothing.

---

## Requirements *(mandatory)*

**Verification**

- **FR-064**: System MUST verify every file recorded as transferred, using the SHA-256, destination path
  and size captured at transfer time.
- **FR-065**: System MUST classify each verified file as exactly one of INTACT, MISSING, RESIZED,
  CORRUPTED or UNREADABLE.
- **FR-066**: System MUST offer a quick mode (existence and size) and a deep mode (full re-hash), and the
  result MUST state which mode produced it.
- **FR-067**: Deep verification MUST compute the digest from the bytes on disk and MUST NOT read from or
  write to the hash cache. A cached digest is the value being tested and cannot also be the test.
- **FR-068**: System MUST identify files present beneath the archive root that have no transfer record,
  report them as UNTRACKED, and MUST NOT treat them as failures.

**Read-only guarantee**

- **FR-069**: Verification MUST NOT create, modify, move, rename or delete any file or directory in the
  archive, under any outcome including cancellation and error.
- **FR-070**: Verification MUST NOT modify the hash index, the canonical claim table, or any recorded
  transfer state.

**Behaviour at scale**

- **FR-071**: System MUST report progress while running and MUST be cancellable; findings already
  established MUST be reported, and the run MUST be marked incomplete.
- **FR-072**: System MUST continue past a file it cannot read, recording the specific reason, and MUST NOT
  abort the run.
- **FR-073**: System MUST hold no more than the findings themselves in memory — an archive of 10M intact
  files MUST NOT accumulate 10M in-memory records.

**Record keeping**

- **FR-074**: System MUST write a durable report for every completed run, listing every non-intact finding
  with its expected and observed values.
- **FR-075**: The report MUST record the mode, the counts by status, the bytes read, the elapsed time, and
  whether the run completed or was cancelled.
- **FR-076**: The report MUST remain readable after the application restarts.

---

## Key Entities

- **IntegrityStatus** — INTACT, MISSING, RESIZED, CORRUPTED, UNREADABLE, UNTRACKED, UNVERIFIABLE.
- **IntegrityFinding** — one file and what was determined about it: path, status, expected and observed
  size, expected and observed hash where computed, and a reason where one applies.
- **IntegrityRun** — one verification pass: root, mode, start time, counts by status, bytes read,
  completion state, and the non-intact findings.

---

## Success Criteria

- **SC-001**: A file overwritten in place with different content of identical length is reported CORRUPTED
  by deep verification and INTACT by quick verification, and the report makes the difference unmistakable.
- **SC-002**: A verification run over an archive leaves the archive byte-identical — provable by comparing
  a full tree hash before and after.
- **SC-003**: Quick verification of 50,000 recorded files completes in under 30 seconds on local storage.
- **SC-004**: A cancelled deep run reports every finding established before the cancellation and is marked
  incomplete.
- **SC-005**: Every non-intact finding in the report carries enough detail to act on without re-running —
  the path, what was expected, and what was found.
