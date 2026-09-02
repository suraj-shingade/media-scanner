# Tasks: Cleanup Tool

**Branch**: `006-cleanup-tool` | **Plan**: [plan.md](./plan.md)

`[P]` = parallelisable (touches files no other in-flight task touches).

## Phase 1 — Foundation (blocks everything)

| # | Task | Story | [P] |
|---|------|-------|-----|
| T001 | `model/MimeGroup.java` — group enum, MIME membership, `deletable` flag, `PROTECTED_MEDIA` non-deletable | — | [P] |
| T002 | `model/CleanupCandidate.java` — path, size, detected type, group, outcome, reason | — | [P] |
| T003 | `model/CleanupRun.java` — root, runId, candidates, counts, byte totals | — | [P] |
| T004 | `engine/DangerousRoots.java` — refuse drive root, OS dirs, profile root | — | [P] |

## Phase 2 — User Story 1: Preview (P1)

| # | Task | Story | [P] |
|---|------|-------|-----|
| T005 | `engine/ContentClassifier.java` — stream-only Tika detection; ZIP→APK content probe | US1 | |
| T006 | `ContentClassifierTest` — renamed-file corpus proves contents decide (FR-033) | US1 | [P] |
| T007 | `engine/CleanupScanner.java` — link-safe walk yielding files and directories | US1 | |
| T008 | `CleanupScannerTest` — link not followed; unreadable dir skipped not fatal | US1 | [P] |
| T009 | `engine/CleanupEngine.analyze()` + cancellation | US1 | |
| T010 | `ui/cleanup.fxml` + `CleanupController` — pick dir, Analyze, grouped preview | US1 | |
| T011 | `ScreenNavigator.CLEANUP` + Tools → "Cleanup…" menu item | US1 | |
| T012 | `CleanupEngineIT.analysisMutatesNothing` — tree hash identical before/after | US1 | [P] |

## Phase 3 — User Story 2: Delete (P2)

| # | Task | Story | [P] |
|---|------|-------|-----|
| T013 | `CleanupEngine.delete()` — re-verify, protected-media assert, per-file failure capture | US2 | |
| T014 | Confirmation modal — states count, bytes, irreversibility; cancel leaves disk untouched | US2 | |
| T015 | `report/CleanupReportWriter.java` — durable JSON incl. skips and failures | US2 | |
| T016 | **G7 test** `CleanupEngineIT.protectedMediaSurvivesConfirmedDeletion` | US2 | [P] |
| T017 | `CleanupEngineIT.reverifySkipsChangedFile` | US2 | [P] |
| T018 | `CleanupEngineIT.continuesPastUndeletableFile` | US2 | [P] |
| T019 | `CleanupReportWriterTest` — report readable after restart | US2 | [P] |

## Phase 4 — User Story 3: Prune (P3)

| # | Task | Story | [P] |
|---|------|-------|-----|
| T020 | `CleanupEngine.pruneEmptyDirectories()` — post-order, root preserved | US3 | |
| T021 | Prune preview + confirmation in the controller | US3 | |
| T022 | `CleanupEngineIT.prunesNestedChainInOnePass` + non-empty preserved | US3 | [P] |

## Phase 5 — Verification

| # | Task | Story | [P] |
|---|------|-------|-----|
| T023 | `./mvnw clean verify` green | — | |
| T024 | Drive the Cleanup screen in the running app; screenshot the preview and the result | — | |
| T025 | Update tracker Session Log and Phase Progress | — | |
