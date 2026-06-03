# Quickstart: MediaScanner Core Engine

**Branch**: `001-media-scanner-core` | **Date**: 2026-06-03

This guide covers the developer setup, build, test, and run workflow for the MediaScanner project.

---

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Java JDK | 21 LTS | Build and run |
| Maven | 3.9+ | Build tool |
| FFmpeg + FFprobe | 6.x | Video metadata extraction (bundled in release; install locally for dev) |
| Git | any | Version control |

Install FFmpeg locally for development:
- **macOS**: `brew install ffmpeg`
- **Windows**: Download from ffmpeg.org and add to PATH

---

## Build

```bash
# Clone and build
git clone <repo-url>
cd MediaScanner
mvn clean package -DskipTests

# Run tests
mvn test

# Run integration tests only
mvn test -Dtest="*IT"

# Run with JavaFX (development mode)
mvn javafx:run
```

---

## Project Layout

```text
src/main/java/com/mediascanner/
├── app/            # Application entry point
├── ui/             # JavaFX controllers + FXML
├── engine/         # Core scan/validate/hash/transfer pipeline
├── model/          # Domain entities
├── db/             # SQLite DAOs and migration runner
├── checkpoint/     # Job state persistence
├── monitor/        # Real-time metrics and resource monitoring
└── config/         # User settings and ignore rules

src/test/java/com/mediascanner/
├── engine/         # Unit tests per engine component
├── db/             # Integration tests (real SQLite)
├── checkpoint/     # Checkpoint write/read tests
└── monitor/        # Metrics calculation tests

resources/
├── fxml/           # JavaFX layout files
├── css/            # Styling
├── db/migrations/  # SQL migration scripts (V001__initial_schema.sql, ...)
└── logback.xml     # Logging configuration
```

---

## Database Location

The SQLite database is stored at:
- **macOS**: `~/.mediascanner/mediascanner.db`
- **Windows**: `%USERPROFILE%\.mediascanner\mediascanner.db`

Job checkpoint files:
- `~/.mediascanner/jobs/<jobId>/checkpoint.json`

To reset the database during development:
```bash
rm -f ~/.mediascanner/mediascanner.db
```

---

## Running Tests

```bash
# All unit tests
mvn test -Dtest="*Test"

# All integration tests (requires writable temp directory)
mvn test -Dtest="*IT"

# Specific engine component
mvn test -Dtest="HashEngineTest"

# With coverage report
mvn test jacoco:report
# Report at: target/site/jacoco/index.html
```

Integration tests create a temporary SQLite database in `target/test-db/` and clean up after each test class.

---

## Key Configuration

`AppConfig.java` reads from `~/.mediascanner/config.properties` (created on first run with defaults):

```properties
# Worker thread count (default: availableProcessors * 2)
worker.thread.count=0

# Image small-file threshold in KB
validation.image.min.kb=10

# Video small-file threshold in KB
validation.video.min.kb=100

# High-priority mode
performance.high.priority=false

# Default folder structure pattern (YYYY_MMM, YYYY_MM, YYYY_MMM_DD, YYYY_MM_DD)
folder.pattern=YYYY_MMM

# Default duplicate policy (SKIP, MOVE_TO_BUCKET, KEEP_BOTH)
duplicate.policy=SKIP
```

---

## Packaging for Distribution

```bash
# Build native installer (requires jpackage in JDK 21)
# macOS .dmg:
mvn package -P release
jpackage --type dmg --input target/ --main-jar mediascanner.jar \
  --main-class com.mediascanner.app.MediaScannerApp \
  --name MediaScanner --app-version 1.0.0

# Windows .msi (run on Windows):
jpackage --type msi --input target/ --main-jar mediascanner.jar \
  --main-class com.mediascanner.app.MediaScannerApp \
  --name MediaScanner --app-version 1.0.0
```

Bundled JRE and FFmpeg binaries are included automatically via the Maven assembly configuration.

---

## Acceptance Test Checklist (per story)

Run these manually to validate each user story before marking it Done (Gate G4):

| Story | Test |
|-------|------|
| US1 | Copy 100 mixed files → verify year/month folders created correctly |
| US2 | Source with 5 nesting levels + `.DS_Store` + `.pdf` → verify only media files queued |
| US3 | 0-byte jpg + 5KB jpg + corrupt mp4 + 10 valid → verify bucket counts match |
| US4 | 3 files (EXIF / creation / modified date only) → verify each lands in correct folder |
| US5 | Same-name collision + content duplicate + unique file → verify all three handled correctly |
| US6 | 50K file job → pause at ~10K → close app → reopen → resume → verify count continuity |
| US7 | 100K file job → dashboard shows updating counts, ETA, CPU%, thread count |
| US8 | Completed job → summary shows all counts, data volumes, peak/avg throughput |
| US9 | 16-core machine → default thread count = 32; change to 8 → verify applied |

---

## Performance Benchmark Targets (Gate G4 for I/O stories)

Run against the reference dataset on reference hardware (16-core, 64 GB, NVMe):

| Workload | Target |
|----------|--------|
| Small files (< 1 MB each) | ≥ 200 files/sec |
| Mixed media | ≥ 100 files/sec |
| Large video (> 1 GB each) | ≥ 1 GB/sec disk throughput |
| Pause response | ≤ 3 seconds |
| Checkpoint write | ≤ 100 ms |
| Resume detection on startup | ≤ 5 seconds |
