# Feature Specification: Cleanup Tool — Delete by MIME Group & Prune Empty Folders

**Feature Branch**: `006-cleanup-tool`

**Created**: 2026-09-01

**Status**: Draft

**Input**: Permanently delete files by detected MIME group (APK, executables, archives, documents, other non-media) and recursively prune empty folders from the source tree, via a standalone Cleanup screen with a mandatory preview-and-confirm gate. Classification uses content detection, not file extension. Deletion is permanent, not quarantine or Recycle Bin.

---

## Summary

MediaScanner organizes images and videos. Everything else in a source tree is invisible to it: the scan
filter drops any file that is not a known image or video extension before it ever reaches a worker, so
installers, executables, archives and documents are neither transferred nor reported — they simply stay
where they are. After a Move job the problem compounds: the media is gone, but the folder skeleton that
held it remains, now empty, alongside every non-media file that was never eligible to move.

This feature adds a **Cleanup** screen: the user picks a directory, MediaScanner walks it and classifies
every non-media file by its **actual content type**, and presents a grouped preview of what it found —
counts, sizes and full paths. Nothing is touched until the user selects the groups they want gone and
explicitly confirms. On confirmation the selected files are **permanently deleted** (not quarantined, not
sent to the Recycle Bin), a deletion report is written for the audit trail, and the empty folders left
behind are pruned bottom-up.

Deletion is irreversible. Every requirement below is written on that basis: the preview gate, the
media-protection invariant, and the re-verification step are not conveniences, they are the only things
standing between a mis-drawn rule and permanent data loss.

> **Governance note**: this feature introduced a new capability class — deliberate permanent deletion —
> which Principle V (no deletion as a *side effect* of duplicate detection) and Principle VII (deletion
> only as the second half of a confirmed Move) did not cover. Constitution v1.2.0 adds **Principle IX,
> Destructive Operations Safety**, and Quality Gate **G7, Destructive Review**. Every requirement in this
> spec is now bound to Principle IX via the FR Traceability Matrix.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Preview What Would Be Deleted (Priority: P1)

A user points the Cleanup screen at a source folder and clicks Analyze. MediaScanner walks the tree and
returns a grouped breakdown: `Executables — 12 files, 480 MB`, `Android packages — 3 files, 210 MB`,
`Archives — 40 files, 6.1 GB`, and so on, each group expandable to the full list of paths and sizes.
Nothing has been deleted. The user can study the list, expand any group, and close the screen with the
disk untouched.

**Why this priority**: This is the safety gate the entire feature rests on, and it is independently
valuable on its own — a user who only ever runs Analyze learns exactly what non-media clutter is sitting
in an archive they were about to migrate, which they cannot discover through MediaScanner today. It is
also the only story that can ship without any destructive code path existing at all.

**Independent Test**: Build a tree containing a valid JPEG, a valid MP4, a Windows `.exe`, an `.apk`, a
`.zip`, a `.pdf`, and — critically — a genuine JPEG renamed to `payload.exe` and a genuine Windows
executable renamed to `holiday.jpg`. Run Analyze. Verify the renamed JPEG is classified as an image and
appears in no deletable group, verify the renamed executable is classified as an executable and appears
in the executables group, and verify nothing on disk changed (compare a full tree hash before and after).

**Acceptance Scenarios**:

1. **Given** a directory containing media and non-media files, **When** the user runs Analyze, **Then**
   every non-media file appears in exactly one group, with its full path, size, and detected content
   type, and no file is created, modified, or removed anywhere on disk.
2. **Given** a file whose extension contradicts its contents, **When** the user runs Analyze, **Then**
   the file is classified by its contents and its extension has no bearing on the result.
3. **Given** a directory containing only images and videos, **When** the user runs Analyze, **Then** the
   preview reports zero deletable candidates and the confirm control is unavailable.
4. **Given** an analysis in progress over a large tree, **When** the user cancels, **Then** analysis stops
   promptly and no deletion is offered.

---

### User Story 2 — Permanently Delete Selected Groups (Priority: P2)

Having reviewed the preview, the user ticks `Executables` and `Android packages`, leaves `Documents`
unticked, and clicks Delete. A confirmation step states exactly what is about to happen — the group names,
the file count, the total bytes, and that the deletion is permanent and cannot be undone — and requires
an explicit affirmative action. On confirmation the files are permanently removed, progress is shown as
it happens, and a deletion report listing every removed path is written for the record.

**Why this priority**: This is the capability the user asked for. It depends on Story 1 for its input and
cannot ship before it.

**Independent Test**: From the Story 1 tree, select only the executables group and confirm. Verify the
`.exe` and the executable-renamed-to-`.jpg` are gone; verify the JPEG, the MP4, the `.apk`, the `.zip`,
the `.pdf` and the JPEG-renamed-to-`.exe` all still exist byte-identical; verify the deletion report lists
exactly the two removed paths with their sizes and detected types.

**Acceptance Scenarios**:

1. **Given** a preview with groups selected, **When** the user requests deletion, **Then** a confirmation
   step naming the groups, the file count and the total size is presented, and no file is deleted until
   an explicit affirmative action is taken.
2. **Given** the user declines or cancels at the confirmation step, **When** the screen returns, **Then**
   nothing has been deleted and the preview is still available.
3. **Given** a confirmed deletion, **When** it completes, **Then** exactly the files in the selected
   groups are gone and every file in an unselected group remains untouched.
4. **Given** a file that changed on disk between preview and confirmation, **When** deletion reaches it,
   **Then** it is re-verified and skipped if it no longer matches the group it was previewed under, and
   the skip is recorded with its reason.
5. **Given** a file that cannot be deleted because it is locked, read-only, or permission-denied,
   **When** deletion reaches it, **Then** the failure is recorded with its reason and the run continues
   through the remaining files.
6. **Given** a completed deletion, **When** the user looks for the record, **Then** a deletion report
   exists listing every removed path with size, detected content type and timestamp, plus every failure
   and skip with its reason.
7. **Given** the tree contains a link pointing at a file outside the selected directory, **When** a
   deletion runs, **Then** the link's target is never removed and no file outside the selected tree is
   modified.
8. **Given** the user selects a drive root, a system directory, or a user profile root, **When** they
   attempt to proceed, **Then** the tool refuses without an additional explicit override.

---

### User Story 3 — Prune Empty Folders (Priority: P3)

After a Move job drains a source tree, or after a deletion pass, the user runs Prune Empty Folders on the
source directory. MediaScanner finds every directory that contains no files and no non-empty
subdirectories, shows the list, and on confirmation removes them bottom-up so a parent whose only contents
were themselves-empty subfolders is also removed.

**Why this priority**: Independently useful and independently testable, but it is housekeeping — the tree
is merely untidy, not polluted. It delivers less than either story above and is the natural last slice.

**Independent Test**: Build `a/b/c/` where `c` is empty, `b` contains only `c`, and `a` contains only `b`;
alongside `d/` containing one JPEG; alongside `e/` containing only a zero-byte file. Run Prune. Verify
`a`, `b` and `c` are all removed in one pass, verify `d` survives, and verify `e` survives because a
zero-byte file is still a file.

**Acceptance Scenarios**:

1. **Given** a chain of nested directories that are empty all the way down, **When** the user confirms a
   prune, **Then** the entire chain is removed in a single pass.
2. **Given** a directory containing any file at all, **When** a prune runs, **Then** that directory and
   all of its ancestors are preserved.
3. **Given** a prune is requested, **When** the user reaches the confirmation step, **Then** the full list
   of directories to be removed is shown first and nothing is removed until confirmed.
4. **Given** the selected root directory is itself empty, **When** a prune runs, **Then** the root is
   preserved and only directories beneath it are considered.

---

### Edge Cases

- **Symbolic links, junctions and reparse points**: the walk must not follow them, and a link must never
  cause deletion of a file outside the selected tree. A link itself may be a candidate; its target is not.
- **Hard links**: deleting one name removes one link, not necessarily the data. The report must not claim
  bytes were reclaimed that were not.
- **The file changes between preview and confirmation**: contents replaced, file renamed, file already
  deleted, file grown. Every candidate is re-verified immediately before deletion.
- **The file cannot be deleted**: open by another process (common on Windows), read-only attribute, ACL
  denies delete, path exceeds the OS path limit. Each is a recorded failure, never a crash and never an
  abort of the whole run.
- **Dangerous roots**: the user selects a drive root, a system directory, a user profile root, or the
  MediaScanner target archive. The tool must refuse or require an additional explicit override.
- **Zero candidates**: analysis finds nothing deletable; the confirm path must be unreachable rather than
  confirming a no-op.
- **Very large trees**: 10 M+ files per NFR-001. Analysis must stream and remain cancellable; the preview
  must stay responsive rather than materializing every path into the UI at once.
- **A folder becomes empty only because of this run's deletions**: prune must see the post-deletion state.
- **Hidden and system files**: present in the tree and classifiable; they are not implicitly protected.
- **A file with no extension, a zero-byte file, or an unreadable file**: classification must produce a
  definite outcome for each rather than silently dropping it from the preview.
- **The selected directory is deleted or unmounted mid-run**: the run ends cleanly with what it recorded.

---

## Requirements *(mandatory)*

### Functional Requirements

**Discovery and classification**

- **FR-032**: System MUST walk the user-selected directory recursively and consider every regular file,
  including files that the transfer pipeline currently excludes as unsupported formats.
- **FR-033**: System MUST classify each file by inspecting its **contents**. The file's name and extension
  MUST NOT influence the classification verdict.
- **FR-034**: System MUST assign every classified file to exactly one group. Groups MUST include at
  minimum: Android packages, Executables and installers, Archives, Documents, Audio, and Other non-media.
- **FR-035**: System MUST classify any file whose contents are a recognized image or video as protected
  media, regardless of its name, and MUST exclude protected media from every deletable group.
- **FR-036**: System MUST produce a definite classification outcome for every file encountered, including
  zero-byte, extension-less, and unreadable files; no file may be silently omitted from the preview.
- **FR-037**: System MUST allow the user to cancel an in-progress analysis, leaving the tree unmodified.

**Preview and confirmation**

- **FR-038**: System MUST present analysis results grouped by classification, showing per group the file
  count and total size, and on demand the full path, size and detected content type of each file.
- **FR-039**: System MUST require the user to select which groups are to be deleted. No group may be
  selected by default.
- **FR-040**: System MUST NOT delete any file until the user has been shown the analysis result and has
  taken an explicit affirmative confirmation action.
- **FR-041**: The confirmation step MUST state the groups selected, the exact file count, the total bytes,
  and that the deletion is permanent and cannot be undone.
- **FR-042**: System MUST allow the user to abandon the operation at the confirmation step with no
  modification to disk.

**Deletion**

- **FR-043**: System MUST permanently delete confirmed files. Files MUST NOT be moved to a quarantine
  bucket, the OS Recycle Bin, or any other holding location.
- **FR-044**: System MUST re-verify each candidate immediately before deleting it, and MUST skip any file
  whose contents no longer place it in the group it was previewed under.
- **FR-045**: System MUST never delete a file classified as protected media, under any group selection.
- **FR-046**: System MUST continue processing remaining candidates when an individual deletion fails, and
  MUST record each failure with its specific reason.
- **FR-047**: System MUST NOT follow symbolic links, junctions or other reparse points during the walk,
  and MUST NOT delete any file located outside the selected directory tree.
- **FR-048**: System MUST refuse to operate on a drive root, an operating-system directory, or a user
  profile root without an additional explicit override from the user.
- **FR-049**: System MUST report deletion progress while the operation runs, and MUST allow the user to
  stop it; files already deleted stay deleted and are reported as such.

**Empty folder pruning**

- **FR-050**: System MUST identify directories that contain no files and no non-empty subdirectories,
  evaluated against the state of the tree at the time the prune runs.
- **FR-051**: System MUST remove qualifying directories bottom-up, so that a directory whose only contents
  were themselves-empty subdirectories is also removed within the same pass.
- **FR-052**: System MUST present the full list of directories to be removed and require explicit
  confirmation before removing any of them.
- **FR-053**: System MUST preserve the user-selected root directory itself even when it is empty.
- **FR-054**: System MUST treat a directory containing any file — including a zero-byte file or a hidden
  file — as non-empty.

**Record keeping**

- **FR-055**: System MUST write a durable deletion report for every run in which at least one file was
  deleted, listing each removed file's full path, size, detected content type, assigned group, and
  removal timestamp.
- **FR-056**: The deletion report MUST additionally record every candidate that was skipped or that failed
  to delete, each with its specific reason.
- **FR-057**: System MUST record every prune run's removed directories in the same durable report format.
- **FR-058**: Deletion reports MUST survive the deletion itself and remain readable after the application
  restarts.

### Key Entities

- **Cleanup Run**: one user-initiated pass over a selected directory. Holds the selected root, the run
  timestamp, the groups the user selected, the terminal state (analyzed / confirmed / completed /
  cancelled / stopped), and the aggregate counts and byte totals.
- **Cleanup Candidate**: one file found during analysis. Holds the full path, size, detected content type,
  assigned group, and its per-file outcome (previewed, deleted, skipped-on-reverify, failed) with reason.
- **MIME Group**: a named, user-selectable bucket of content types — Android packages, Executables and
  installers, Archives, Documents, Audio, Other non-media — plus the non-selectable Protected Media group
  that exists specifically so that images and videos can never be chosen for deletion.
- **Empty Directory Candidate**: one directory identified as prunable, with its path, its depth, and
  whether it qualified originally or only after this run's deletions.
- **Deletion Report**: the durable per-run record of everything removed, skipped and failed, retained
  after the files themselves are gone.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In 100% of runs, no file is deleted without the user having first been shown a preview
  containing that file and having taken an explicit confirmation action.
- **SC-002**: Across a validation corpus in which every file's extension is deliberately mismatched to its
  contents, classification is correct for 100% of files, and zero files whose contents are images or
  videos are ever offered for deletion.
- **SC-003**: A user can go from opening the Cleanup screen to understanding what would be removed, on a
  50 000-file tree, in under 2 minutes.
- **SC-004**: When a deletion run encounters locked, read-only or permission-denied files, it completes
  the remaining candidates in 100% of cases and reports every failure individually.
- **SC-005**: After a prune, zero empty directories remain anywhere beneath the selected root, and 100% of
  directories that contained at least one file are preserved.
- **SC-006**: For every completed run that deleted at least one file, a report is retrievable after an
  application restart that accounts for 100% of the deleted, skipped and failed candidates.
- **SC-007**: Analysis of a 1 000 000-file tree remains cancellable, returning control within 5 seconds of
  the user cancelling.

---

## Assumptions

- **Audio is protected by default but selectable.** Audio is not image or video, so it is not protected
  media under FR-035, but deleting a user's music alongside installers would be a surprise. Audio is its
  own group and, like every group, starts unselected.
- **Existing ignore rules do not protect files from cleanup.** The FR-013 ignore list (`Thumbs.db`,
  `.DS_Store`, `desktop.ini`, `.tmp`) exists to keep junk *out of the archive*; those same files are prime
  cleanup targets. Ignore rules are not consulted here.
- **Reports are stored in the application data directory**, not in a target archive, because the Cleanup
  screen operates on an arbitrary directory and no archive need exist.
- **The Cleanup screen is independent of the scan pipeline.** It does not create a scan job, does not
  write to the hash index, and does not participate in checkpoint or resume. A cleanup run that is
  interrupted is simply over; its report covers what it did before stopping.
- **Cleanup is a foreground, user-attended operation.** It is not schedulable, not automatic, and not
  triggered by the completion of a transfer job.
- **Content detection identifies container and format, not integrity.** A truncated archive is still
  classified as an archive. Detecting damaged payloads is out of scope here and is tracked separately as
  audit finding M3.
- **Single-user desktop context.** No permissions model, no multi-user arbitration, no audit requirements
  beyond the local report.

---

## Dependencies

- **Scan filter must widen.** The current walk admits only known image and video extensions and reports
  everything else as an unsupported format before it reaches a worker. Analysis needs the full file list,
  which means either a second walk mode or a parameterized filter. This is the largest structural change.
- **Directory identity must survive the walk.** The current walk flattens directories away and yields only
  regular files, so nothing downstream can see a directory at all. Empty-folder pruning needs directories
  as first-class results.
- **Classification must be content-only.** The existing validator's content detection is given the file,
  so the filename participates in the verdict — which is exactly what FR-033 forbids. Cleanup needs
  detection driven from the file's bytes alone, with no filename hint supplied.
- **A third classification axis is needed.** File type is currently a two-value notion, image or video,
  with no way to express "this is an executable". Group classification is a new dimension.
- **Constitution amendment — done.** Constitution v1.2.0 adds Principle IX (Destructive Operations
  Safety) and Quality Gate G7 (Destructive Review), and maps FR-032–FR-058 in the traceability matrix.
  The plan must record an explicit Principle IX check, and the feature must carry an acceptance test
  proving protected media survives a confirmed deletion.

## Out of Scope

- Quarantine buckets, Recycle Bin integration, undo, or any form of recovery after deletion.
- Scheduled, automatic, or post-job cleanup. Every run is explicitly user-initiated.
- Deleting or deduplicating media files; that remains the duplicate-handling feature's territory.
- Integrity checking of non-media files.
- Cleaning up remote, network-mounted or cloud storage beyond what the OS presents as a normal path.
