# Implementation Plan: Cleanup Tool

**Branch**: `006-cleanup-tool` | **Spec**: [spec.md](./spec.md) | **Date**: 2026-09-01

## Summary

A standalone Cleanup screen that walks a user-chosen directory, classifies every file by its **contents**,
presents a grouped preview, and — only after explicit confirmation — permanently deletes the selected
groups and prunes the empty folders left behind. The destructive path is deliberately kept off the scan
engine: no `ScanEngine`, no `HASH_CANONICAL`, no checkpoint, no DB writes.

## Technical Context

| Field | Value |
|-------|-------|
| Language | Java 21 LTS |
| UI | JavaFX 21 (FXML, matching existing screens) |
| Content detection | Apache Tika (already a dependency) |
| JSON | Jackson streaming (`JsonGenerator`), matching `ReportWriter` |
| Storage | None. Reports are files under `~/.mediascanner/cleanup/` |
| Testing | JUnit 5 + AssertJ; `*Test` unit, `*IT` integration via Failsafe |

## Constitution Check

*GATE: must pass before Phase 0. Re-checked after design.*

### Principle IX — Destructive Operations Safety (primary)

| Rule | How this design satisfies it | Enforced by |
|------|------------------------------|-------------|
| Preview before delete | `CleanupEngine.analyze()` returns the full candidate list; `delete()` accepts only a list produced by `analyze()` | `CleanupEngineIT.deleteRequiresAnalysis` |
| Explicit confirmation | Controller shows a modal stating count, bytes and irreversibility; `delete()` is unreachable from the UI without it | `CleanupControllerTest` |
| No default destructive scope | Every group checkbox is created unselected; `selectedGroups` starts empty | `CleanupControllerTest.noGroupSelectedByDefault` |
| Media never deletable | `MimeGroup.PROTECTED_MEDIA` has `deletable=false`; `delete()` re-asserts per file and throws on violation | `CleanupEngineIT.protectedMediaSurvivesConfirmedDeletion` (**G7 test**) |
| Contents decide, not names | `ContentClassifier` reads bytes only; no filename is passed to Tika | `ContentClassifierTest.renamedFilesClassifyByContent` |
| Re-verify before acting | `delete()` re-classifies each candidate immediately before removal | `CleanupEngineIT.reverifySkipsChangedFile` |
| Never escape the tree | Walk uses `NOFOLLOW_LINKS`; never recurses into a link | `CleanupScannerTest.doesNotFollowLinks` |
| Refuse dangerous roots | `DangerousRoots.check()` runs before analysis | `DangerousRootsTest` |
| Continue past failures | Per-file try/catch records a `FAILED` outcome and continues | `CleanupEngineIT.continuesPastUndeletableFile` |
| Cancellable | `AtomicBoolean` cancel flag checked per file | `CleanupEngineIT.analysisIsCancellable` |
| Durable report | `CleanupReportWriter` writes before the run returns | `CleanupReportWriterTest` |

### Other principles

- **I (Performance)**: analysis streams; it never materialises the tree into a list before classifying.
  Classification reads a bounded prefix of each file, not the whole file.
- **III (SQLite source of truth)**: not engaged. Cleanup writes no job state, so there is nothing for
  SQLite to be the truth of. The report file is the record, per IX.
- **VI (Validation gates)**: reuses the *idea* of a definite outcome per file (FR-036) but not
  `FileValidator`, whose gates are about archive-worthiness, not deletability.
- **VII (Transfer discipline)**: untouched. Cleanup never writes into an archive.

**Result: PASS.** No violations to justify.

## Phase 0 — Research decisions

**D1. Content-only detection.** `Tika.detect(File)` and `detect(String)` both feed the filename into the
detector, which FR-033 forbids. `Tika.detect(InputStream)` uses magic bytes alone. That is the only
detection entry point this feature uses.

**D2. APK detection.** An APK is a ZIP; magic-byte detection returns `application/zip`. Rather than fall
back to the `.apk` extension (which FR-033 forbids), a ZIP result triggers a second **content** probe:
read the archive's entry names and look for `AndroidManifest.xml` plus `classes.dex`. Still contents-only,
and it correctly classifies an APK named `holiday.jpg`.

**D3. Where reports live.** `~/.mediascanner/cleanup/cleanup-report-<runId>.json`. Not the target archive
— the Cleanup screen operates on an arbitrary directory and no archive need exist (spec Assumptions).

**D4. Empty-folder pruning is post-order.** A single post-order walk deletes children before parents, so
a chain `a/b/c` collapses in one pass without iterating to a fixed point.

**D5. Re-verification cost.** Re-classifying reads a bounded prefix again. At deletion-set sizes (tens to
thousands, not millions) this is negligible, and it is the only defence against the preview being stale.

**D6. No new SQLite migration.** Deliberate: see Constitution Check, Principle III.

## Phase 1 — Design

### New components

| File | Responsibility |
|------|----------------|
| `model/MimeGroup.java` | The group enum: which MIME types belong, whether the group is deletable |
| `model/CleanupCandidate.java` | One classified file: path, size, detected type, group, outcome, reason |
| `model/CleanupRun.java` | Aggregate of one run: root, timestamp, counts, byte totals |
| `engine/ContentClassifier.java` | Bytes → MIME type → `MimeGroup`. Filename never consulted |
| `engine/CleanupScanner.java` | Link-safe walk yielding files **and** directories |
| `engine/DangerousRoots.java` | Refuses drive roots, OS directories, profile roots |
| `engine/CleanupEngine.java` | `analyze()`, `delete()`, `pruneEmptyDirectories()` |
| `report/CleanupReportWriter.java` | Durable JSON report |
| `ui/CleanupController.java` + `fxml/cleanup.fxml` | The screen |

### Modified

- `ui/ScreenNavigator.java` — add `CLEANUP` screen type
- `ui/MenuBarController.java` — add Tools → "Cleanup…"

### Contract: `CleanupEngine`

```
CleanupRun  analyze(Path root, Consumer<Progress> onProgress)   // never mutates
DeleteResult delete(CleanupRun run, Set<MimeGroup> groups)      // re-verifies each file
PruneResult  pruneEmptyDirectories(Path root)                   // post-order, root preserved
void         cancel()
```

`delete()` rejects any group whose `deletable` flag is false, and re-asserts the protected-media
invariant per file even if a caller somehow passes one.

## Phase 2 — Story slices

- **US1 (P1)**: scanner + classifier + analyze + preview UI. No destructive code exists yet.
- **US2 (P2)**: confirmation modal + `delete()` + report.
- **US3 (P3)**: `pruneEmptyDirectories()` + its confirmation.

Each slice is independently demonstrable, per Principle VIII.

## Complexity Tracking

No constitutional violations requiring justification.
