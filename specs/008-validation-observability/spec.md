# Feature Specification: Validation & Observability Hardening

**Feature Branch**: `008-validation-observability`

**Created**: 2026-09-01

**Status**: Draft

**Input**: Close the last of the engineering audit findings. Two constitutional principles are only partially implemented: FR-012 corrupt-media detection cannot actually detect corrupt media, and FR-030 reports two of its five metrics as hardcoded zeros.

---

## Summary

Four findings from `docs/ENGINEERING-AUDIT.md` remain open, and three of them are gaps against
requirements the constitution already mandates rather than new scope:

- **FR-012 does not detect corrupt media.** `FileValidator` classifies with `Tika.detect()`, which
  reads a filename and a few magic bytes. A truncated JPEG, a half-written MP4, or a file with a valid
  header and a shredded payload — precisely the "damaged PNG, incomplete MOV" cases the FR names — all
  return `image/jpeg` or `video/mp4` and sail through the gate into the archive.
- **FR-030 reports zeros.** `getDiskReadMbSec()` and `getDiskWriteMbSec()` return fields that are
  declared, never assigned, and displayed on the dashboard. The requirement asks for disk read/write
  MB/sec.
- **FR-030 counts the wrong threads.** `activeThreads` uses `Thread.activeCount()`, which counts every
  thread in the JVM — JavaFX, logging, the checkpoint scheduler — not the scan's workers.
- **Job statistics can be read mid-update.** Counters are written inside `synchronized (jobStatistics)`
  blocks and read with no synchronisation at all, so a checkpoint can record `filesProcessed` from one
  instant and `totalBytesProcessed` from another.

This feature closes all four.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Corrupt Media Is Actually Detected (Priority: P1)

A user archives a folder recovered from a failing SD card. Several JPEGs are truncated and several MP4s
are incomplete — they open as grey static or not at all. These land in the failure bucket with a reason,
instead of being copied into the archive as if they were fine.

**Why this priority**: FR-012 and Principle VI exist to stop exactly this. An archive tool that silently
files damaged originals alongside good ones is worse than one that refuses them, because the user
discovers the loss years later when the backup is gone.

**Independent Test**: Build a source set containing a valid JPEG, a JPEG truncated to 40% of its bytes,
a JPEG with a valid header and randomised payload, a valid-extension file containing text, and a valid
PNG. Run a job. Verify the two valid images are archived and the three damaged files appear in
`_failures/failure-report.json` with distinct reasons.

**Acceptance Scenarios**:

1. **Given** a JPEG truncated partway through, **When** it is validated, **Then** it is placed in the
   failure bucket with a reason naming the decode failure.
2. **Given** an image with a valid header but corrupt pixel data, **When** it is validated, **Then** it
   is placed in the failure bucket.
3. **Given** a valid image, **When** it is validated, **Then** it passes and is transferred.
4. **Given** deep validation is disabled in preferences, **When** a truncated image is validated,
   **Then** the previous header-only behaviour applies and the file is accepted.
5. **Given** a video file, **When** `ffprobe` is unavailable on the system, **Then** the video is
   validated by header inspection only and is not failed merely because the tool is missing.
6. **Given** a job over 50 000 images, **When** deep validation is enabled, **Then** the added cost is
   proportionate — validation must remain parallel across worker threads and must not read a file more
   times than necessary.

---

### User Story 2 — Resource Metrics Tell the Truth (Priority: P2)

A user watching a slow job looks at the dashboard to see whether the bottleneck is CPU or disk. The
disk figures are real numbers that move, and the thread count reflects the workers actually running.

**Why this priority**: FR-030 lists five metrics and two of them are permanently zero, which is worse
than absent — a zero reads as "no disk activity" rather than "not measured". Lower priority than US1
because it misleads rather than loses data.

**Independent Test**: Run a job over a large source. Verify the dashboard's disk read and write figures
are non-zero and vary during the run, and that the active-thread count matches the configured worker
count while the job is running and drops to zero when it finishes.

**Acceptance Scenarios**:

1. **Given** a running job, **When** the dashboard samples resources, **Then** disk read MB/sec and
   disk write MB/sec reflect the bytes the job is actually reading and writing.
2. **Given** a running job with 16 configured workers, **When** the dashboard samples, **Then** the
   active-thread count reports the busy worker count, not the JVM total.
3. **Given** an idle application, **When** the dashboard samples, **Then** the disk figures read zero
   and the worker count reads zero.
4. **Given** the summary of a completed job, **When** it is displayed, **Then** peak and average disk
   throughput are included.

---

### User Story 3 — Checkpoints Are Internally Consistent (Priority: P3)

A checkpoint written mid-job describes one coherent instant: its file counts and byte counts agree with
each other rather than being sampled microseconds apart while workers mutate them.

**Why this priority**: The current inconsistency is real but narrow — the fields are plain `long`s so
reads are not torn, they are merely mutually stale. It matters most for a resumed job's reported
progress and for anyone reconciling a checkpoint against a report.

**Independent Test**: With many workers running, take repeated snapshots and verify that within a
snapshot the derived totals are self-consistent, and that no snapshot shows a byte count that could not
correspond to its file count.

**Acceptance Scenarios**:

1. **Given** a job with many active workers, **When** a checkpoint is written, **Then** every counter in
   it was read under the same lock acquisition.
2. **Given** the dashboard refreshing while workers run, **When** it reads statistics, **Then** it reads
   a coherent snapshot rather than individual live fields.

---

### Edge Cases

- **Deep validation cost on a huge archive.** Decoding every image is real work. It must be
  configurable, parallel, and must not add a second read of the file where the existing pipeline
  already reads it.
- **Images ImageIO cannot decode but which are valid** — HEIC, RAW, CR2, NEF, ARW, DNG. Java has no
  built-in reader for these. They must not be failed merely because the JDK cannot decode them; they
  fall back to header validation.
- **Very large videos.** Deep video validation must not read the whole file.
- **`ffprobe` missing** is the normal case on a clean machine, not an error.
- **Disk metrics are per-job, not system-wide.** The figures describe what this application is doing,
  which is what a user watching their own job wants; they are not a system I/O monitor.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-008-001**: The system MUST detect images that cannot be fully decoded and place them in the
  failure bucket with a specific reason (closes FR-012 for images).
- **FR-008-002**: The system MUST detect videos whose container cannot be parsed, using `ffprobe` when
  available (closes FR-012 for videos).
- **FR-008-003**: Deep validation MUST be user-configurable and default to enabled.
- **FR-008-004**: A format the JDK cannot decode MUST NOT be failed for that reason alone.
- **FR-008-005**: Deep validation MUST NOT introduce an additional full read of a file that the
  pipeline already reads.
- **FR-008-006**: The system MUST report disk read and write throughput reflecting the job's actual
  I/O (closes FR-030).
- **FR-008-007**: The system MUST report the count of scan workers actually running.
- **FR-008-008**: Peak and average disk throughput MUST appear in the end-of-job summary.
- **FR-008-009**: Job statistics MUST be readable as a single coherent snapshot.

---

## Success Criteria *(mandatory)*

- **SC-001**: A truncated or payload-corrupted image is always placed in the failure bucket, never
  archived.
- **SC-002**: A valid image is never failed by deep validation.
- **SC-003**: An image format the JDK cannot decode is never failed for that reason.
- **SC-004**: Deep validation adds no more than one decode pass per file and remains parallel.
- **SC-005**: Disk read/write figures are non-zero during a job with real I/O.
- **SC-006**: The reported worker count never exceeds the configured thread count.
- **SC-007**: Every counter in a checkpoint comes from one lock acquisition.

---

## Assumptions

- "Corrupt" means "cannot be decoded by the tools available". A file that decodes but looks wrong to a
  human is out of scope.
- Disk metrics describe this application's I/O, not the system's. Reporting system-wide disk activity
  would need a native library, which the locked technology stack does not include.
- Deep validation reuses the read the hash stage already performs where possible.

## Dependencies

- No new third-party dependencies. The technology stack is locked by the constitution.
- Builds on features 005 and 007, both merged to `main`.
