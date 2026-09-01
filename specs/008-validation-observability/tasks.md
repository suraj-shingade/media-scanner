# Tasks: Validation & Observability Hardening

**Input**: Design documents from `specs/008-validation-observability/`

**Prerequisites**: spec.md ✅

---

## Phase 1: Establish what actually detects corruption

- [x] T001 Probe `ImageIO` behaviour on valid, truncated, shredded and bogus images before designing the gate
- [x] T002 Record the finding: **`ImageIO.read` decodes a truncated or shredded JPEG successfully** and returns a garbage image without throwing. "Did it decode" is therefore not a usable test. The decoder's *warning stream* is — every corrupt case emitted at least one warning, both valid cases emitted none, and a truncated PNG throws
- [x] T003 Create `DeepValidationTest` covering valid JPEG/PNG, truncated JPEG, shredded payload, shredded-with-intact-end-marker, truncated PNG, text-in-image-extension, an undecodable-but-valid format, and the disabled-toggle case

---

## Phase 2: Corrupt-media detection (FR-012)

- [x] T004 Add gate 5 to `FileValidator`: decode with an `IIOReadWarningListener` attached; a throw or a corruption-flavoured warning fails the file
- [x] T005 Match warnings against a deliberately narrow list (`truncated`, `corrupt`, `premature`, `missing eoi`, `bogus`, `extraneous bytes`) so an unusual-but-valid file is not failed for a benign warning
- [x] T006 Never fail a format the JDK has no reader for — HEIC, RAW, CR2, NEF, ARW, DNG fall back to header validation (FR-008-004). Only extensions the JDK *does* ship a reader for are failed when no reader accepts them
- [x] T007 Add `ffprobe`-based video validation; a missing `ffprobe` is the normal case on a clean machine and must not fail the file (FR-008-002)
- [x] T008 Add `validation.deep.enabled` to `AppConfig`, defaulting to true, and honour it in `ScanEngine`

---

## Phase 3: Real resource metrics (FR-030)

- [x] T009 Replace the hardcoded-zero disk fields in `ResourceMonitor` with rates derived from the job's own byte counters, via `bindJobSources`
- [x] T010 Replace `Thread.activeCount()` — which counts every thread in the JVM — with the scan pool's active worker count
- [x] T011 Track peak disk read/write and persist them (`V004` migration) so they reach the end-of-job summary (FR-008-008)

---

## Phase 4: Coherent statistics (M9)

- [x] T012 Add `JobStatistics.copy()` and `ScanEngine.snapshotStatistics()`, taken under the lock the workers write through
- [x] T013 Build the checkpoint from one snapshot rather than reading counters individually off the live object

---

## Phase 5: Verification

- [x] T014 `DeepValidationTest` — 10 tests green
- [x] T015 **Fix `FullPipelineIT`**: its fixture wrote 20 KB of zero bytes and called them valid images. The header-only gate accepted that, so the test had been asserting for four features that undecodable content was valid media. Now generates real JPEGs
- [x] T016 **Fix flaky temp-dir cleanup** in `RerunAndResumeIT` and `ScanReportsEndToEndIT`: the SQLite database lived inside the `@TempDir`, and on Windows the WAL/SHM files linger briefly after close while JUnit deletes the directory immediately. Passed in isolation, failed under full-suite load. The database now lives outside the TempDir and is cleaned up tolerantly
- [x] T017 Full suite green — 224 tests (144 unit, 80 integration), confirmed across repeated runs
- [ ] T018 Re-run the 52k-file acceptance corpus with deep validation enabled, to measure its real cost at scale
- [ ] T019 Surface the deep-validation toggle in the Preferences dialog — it is currently config-file only

---

## Notes

T002 is the whole feature in one line. A gate built on "does it decode" would have looked correct,
passed review, and detected nothing — `ImageIO` returns a perfectly-shaped `BufferedImage` for a file
truncated to 40% of its bytes. Probing the behaviour before designing the gate is what avoided shipping
a validator that validates nothing.
