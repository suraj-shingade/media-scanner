# Feature Specification: MediaScanner Core Engine

**Feature Branch**: `001-media-scanner-core`

**Created**: 2026-06-03

**Status**: Draft

**Input**: Full feature covering FR-001–FR-031 from MediaScanner BRD and Functional Requirements Document

---

## Overview

MediaScanner is a desktop application for Windows and macOS that organizes large personal and professional media collections automatically. Users point it at a source folder, choose a destination, and the application recursively scans, validates, and reorganizes image and video files into a clean date-based folder structure — without modifying originals unless the user explicitly requests it. The application handles files numbering in the millions, provides a live progress dashboard, and can be paused, resumed, or recovered after a crash at any point.

---

## User Scenarios & Testing

### User Story 1 — Directory Setup and Scan Configuration (Priority: P1)

A user launches MediaScanner, selects a source directory containing their media files, selects a target directory where organized files will be placed, chooses their preferred transfer mode (copy or move), configures the folder naming pattern, and starts the scan. The system validates both directories are accessible and distinct, then begins processing.

**Why this priority**: Every other story depends on the user being able to configure and launch a scan. Without this, nothing else runs. Covers FR-001, FR-002, FR-003, FR-004, FR-005, FR-007, FR-008.

**Independent Test**: Launch the application, select a source folder with 100 mixed media files (images and videos), select an empty target folder, choose Copy mode with default `/yyyy/MMM` structure, start the scan, and verify files appear in correctly named year/month folders at the target.

**Acceptance Scenarios**:

1. **Given** the user launches the application, **When** they browse and select a source directory, **Then** the application displays the selected path and confirms it is accessible and non-empty.
2. **Given** a source directory is selected, **When** the user selects a target directory that is different from the source, **Then** the application accepts it and enables the Start button.
3. **Given** the user selects the same path for both source and target, **When** they attempt to start, **Then** the application displays an error and prevents execution.
4. **Given** both directories are configured, **When** the user chooses Copy mode, **Then** originals remain untouched after processing completes.
5. **Given** both directories are configured, **When** the user chooses Move mode, **Then** source files are deleted only after confirmed successful transfer to the target.
6. **Given** the user selects `/yyyy/MM` as the folder pattern, **When** a file with capture date 2024-03-15 is processed, **Then** it is placed at `<target>/2024/03/`.
7. **Given** the user selects `/yyyy/MMM` (default), **When** a file with capture date 2024-03-15 is processed, **Then** it is placed at `<target>/2024/Mar/`.
8. **Given** the user selects `/yyyy/MMM/dd`, **When** a file with capture date 2024-03-15 is processed, **Then** it is placed at `<target>/2024/Mar/15/`.
9. **Given** the scan is running, **When** all files in the source are processed, **Then** the application displays a completion summary and stops automatically.

---

### User Story 2 — Recursive Scanning and Media Classification (Priority: P1)

The system recursively traverses all subfolders of the source directory, identifies media files by type, applies ignore rules to skip system and temporary files, and builds a complete work queue before or during processing.

**Why this priority**: Correct file discovery is the foundation of every subsequent step. Without accurate scanning, the entire operation produces wrong results. Covers FR-003, FR-005, FR-013.

**Independent Test**: Create a source folder with 5 levels of nested subfolders containing a mix of media files, system files (`.DS_Store`, `Thumbs.db`), and non-media files (`.txt`, `.pdf`). Run a scan and verify only the supported media files appear in the queue — system and non-media files are absent.

**Acceptance Scenarios**:

1. **Given** a source directory with nested subfolders, **When** the scan starts, **Then** all subfolders at every depth are traversed and their files evaluated.
2. **Given** the default ignore rules are active, **When** `Thumbs.db`, `.DS_Store`, `desktop.ini`, `._*`, `.cache`, `.tmp`, and `.temp` files are encountered, **Then** they are skipped and counted in the Skipped bucket with reason "ignore rule matched".
3. **Given** the user adds a custom ignore pattern (e.g., `*-thumbnail.*`), **When** matching files are encountered, **Then** they are skipped without error.
4. **Given** a file with extension `.heic`, `.cr2`, `.nef`, `.arw`, or `.dng` is found, **When** classified, **Then** it is treated as a supported image file and added to the work queue.
5. **Given** a file with extension `.mts`, `.m4v`, or `.3gp` is found, **When** classified, **Then** it is treated as a supported video file and added to the work queue.
6. **Given** a file with an unsupported extension (e.g., `.pdf`, `.docx`) is found, **When** classified, **Then** it is placed in the Skipped bucket with reason "unsupported format".

---

### User Story 3 — File Validation and Quality Filtering (Priority: P2)

Before any file is transferred, the system validates it: checking for zero-byte files, files below configurable size thresholds, and media files that cannot be read or decoded. Invalid files are logged and placed in appropriate buckets without halting the job.

**Why this priority**: Prevents garbage from entering the target archive. Without validation, corrupt and empty files pollute the organized output. Covers FR-010, FR-011, FR-012, FR-019, FR-020.

**Independent Test**: Create a test set with: one 0-byte `.jpg`, one 5 KB `.jpg` (below the 10 KB image threshold), one corrupt `.mp4` (valid extension, unreadable content), and ten valid media files. Run a scan and verify: the three invalid files are in the appropriate buckets with correct reasons, and all ten valid files are transferred correctly.

**Acceptance Scenarios**:

1. **Given** a file with size = 0 bytes is encountered, **When** validated, **Then** it is placed in the Skipped bucket with reason "empty file" and never transferred.
2. **Given** an image file smaller than 10 KB is encountered (default threshold), **When** validated, **Then** it is placed in the Skipped bucket with reason "small file".
3. **Given** a video file smaller than 100 KB is encountered (default threshold), **When** validated, **Then** it is placed in the Skipped bucket with reason "small file".
4. **Given** the user sets a custom image size threshold of 50 KB, **When** a 30 KB image is encountered, **Then** it is placed in the Skipped bucket with reason "small file".
5. **Given** a file with a valid media extension cannot be read or decoded as valid media, **When** validated, **Then** it is placed in the Failure bucket at `/_failures` with the specific failure reason recorded in `failure-report.json`.
6. **Given** a media file passes all validation checks, **When** the validation step completes, **Then** it proceeds to metadata extraction without being logged in any error bucket.
7. **Given** the job completes, **When** the summary is displayed, **Then** counts for empty files, small files, corrupted files, and unsupported files are each shown separately.

---

### User Story 4 — Metadata Extraction and Date Standardization (Priority: P2)

For each valid media file, the system extracts the best available date using a priority-ordered fallback chain: embedded capture date first, then file creation date, then file modified date. All dates are normalized to a standard format before being used to determine the target folder.

**Why this priority**: Date accuracy determines folder correctness. Incorrect or missing dates cause files to land in the wrong folders. Covers FR-006, FR-007.

**Independent Test**: Prepare three files: one with embedded EXIF capture date, one with no EXIF but a known creation date, one with neither (only a modified date). Run a scan and verify each file lands in the folder matching its respective date source, using the correct priority.

**Acceptance Scenarios**:

1. **Given** a JPEG with an embedded EXIF capture date of 2023-07-04, **When** metadata is extracted, **Then** the file is placed in the folder for July 2023, not the file's filesystem creation date.
2. **Given** an image with no embedded capture date but a filesystem creation date of 2022-12-01, **When** metadata is extracted, **Then** the creation date is used and the file is placed in the folder for December 2022.
3. **Given** an image with no embedded capture date and no creation date, but a modified date of 2021-05-20, **When** metadata is extracted, **Then** the modified date is used and the file is placed in the folder for May 2021.
4. **Given** a video file with FFprobe-readable metadata containing a capture date, **When** metadata is extracted, **Then** that date takes priority over filesystem dates.
5. **Given** a file where all three date sources are unavailable or unreadable, **When** metadata extraction fails, **Then** the file is placed in the Skipped bucket with reason "metadata missing".
6. **Given** a date is extracted, **When** standardized, **Then** it is stored in ISO 8601 format (e.g., `2025-04-15T18:22:01`) for all internal operations.

---

### User Story 5 — Filename Collision and Content Duplicate Handling (Priority: P2)

When transferring files, the system detects two types of conflicts: files with the same name at the destination (filename collision) and files with identical content already in the archive (hash-based duplicates). Each type is handled by a configurable policy without ever deleting source files.

**Why this priority**: Prevents silent overwrite of existing archive files and ensures duplicate content does not waste storage. Covers FR-009, FR-023, FR-024, FR-025.

**Independent Test**: Set up a target with existing files. Run a job that includes: a file with the same name as an existing target file (different content), a file with identical content to an existing indexed file (different name), and a new unique file. Verify: the name collision is renamed with `(1)` suffix, the content duplicate is handled per configured policy (default: skip), and the unique file is transferred normally.

**Acceptance Scenarios**:

1. **Given** a source file `IMG001.jpg` and a `IMG001.jpg` already exists at the target destination folder, **When** transferred, **Then** the source is renamed to `IMG001(1).jpg`; if that also exists, `IMG001(2).jpg`, and so on.
2. **Given** the duplicate policy is set to Skip (default), **When** a file's SHA-256 hash matches an entry in the index, **Then** the file is not transferred and is counted as a duplicate.
3. **Given** the duplicate policy is set to Move to Duplicate Bucket, **When** a content duplicate is detected, **Then** the file is moved to `/_duplicates` in the target and counted.
4. **Given** the duplicate policy is set to Keep Both, **When** a content duplicate is detected, **Then** the file is transferred with a `_DUP_1` suffix (incrementing) and both versions are retained.
5. **Given** a very large video file (>1 GB) is being duplicate-checked, **When** the smart hash check runs, **Then** the system first compares file size and name, then a partial block hash, before computing a full SHA-256 — only computing the full hash when the faster checks do not eliminate it.
6. **Given** a file has been hashed in a previous job run, **When** the same file path is encountered in a subsequent run with an unchanged size and modification timestamp, **Then** its cached hash is used and the file is not re-read from disk. If the size or modification timestamp has changed, the file is re-hashed and the index is updated.
7. **Given** any duplicate is detected, **When** counted, **Then** the duplicate count, total size saved, file location, and hash value are recorded for the end-of-job report.
8. **Given** a source file is identified as a duplicate, **When** the Skip policy is active, **Then** the source file is never deleted or moved — only skipped at the destination.

---

### User Story 6 — Job Control: Pause, Resume, and Stop (Priority: P2)

Users can pause an active job at any time, resume from where they left off (even after an application restart), export the job state to transfer it to another machine, and stop the job cleanly with no file corruption.

**Why this priority**: Multi-hour jobs over large archives need reliable control. Without this, a machine restart or intentional pause forces the user to restart from scratch. Covers FR-014, FR-015, FR-016, FR-017, FR-018, FR-021, FR-022.

**Independent Test**: Start a job with 50,000 files, pause it after ~10,000 are processed, close the application entirely, reopen it, and verify it automatically detects the interrupted job and offers to resume from the pause point. Verify the count of processed files is consistent with the checkpoint.

**Acceptance Scenarios**:

1. **Given** a job is running, **When** the user clicks Pause, **Then** processing stops within 3 seconds, in-flight file transfers complete, and the job state is saved.
2. **Given** a job is paused, **When** the user clicks Resume, **Then** processing continues from the exact point it was paused with no re-processing of already-completed files.
3. **Given** a job is running and the application crashes or is force-quit, **When** the application is restarted, **Then** it automatically detects the interrupted job within 5 seconds and offers to resume.
4. **Given** the user accepts the resume offer, **When** processing restarts, **Then** files already processed before the interruption are not re-transferred.
5. **Given** the user declines the resume offer, **When** they choose to start fresh, **Then** the previous job state is cleared and a new job begins.
6. **Given** a job is running, **When** the user clicks Stop, **Then** the current file transfer completes safely, the job terminates cleanly, and no partial or corrupt files are left at the target.
7. **Given** the user wants to move a job to another machine, **When** they export the job state, **Then** a portable state file is created that can be imported on another machine to resume processing.
8. **Given** the application is processing, **When** it runs in the background (e.g., user switches applications), **Then** processing continues uninterrupted.
9. **Given** a job is active, **When** progress is being made, **Then** the job state is automatically saved every 1,000 files processed or every 60 seconds (whichever comes first).
10. **Given** a Move-mode job is resumed after a crash, **When** the system detects a partial copy at the target (file exists but size does not match the source), **Then** the partial file is deleted and the complete file is re-transferred from the intact source before marking it processed.

---

### User Story 7 — Real-Time Progress Dashboard (Priority: P3)

During any active job, the application displays a live dashboard showing file counts, data transfer statistics, current throughput, ETA, and system resource utilization — all updating continuously without user interaction.

**Why this priority**: Gives users confidence the job is running correctly and on track. Without this, users have no visibility into progress and cannot estimate completion time. Covers FR-026, FR-027, FR-028, FR-029, FR-030, FR-031.

**Independent Test**: Start a job with 100,000 files. Verify the dashboard shows updating counts, that throughput figures change dynamically, that ETA decreases as processing progresses, and that CPU and memory utilization values are visible and non-zero.

**Acceptance Scenarios**:

1. **Given** a job is active, **When** the dashboard is displayed, **Then** it shows: total files found, files processed, files remaining, files copied, files moved, files skipped, files failed, and duplicates detected — all updating in real time.
2. **Given** data is being transferred, **When** the data statistics panel is visible, **Then** total data processed, copied, moved, skipped, and duplicate data saved are shown with auto-scaled units (B, KB, MB, GB, or TB chosen automatically).
3. **Given** the job has been running for at least 5 seconds, **When** throughput is displayed, **Then** files/sec and MB/sec (or GB/sec) are shown as rolling averages over 5 seconds, 30 seconds, and the full job duration.
4. **Given** current throughput is available, **When** ETA is displayed, **Then** it is calculated from (remaining files + remaining bytes) ÷ current throughput and shown in hours, minutes, seconds format.
5. **Given** the system is actively processing, **When** resource utilization is displayed, **Then** current CPU usage (%), memory usage (GB), disk read speed (MB/sec), disk write speed (MB/sec), and active worker thread count are all visible.
6. **Given** the job runs for more than 30 seconds, **When** the throughput history panel is visible, **Then** files/sec, MB/sec, CPU %, and memory % are tracked over time for performance analysis.

---

### User Story 8 — End-of-Job Summary Report (Priority: P3)

When a job completes (or is stopped), the application displays a comprehensive summary covering file counts, data volumes, performance statistics, and execution timing — and allows the user to export this report.

**Why this priority**: Users need a record of what happened, especially for large archive operations. Enables verification and audit of the job outcome. Covers the Enhanced Summary Report section of the FRD.

**Independent Test**: Run a job to completion with a known set of files. Verify the summary shows correct counts for each category (processed, copied, moved, skipped, failed, duplicate), correct total data volumes, and that peak and average throughput figures are shown.

**Acceptance Scenarios**:

1. **Given** a job completes, **When** the summary report is displayed, **Then** it shows separate counts for: total files found, processed, copied, moved, skipped, failed, and duplicate.
2. **Given** a job completes, **When** the data summary is displayed, **Then** it shows total data scanned, copied, moved, skipped, and duplicate data saved — all in auto-scaled units.
3. **Given** a job completes, **When** performance statistics are shown, **Then** peak files/sec, average files/sec, peak MB/sec, average MB/sec, peak GB/sec (if applicable), and average GB/sec are all displayed.
4. **Given** a job completes, **When** infrastructure statistics are shown, **Then** average CPU utilization, peak CPU utilization, average memory utilization, and peak memory utilization are displayed.
5. **Given** a job completes, **When** execution timing is shown, **Then** start time, end time, total duration, and total folders created are all displayed.
6. **Given** the summary report is displayed, **When** the user chooses to export it, **Then** the report is saved in a readable format (JSON or plain text) to a user-chosen location.

---

### User Story 9 — Performance Mode and Worker Configuration (Priority: P3)

Users can maximize processing speed by enabling High-Priority Mode and adjusting the number of parallel worker threads. The default configuration automatically selects a high-performance thread count based on available CPU cores.

**Why this priority**: Power users and enterprise operators need to extract maximum throughput from their hardware. Covers NFR-003, NFR-004, NFR-005.

**Independent Test**: On a 16-core machine, enable High-Priority Mode and run a job. Verify the default thread count is `32` (CPU cores × 2). Change the thread count to 8 and verify it is applied. Verify throughput is measurably higher with more threads on I/O-bound workloads.

**Acceptance Scenarios**:

1. **Given** the application launches on a machine with 16 CPU cores, **When** a new job is configured, **Then** the default worker thread count is 32 (cores × 2) and is visible in the settings.
2. **Given** the default thread count is shown, **When** the user changes it to a custom value (e.g., 8), **Then** subsequent processing uses exactly 8 worker threads.
3. **Given** the user enables High-Priority Mode, **When** a job runs on Windows, **Then** the application requests elevated scheduling priority (`HIGH_PRIORITY_CLASS`).
4. **Given** the user enables High-Priority Mode, **When** a job runs on macOS, **Then** the application requests increased scheduling priority to the extent the OS permits.
5. **Given** the application is processing on a machine with sufficient RAM, **When** files are being queued and metadata cached, **Then** available system RAM is used aggressively to minimize redundant disk reads.

---

### Edge Cases

- **No media files found**: Source directory exists and is readable but contains zero supported media files. The application reports "0 files found" and does not start a transfer job.
- **Source and target on different drives**: Copy and Move modes both work correctly across drive boundaries; no assumption of atomic rename is made.
- **Target drive fills up mid-job**: The current file transfer fails with a "disk full" error, the file is placed in the Failure bucket, and the job pauses with a user-visible alert rather than crashing silently.
- **File permission denied**: A source file cannot be read due to OS permissions. It is placed in the Failure bucket with reason "permission denied" and processing continues.
- **Network drive disconnects during Move**: The source file is not deleted until the target write is confirmed complete. If the write cannot be confirmed, the source is preserved and the file goes to Failure bucket.
- **Partial file at target on resume (Move mode)**: If resume detects a partial copy at the target (file exists but size does not match source), the partial target file is deleted and the complete file is re-transferred from the still-intact source.
- **SQLite database missing or corrupt on startup**: The application detects the corruption or absence, rebuilds a fresh empty database, displays a prominent warning that the hash cache has been lost and all files will be re-hashed in the next job, and allows the user to start a new job. No prior job can be automatically resumed without its checkpoint state.
- **Simultaneous duplicate of new file**: Two source files have identical content (hash). Only one is transferred; the other is handled per duplicate policy.
- **Date in the future**: A file's metadata contains a capture date in the future (e.g., camera clock misconfigured). The system accepts it and organizes the file under the future date folder without error.
- **File modified during scan**: A source file changes size or content between discovery and processing. The system uses the state at the time of processing; if the read fails, the file goes to Failure bucket.
- **Extremely long file paths**: Paths exceeding OS limits (Windows 260 char, macOS 1024 char) are placed in the Failure bucket with reason "path too long".
- **Zero-duration video**: A video file that opens successfully but has 0-second duration is treated as valid unless it is also below the size threshold.

---

## Requirements

### Functional Requirements

**Directory and Transfer Configuration**
- **FR-001**: Users MUST be able to select any accessible local or network-mounted source directory via a file browser dialog.
- **FR-002**: Users MUST be able to select any accessible local or network-mounted target directory via a file browser dialog; the target MUST differ from the source.
- **FR-003**: The system MUST support the following image formats: jpg, jpeg, png, gif, webp, bmp, tif, tiff, heic, raw, cr2, nef, arw, dng. The system MUST support the following video formats: mp4, mov, avi, mkv, webm, mts, m4v, 3gp.
- **FR-004**: Users MUST be able to choose between Copy mode (originals preserved) and Move mode (originals deleted only after confirmed successful transfer).
- **FR-005**: The system MUST recursively scan all subfolders at every depth within the source directory.

**Metadata and Organization**
- **FR-006**: The system MUST extract file dates using this priority order: (1) embedded media capture date, (2) filesystem creation date, (3) filesystem modified date. If all three are unavailable, the file is placed in the Skipped bucket with reason "metadata missing".
- **FR-007**: All extracted dates MUST be normalized to ISO 8601 format (`YYYY-MM-DDTHH:MM:SS`) for all internal operations and display.
- **FR-008**: The system MUST organize files into the default folder structure `/yyyy/MMM`. Users MUST be able to select from these alternatives: `/yyyy/MM`, `/yyyy/MMM/dd`, `/yyyy/MM/dd`.

**File Filtering and Ignore Rules**
- **FR-009**: When a file with the same name already exists at the target destination folder, the incoming file MUST be renamed with an incrementing numeric suffix in parentheses: `IMG001.jpg` → `IMG001(1).jpg` → `IMG001(2).jpg`.
- **FR-010**: The system MUST detect files with size = 0 bytes, skip them without transfer, and record them in the Skipped bucket with reason "empty file".
- **FR-011**: The system MUST detect image files smaller than 10 KB and video files smaller than 100 KB, skip them, and record them in the Skipped bucket with reason "small file". Both thresholds MUST be user-configurable.
- **FR-012**: The system MUST attempt to validate the readability of each media file. Files that cannot be decoded as valid media MUST be placed in the Failure bucket with the specific failure reason.
- **FR-013**: The system MUST apply ignore rules before processing. Default ignored patterns: `Thumbs.db`, `.DS_Store`, `desktop.ini`, `._*`, `.cache`, `.tmp`, `.temp`. Users MUST be able to add custom patterns.

**Job Persistence and Control**
- **FR-014**: The system MUST automatically persist job state every 1,000 files processed or every 60 seconds (whichever comes first) in JSON format containing: jobId, status, sourcePath, targetPath, processedFiles, failedFiles, skippedFiles, emptyFiles, smallFiles, checkpointTime.
- **FR-015**: Users MUST be able to export the current job state to a portable file and import a previously exported state to resume processing on any machine.
- **FR-016**: Users MUST be able to pause an active job at any time. Processing MUST fully stop within 3 seconds of the pause action, with all in-flight file transfers completing before the job enters the paused state.
- **FR-017**: Users MUST be able to resume a paused or interrupted job. Resuming MUST continue from exactly where the job stopped with no re-processing of already-completed files.
- **FR-018**: Users MUST be able to stop an active job. The current file transfer completes safely, and no partial or corrupt files are left at the target. On resume after a crash or stop during Move mode, if a partial copy is detected at the target (size mismatch with source), it MUST be deleted and the file re-transferred from the intact source.
- **FR-019**: All files that fail processing MUST be recorded in a Failure bucket at `/_failures` with a `failure-report.json` listing each file's path and failure reason.
- **FR-020**: All skipped files MUST be tracked in a Skipped bucket with one of these reasons: empty file, small file, unsupported format, ignore rule matched, metadata missing.
- **FR-021**: The application MUST continue processing when the user switches to other applications or minimizes the window.
- **FR-022**: On startup, the application MUST detect any interrupted job from a previous session and offer to resume it automatically within 5 seconds of launch. If the local database file is missing or corrupt, the application MUST rebuild a fresh empty database, display a prominent warning that the hash cache is lost and all previously indexed files will be re-hashed, and allow the user to start a new job.

**Hash-Based Duplicate Detection**
- **FR-023**: The system MUST detect content duplicates using SHA-256 hashing. Three policies MUST be supported: Skip (default, file not transferred), Move to `/_duplicates`, or Keep Both (renamed with `_DUP_N` suffix). The duplicate report MUST record: count, size saved, file locations, and hash values.
- **FR-024**: The system MUST maintain a single global persistent index shared across all jobs on the machine, containing: file path, file name, SHA-256 hash, file size, modification timestamp, capture date, and last processed time. The index is consulted for every file across all jobs — a file hashed in a prior job is recognized as a duplicate in all future jobs regardless of source or target directory. When a previously indexed file is encountered again, the cached hash MUST be used only if the file's current size AND modification timestamp match the indexed values; otherwise the file MUST be re-hashed and the index updated.
- **FR-025**: For large files, hashing MUST use a three-stage optimization: Stage 1 compares file size and name; Stage 2 computes a partial block hash; Stage 3 computes the full SHA-256. A later stage is only reached if the earlier stage does not rule out duplication.

**Progress Monitoring**
- **FR-026**: The system MUST display a real-time dashboard during any active job showing: total files found, files processed, files remaining, files copied, files moved, files skipped, files failed, and duplicate files detected — all updating continuously.
- **FR-027**: The dashboard MUST display data transfer statistics: total data processed, copied, moved, skipped, and duplicate data saved — with units automatically selected (B, KB, MB, GB, or TB) based on the volume.
- **FR-028**: The dashboard MUST display throughput as files/sec and MB/sec (or GB/sec) with rolling averages over the last 5 seconds, the last 30 seconds, and the full job duration.
- **FR-029**: The dashboard MUST display an estimated time remaining (ETA) calculated from current throughput and the remaining file count and data volume, formatted as `HH:MM:SS`.
- **FR-030**: The dashboard MUST display system resource utilization: CPU usage (%), memory usage (GB), disk read speed (MB/sec), disk write speed (MB/sec), and active worker thread count.
- **FR-031**: The system MUST track historical execution metrics (files/sec, MB/sec, CPU %, memory %) over the lifetime of the job for performance analysis and troubleshooting.

### Key Entities

- **Job**: Represents one scan-and-organize operation. Has a unique ID, source path, target path, transfer mode, folder pattern, duplicate policy, status (RUNNING / PAUSED / COMPLETED / FAILED), and all counters. A job persists across sessions.
- **MediaFile**: A candidate file discovered during scanning. Has a path, extension, size, validation status, extracted date, destination path, and outcome (transferred / skipped / failed / duplicate).
- **FileHashRecord**: An entry in the global persistent hash index shared across all jobs. Has file path, file name, SHA-256 hash, file size, modification timestamp, capture date, and last processed time. The cached hash is considered valid only when both file size and modification timestamp match the current file on disk. A single FileHashRecord can be referenced by multiple jobs.
- **JobStatistics**: Aggregated counters for a job. Includes all file counts (found, processed, copied, moved, skipped, failed, duplicate), all data volumes, and all throughput metrics (peak and average files/sec, MB/sec, GB/sec, CPU %, memory %).
- **CheckpointState**: The JSON snapshot of a job saved every 1,000 files or 60 seconds. Contains jobId, status, sourcePath, targetPath, processedFiles, failedFiles, skippedFiles, emptyFiles, smallFiles, and checkpointTime.
- **IgnoreRule**: A pattern (glob or exact match) applied during file discovery. Has a pattern string and source (built-in default or user-defined).
- **FailureRecord**: An entry in the Failure bucket log. Has source file path and failure reason string.
- **SkippedRecord**: An entry in the Skipped bucket log. Has source file path and skip reason (one of the five defined reasons).

---

## Success Criteria

### Measurable Outcomes

- **SC-001**: A scan of 10 million files on reference hardware (16-core CPU, 64 GB RAM, NVMe SSD) completes at no less than 100 files/sec sustained throughput for mixed media workloads.
- **SC-002**: A scan of a 20 TB archive completes without data loss, corruption, or missed files.
- **SC-003**: After an application crash or forced termination mid-job, the system resumes successfully from its last checkpoint within 5 seconds of application restart, with no re-processing of already-completed files.
- **SC-004**: Job state is persisted (checkpoint written) within 100 milliseconds of each save trigger (every 1,000 files or 60 seconds).
- **SC-005**: 100% of files in the Failure bucket have a recorded failure reason — no file silently disappears from the source without being tracked either as transferred, skipped, or failed.
- **SC-006**: Content duplicate detection produces zero false positives (two distinct files are never incorrectly identified as duplicates) and zero false negatives (two identical files are always identified as duplicates).
- **SC-007**: The real-time dashboard updates at least once per second during active processing.
- **SC-008**: ETA accuracy is within ±20% of actual remaining time when the job is more than 10% complete.
- **SC-009**: A user can configure a scan job (select source, target, mode, and pattern), start it, pause it, and resume it — all without reading documentation, completing the full workflow in under 3 minutes.
- **SC-010**: Files are placed in the correct date-based folder for 100% of files with any recoverable date (embedded, creation, or modified) — no file with a recoverable date lands in the wrong folder.
- **SC-011**: Small-file and empty-file detection catches 100% of files at or below the configured thresholds with no false positives on valid media files just above the threshold.
- **SC-012**: After the user clicks Pause, all processing fully stops within 3 seconds regardless of current throughput or thread count.

---

## Clarifications

### Session 2026-06-03

- Q: If a file at a previously indexed path has changed since it was last hashed (size or modification timestamp differs), should the cached hash be trusted or should the file be re-hashed? → A: Re-hash if size or modification timestamp differs from the indexed record.
- Q: On resume after a crash during Move mode, if a partial copy exists at the target but the source is intact, what happens to the partial file? → A: Delete the partial target file and re-transfer the complete file from source.
- Q: If the SQLite database is missing or corrupt on startup, what should the system do? → A: Rebuild a fresh empty database, display a prominent warning that the hash cache is lost and files will be re-hashed, then allow the user to start a new job.
- Q: What is the maximum acceptable time from clicking Pause to processing fully stopping? → A: Within 3 seconds.
- Q: Should the hash index be global (shared across all jobs on the machine) or scoped per-target or per-job? → A: Global index — one shared index across all jobs on the machine; any previously hashed file is recognized as a duplicate regardless of which job processed it.

---

## Assumptions

- The application targets single-user desktop use; no concurrent multi-user session support is required for v1.
- Network-mounted drives are treated identically to local drives; no special NAS or cloud storage protocol is required in v1 (those are deferred to Phase 2 per BRD Future Enhancements).
- The user's machine has sufficient local disk space at the target directory; the application is not responsible for pre-flight disk space validation beyond reporting a clear error if a transfer fails due to insufficient space.
- Folder structure patterns are applied uniformly across all files in a job; per-file pattern overrides are out of scope.
- The `/_failures` and `/_duplicates` bucket folders are created inside the target directory, not the source directory.
- SHA-256 duplicate detection operates against a single global persistent hash index shared across all jobs on the machine. A file hashed in any prior job is recognized as a duplicate in all future jobs, regardless of source or target directory. The index does not detect duplicates that existed in the target before the first MediaScanner job ever ran.
- The application does not modify, re-encode, or alter the content of any media file — only copies, moves, or renames.
- File date extraction from video files uses the tools available (FFprobe/FFmpeg); if the tool is not found on the system, video metadata extraction falls back to filesystem dates (capture-date priority still applies).
- The ignore rules pattern matching uses glob syntax (e.g., `._*` matches any file starting with `._`).
- Phase 2 BRD enhancements (GPS-based organization, AI similarity detection, Watch Folder mode, cloud storage support, face recognition, automatic scheduling) are explicitly out of scope for this specification.
- The persistent hash index is local to the machine; there is no cloud sync or cross-machine index sharing in v1.
- The application supports Windows 10+, Windows 11, and macOS; Linux is out of scope.
