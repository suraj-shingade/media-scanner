<p align="center">
  <img src="docs/branding/wordmark.png" alt="MediaScanner" width="520">
</p>

<p align="center">
  Organise large photo and video archives into a clean, date-based folder structure —
  without touching your originals unless you ask.
</p>

---

## What it does

Point MediaScanner at a source folder and a destination. It walks the source recursively, validates
every file, reads the capture date, and files each photo or video into a dated folder — copying by
default, never moving unless you choose to.

- **Built for scale.** Designed against 10M-file, 20+ TB archives: parallel workers, a bounded work
  queue, an on-disk hash index, and per-thread database connections.
- **Never loses data.** Copy preserves originals. Move deletes only after a verified transfer.
  Duplicates are skipped, not deleted.
- **Resumable.** A job interrupted by a crash or a power cut is completed by re-running it. Files
  already in the archive are recognised and left alone.
- **Auditable.** Every skipped, failed and duplicated file is written to a report inside the archive,
  with its full path and the specific reason.
- **Honest about damage.** Truncated and corrupt media are detected by decoding, not by trusting the
  file extension, and are sent to a failure bucket rather than into your archive.

## Screens

| | |
|---|---|
| **Configuration** | Pick source, target, transfer mode, folder pattern and duplicate policy |
| **Dashboard** | Live counts, throughput, ETA, resource use, and a throughput chart |
| **Summary** | The full end-of-job record, exportable as JSON, CSV or a self-contained HTML page |
| **Job History** | Every job ever run, browsable after restart, with its stored throughput chart |
| **Cleanup** | Permanently delete non-media files by detected type, behind a preview-and-confirm gate |

## Reports

A job that skips, fails or deduplicates anything leaves a record in the target archive:

```
<archive>/_skipped/skipped-report.json
<archive>/_failures/failure-report.json
<archive>/_duplicates/duplicate-report.json
```

Each lists the full source path, the size, and the specific reason. Reports are written per job, so a
second job against the same archive does not overwrite the first one's record.

## Building

```bash
./mvnw clean verify          # compile and run the full test suite
./mvnw javafx:run            # run from source
./mvnw package -P package-win   # Windows .msi   (needs WiX)
./mvnw package -P package-mac   # macOS .dmg
```

Java 21 is required. The Maven wrapper downloads Maven itself, so nothing else needs installing.

See [docs/INSTALL.md](docs/INSTALL.md) for installing a release, and
[docs/ENGINEERING-AUDIT.md](docs/ENGINEERING-AUDIT.md) for the current state of known issues.

## Project layout

```
specs/            One folder per feature: spec, plan, research, data model, tasks
.specify/memory/  Constitution (the rules this project is held to) and the development tracker
src/main/java/    Application source
src/test/java/    Unit tests (*Test) and integration tests (*IT)
docs/             Install guide, engineering audit, branding assets
```

Development follows [Spec Kit](https://github.com/github/spec-kit): every feature begins as a
specification with independently testable user stories, and is checked against the project
constitution in `.specify/memory/constitution.md` before implementation.
