# Quickstart: Cutting a MediaScanner Release

**Feature**: 004-github-actions-release | **Date**: 2026-06-04

This guide covers how to publish a new versioned release of MediaScanner after the release pipeline is in place.

---

## Prerequisites

- Push access to the `main` branch of the GitHub repository
- The code on `main` is in a releasable state (all tests passing, no known blocking bugs)
- The `GITHUB_TOKEN` secret is available in the repository (automatically provided by GitHub Actions — no setup required)

---

## Releasing a New Version

### Step 1 — Decide the version number

Follow semantic versioning (`MAJOR.MINOR.PATCH`):

| Change type | Example |
|-------------|---------|
| Bug fix only | `1.0.0` → `1.0.1` |
| New feature, backwards-compatible | `1.0.0` → `1.1.0` |
| Breaking change | `1.0.0` → `2.0.0` |

### Step 2 — Create and push the version tag

```bash
# Replace 1.2.3 with your actual version
git tag v1.2.3
git push origin v1.2.3
```

That's all. The pipeline starts automatically.

### Step 3 — Monitor the pipeline

Navigate to the repository's **Actions** tab on GitHub. You will see a workflow run named after your tag (e.g., `v1.2.3`). Two parallel jobs run first (`build-mac`, `build-win`), followed by `publish-release`.

Typical run time: 15–25 minutes.

### Step 4 — Verify the release

Once the pipeline succeeds, go to the repository's **Releases** page. Confirm:

- The release title is `MediaScanner 1.2.3`
- Four files are attached: `.dmg`, `.dmg.sha256`, `.msi`, `.msi.sha256`
- The release is marked as **Latest**

Download and install each artifact on a clean machine to confirm the version number matches.

---

## If the Pipeline Fails

1. Go to **Actions → [the failed run]** and expand the failed job to read the error output.
2. Fix the root cause (compilation error, jpackage error, WiX missing, etc.).
3. Delete the failed tag locally and remotely, then re-tag from the fixed commit:

```bash
git tag -d v1.2.3           # delete local tag
git push origin :v1.2.3     # delete remote tag
# make your fix, commit it
git tag v1.2.3
git push origin v1.2.3
```

If a partial release was accidentally created, it will be automatically replaced when the new tag push triggers a fresh pipeline run (FR-013).

---

## Re-releasing the Same Version

If you need to republish the same version (e.g., after a pipeline infrastructure fix with no code change), simply delete and re-push the tag:

```bash
git tag -d v1.2.3
git push origin :v1.2.3
git tag v1.2.3
git push origin v1.2.3
```

The pipeline will delete the existing GitHub Release and publish a fresh one.

---

## Verifying a Download's Integrity (for end users)

**macOS**:
```bash
shasum -a 256 -c MediaScanner-1.2.3.dmg.sha256
```

**Windows (PowerShell)**:
```powershell
$hash = (Get-FileHash MediaScanner-1.2.3.msi -Algorithm SHA256).Hash.ToLower()
$expected = (Get-Content MediaScanner-1.2.3.msi.sha256 -Raw).Split(" ")[0].Trim()
if ($hash -eq $expected) { "VERIFIED" } else { "MISMATCH — do not install" }
```

---

## Artifact Naming Reference

| File | Contents |
|------|----------|
| `MediaScanner-{VERSION}.dmg` | macOS installer (Universal Binary: ARM64 + x86_64) |
| `MediaScanner-{VERSION}.dmg.sha256` | SHA-256 checksum for the DMG |
| `MediaScanner-{VERSION}.msi` | Windows installer (x64) |
| `MediaScanner-{VERSION}.msi.sha256` | SHA-256 checksum for the MSI |
