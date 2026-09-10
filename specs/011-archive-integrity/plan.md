# Implementation Plan: Archive Integrity Verification

**Feature**: 011-archive-integrity | **Branch**: `011-archive-integrity` | **Date**: 2026-09-06

**Spec**: [spec.md](./spec.md) — FR-064 through FR-076

---

## Approach

The data needed already exists. `HASH_CANONICAL` holds, for every file the application transferred, the
SHA-256 captured at transfer time (`V002`) and the destination path and size it was written to (`V003`).
Verification is that table read back and compared against the disk. **No schema migration is required**,
which is the main reason this feature is small.

Three pieces:

1. **`IntegrityEngine`** — reads the canonical table, checks each recorded file, sweeps the archive for
   files it has no record of, reports progress, honours cancellation.
2. **`IntegrityRun` / `IntegrityFinding` / `IntegrityStatus`** — the result, structured so that a healthy
   archive costs counters rather than heap.
3. **`IntegrityReportWriter`** — the durable artefact, written for every run including clean ones.

Plus a read-only screen and a `View → Verify Archive…` entry.

---

## Key decisions

### D1 — Do not reuse `HashEngine`

`HashEngine.computeHash` is cache-aware: it consults `FILE_HASH_INDEX` and returns the stored digest when
size and mtime are unchanged, then writes its result back. Both halves defeat verification. A cache hit
returns the value recorded at transfer time — the number being tested — and would report every file
intact while reading nothing; the write-back would overwrite the evidence needed by the next run.

`IntegrityEngine.sha256Of` therefore computes its own digest and does nothing else. This is FR-067, and
it is the single most important line in the feature. It is stated as a requirement rather than left as
an implementation habit precisely because reusing the existing hasher is the obvious thing to do and is
wrong.

### D2 — Count intact files, retain only findings

FR-073. A clean 10M-file archive produces 10M intact results. Holding them would exhaust the heap on the
archives Principle I exists for, while carrying no information — "everything else was fine" is fully
described by a count. `IntegrityRun.record` increments a counter for every status and appends to the
findings list only for non-intact ones.

The one structure that still grows with the archive is the set of accounted-for destination paths, needed
to compute the untracked set. Paths only, no findings. If that ever becomes the limit, the fix is to sort
both sides and merge rather than to hold a set — noted, not built, because it is not the limit today.

### D3 — Two modes, and the mode is part of the result

Quick mode reads no file contents and cannot detect a file altered in place. That is a real limitation
and the most misreadable result the feature can produce, so the mode is recorded on the run, printed on
the screen, and written into the report alongside a sentence stating what it could not check (FR-066).

A cancelled run is never reported as clean, whatever it found (`IntegrityRun.isClean`). It did not look
at everything, and a partial pass presented as proof would be the most damaging outcome here.

### D4 — Untracked is not a failure

Files in the archive with no transfer record are reported but do not count as problems (FR-068). The user
may have put them there deliberately. Counting them as failures would make a healthy archive look
damaged and would train the user to ignore the report. The application's own `_skipped`, `_failures` and
`_duplicates` report directories are excluded for the same reason.

### D5 — Reports live outside the archive

`~/.mediascanner/integrity/`, not inside the archive. The archive may be read-only or on removable media,
and writing into the tree being verified would make the next run report the report as untracked.

---

## Constitution Check

| Principle | Applies | How |
|-----------|---------|-----|
| I — Scale | Yes | Counters not records (D2); streaming DAO read; cancellable |
| III — Concurrency | No | Single-threaded by design. Verification is I/O-bound on one tree and gains nothing from a pool it would have to synchronise |
| IV — Data integrity | **Core** | This feature *is* the integrity check |
| V — No deletion as a side effect | **Yes** | FR-069/FR-070 make read-only a stated requirement, tested by comparing a full tree fingerprint before and after |
| VIII — Testing | Yes | 16 tests; the load-bearing one is same-length content alteration |
| **IX — Destructive Operations** | **N/A by construction** | This engine has no write path. Recorded here explicitly rather than left silent, since the neighbouring feature does |

**G7 Destructive Review**: not applicable — the feature contains no code path that removes or modifies
user files. The inverse property is what needed proving, and
`testVerificationLeavesTheArchiveAndTheIndexUntouched` proves it by fingerprinting the tree either side
of a full deep run.

---

## Tests

| Test | Pins |
|------|------|
| `testDetectsAFileAlteredInPlaceWithoutChangingItsLength` | SC-001 — the reason deep mode exists |
| `testQuickModeCannotSeeContentChangeAndSaysSo` | FR-066 — the limitation is honest |
| `testVerificationLeavesTheArchiveAndTheIndexUntouched` | FR-069, FR-070, SC-002 |
| `testACancelledRunIsNeverReportedAsClean` | FR-071, SC-004 |
| `testARecordWithNoDestinationIsUnverifiableNotMissing` | Edge case — our gap is not their data loss |
| `testReportsArchiveFilesWithNoRecordAsUntrackedNotAsFailures` | FR-068, D4 |
| `testTheApplicationsOwnReportsAreNotReportedAsUntracked` | D4 |
| `IntegrityReportWriterTest` (4) | FR-074–FR-076, SC-005 |

---

## Not in scope

- **Repair.** Verification reports; it does not restore. Re-copying a corrupt file from a source that may
  itself be gone is a separate feature with its own safety questions.
- **Scheduling.** A verification that runs monthly on its own is the obvious follow-up and needs a
  scheduler the application does not have.
- **Persisting runs to the database.** Reports are files today, consistent with the cleanup reports. If
  verification history is wanted on the Job History screen, that is a migration and a screen change.
