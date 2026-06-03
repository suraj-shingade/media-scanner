# Research: MediaScanner Core Engine

**Branch**: `001-media-scanner-core` | **Date**: 2026-06-03

---

## 1. Parallel Worker Pool Design

**Decision**: `ExecutorService` with a fixed thread pool sized to `Runtime.getRuntime().availableProcessors() * 2`, exposed as a user-configurable setting. A `BlockingQueue<MediaFile>` pipeline connects the scanner thread to worker threads for validation, hashing, and transfer.

**Rationale**: Java's `ExecutorService` with `LinkedBlockingQueue` is the canonical pattern for bounded parallel I/O workloads. Using `Executors.newFixedThreadPool(n)` avoids unbounded thread creation. The 2× multiplier is standard for I/O-bound work where threads block on disk reads.

**Alternatives considered**:
- Virtual threads (Java 21 Project Loom): rejected for now — virtual threads excel at network I/O; NVMe sequential disk I/O is better served by a small fixed pool to avoid thrashing the page cache.
- ForkJoinPool: rejected — designed for CPU-bound divide-and-conquer, not sequential I/O pipelines.

---

## 2. SHA-256 Hashing and Tiered Optimization (FR-025)

**Decision**: Three-stage hash gate:
1. Stage 1: Compare `file.length()` and `fileName` against index. If both differ from every indexed record → unique, skip further stages.
2. Stage 2: Read first 64 KB block, compute partial SHA-256. If no collision → unique.
3. Stage 3: Full file SHA-256 with `MessageDigest.getInstance("SHA-256")` reading in 8 MB chunks to maximize sequential disk throughput.

Hash results persisted in `FILE_HASH_INDEX` immediately after Stage 3.

**Rationale**: For a 20 TB archive with mostly unique large video files, Stage 1 eliminates the vast majority without any disk read. Stage 2 catches filename+size collisions cheaply. Full hash only when necessary — aligns with FR-025 and Constitution Principle I.

**Alternatives considered**:
- MD5: Rejected — cryptographically weak; SHA-256 is the BRD requirement.
- xxHash: Rejected — not specified in BRD; SHA-256 provides content integrity guarantee.
- Whole-file streaming without stages: Rejected — 10 GB video files would consume full disk bandwidth unnecessarily.

---

## 3. Metadata Extraction Strategy (FR-006)

**Decision**: Priority chain per file type:
- **Images**: Apache Tika (wraps Metadata Extractor) reads EXIF `DateTimeOriginal`. If absent, fallback to `BasicFileAttributes.creationTime()`, then `lastModifiedTime()`.
- **Videos**: FFprobe invoked as external process: `ffprobe -v quiet -print_format json -show_format <file>`. Parse `format.tags.creation_time`. If FFprobe absent or returns no date, fallback to filesystem dates.
- **All files**: Date normalized to `LocalDateTime` → ISO 8601 string via `DateTimeFormatter.ISO_LOCAL_DATE_TIME`.

**Rationale**: Apache Tika + Metadata Extractor is the most comprehensive Java library for EXIF across RAW formats (CR2, NEF, ARW, DNG). FFprobe is the industry standard for video container metadata and handles MTS, M4V, 3GP correctly. External process invocation avoids GPL licensing issues.

**Alternatives considered**:
- Pure Java video metadata (mp4parser): Rejected — does not support MTS/3GP formats required by FR-003.
- Reading filesystem dates only: Rejected — EXIF capture date takes priority per FR-006.

---

## 4. SQLite Schema and Migration Strategy (FR-024, Constitution III)

**Decision**: Schema version tracked in `PRAGMA user_version`. On startup, `Database.java` reads current version and runs pending migration scripts in order. Migrations are embedded as classpath resources (`/db/migrations/V001__initial_schema.sql`, etc.).

Schema adds `FILE_MODIFICATION_TS` (TIMESTAMP) to `FILE_HASH_INDEX` beyond the BRD spec to support cache invalidation (Q1 clarification).

**Rationale**: Embedded migration scripts are simpler than a full migration framework (Flyway/Liquibase) for a single-user desktop app. `PRAGMA user_version` is SQLite-native and requires no additional tables.

**Alternatives considered**:
- Flyway: Rejected — adds 10+ MB JAR dependency; overkill for a single-schema SQLite app.
- In-code `CREATE TABLE IF NOT EXISTS`: Rejected — cannot handle schema evolution without ALTER TABLE logic.

---

## 5. Job State Persistence and Checkpoint Strategy (FR-014, Constitution II)

**Decision**: `CheckpointManager` runs on a dedicated daemon thread with two triggers:
1. File counter: atomic `AtomicLong` incremented per processed file; triggers JSON write every 1,000.
2. Time-based: `ScheduledExecutorService` fires every 60 seconds as fallback.

JSON serialized via Jackson `ObjectMapper` to `~/.mediascanner/jobs/<jobId>/checkpoint.json`. SQLite `JOB_STATISTICS` row updated in the same transaction.

On startup: `Database.java` queries `JOB_STATISTICS` for any row with `status = 'RUNNING'` or `'PAUSED'`. If found, the checkpoint JSON is loaded and resume is offered within the startup timeout.

**Rationale**: Dual-trigger ensures neither a slow job (few files, lots of data) nor a fast job (many small files) misses a checkpoint. JSON is human-readable and portable for FR-015 export/import.

**Alternatives considered**:
- Single time-based trigger only: Rejected — at 1,000 files/sec, 60 s = 60,000 files potentially lost.
- Binary serialization: Rejected — FR-015 requires portability; JSON is universally readable.

---

## 6. JavaFX Real-Time Dashboard Update Strategy (FR-026–031)

**Decision**: Engine publishes progress events to a `ProgressTracker` which maintains thread-safe counters (`AtomicLong`). A `Timeline` animation on the JavaFX Application Thread fires at 1 Hz and calls `Platform.runLater()` to bind updated values to UI properties. Rolling averages use a circular buffer of the last 30 samples (5 s at 1 Hz update) and last 150 samples (30 s).

`ResourceMonitor` uses `com.sun.management.OperatingSystemMXBean` for CPU% and memory; disk R/W rates sampled via `java.io` stats delta between ticks.

**Rationale**: `Platform.runLater()` at 1 Hz satisfies SC-007 (≥ 1 update/sec) without flooding the JavaFX event queue. Circular buffer rolling average is O(1) update and O(1) query.

**Alternatives considered**:
- Direct binding to `LongProperty` updated from worker threads: Rejected — JavaFX properties are not thread-safe; causes `IllegalStateException`.
- 10 Hz update rate: Rejected — unnecessary CPU overhead for a progress dashboard; 1 Hz is sufficient.

---

## 7. Move Mode Atomicity and Partial-File Recovery (FR-004, FR-018)

**Decision**: Move = Copy + verify + delete source. Verification: after copy completes, compare `Files.size(target)` with `Files.size(source)`. Only if sizes match is the source deleted. On resume, `FileTransfer.java` checks for partial files: if `target.exists() && Files.size(target) != Files.size(source)`, delete target and re-transfer (Q2 clarification).

**Rationale**: True atomic cross-device rename is impossible on different filesystems. Size comparison post-copy is the minimum viable integrity check without a full re-hash on every moved file. This prevents the "moved but corrupt" failure mode.

**Alternatives considered**:
- Full SHA-256 verify after copy: Rejected — doubles the read I/O on every moved file; would halve throughput. Used only for duplicate detection, not transfer verification.
- `Files.move()` with `ATOMIC_MOVE`: Rejected — only atomic on same filesystem; cross-drive moves silently fall back to copy+delete without the size check.

---

## 8. High-Priority Mode Implementation (NFR-005)

**Decision**:
- **Windows**: Call `kernel32.SetPriorityClass(GetCurrentProcess(), HIGH_PRIORITY_CLASS)` via JNA (Java Native Access), already available as a lightweight dependency.
- **macOS**: Call `setpriority(PRIO_PROCESS, 0, -10)` via JNA to request higher scheduling priority (lower nice value). Requires no special entitlements for non-negative renice.

**Rationale**: JNA provides native access without JNI boilerplate. Both calls are documented, safe, and reversible. The calls are wrapped in a try/catch so failure (e.g., permission denied) degrades gracefully with a logged warning.

**Alternatives considered**:
- ProcessBuilder to shell out `nice`/`renice`: Rejected — process priority applies to child process, not the JVM itself.
- Skip High-Priority Mode: Rejected — NFR-005 is a BRD requirement.

---

## 9. Database Corruption Recovery (FR-022, Q3 Clarification)

**Decision**: On startup, `Database.java` attempts to open the SQLite file and run `PRAGMA integrity_check`. If the file is missing or `integrity_check` returns anything other than `ok`, the corrupted file is renamed to `<name>.corrupt.<timestamp>` (preserved for user inspection), a fresh empty database is created, and a startup warning dialog is shown: "Hash cache lost — all files will be re-hashed."

**Rationale**: Preserving the corrupt file lets advanced users attempt recovery with SQLite tools. A non-blocking warning (not a blocking error) lets the user continue immediately. Aligns with Q3 clarification answer.

**Alternatives considered**:
- Delete corrupt file silently: Rejected — user may want to recover data.
- Block startup until user resolves: Rejected — overly harsh for a local desktop tool.

---

## 10. Packaging and Distribution

**Decision**: `jpackage` with platform-specific targets:
- Windows: `.msi` installer via `--type msi`
- macOS: `.dmg` via `--type dmg`

Bundled JRE (Java 21) included in package to eliminate "install Java first" friction. FFmpeg/FFprobe bundled as platform binaries in `resources/ffmpeg/`.

**Rationale**: Bundled JRE is standard practice for JavaFX desktop apps since Java 9 removed the public JRE. Bundling FFmpeg ensures video metadata extraction works out of the box without user configuration.

**Alternatives considered**:
- Require system FFmpeg: Rejected — unreliable on Windows; version incompatibilities.
- GraalVM native image: Rejected — JavaFX + Apache Tika native compilation is not stable as of Java 21.
