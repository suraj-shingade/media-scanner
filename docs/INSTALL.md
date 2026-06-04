# MediaScanner — Installation Guide

## macOS

### System Requirements

- macOS 12 (Monterey) or later
- Apple Silicon (M1/M2/M3) or Intel Mac
- No Java installation required — the JVM is bundled in the installer

### Install Steps

1. Download `MediaScanner-<version>.dmg`
2. Double-click the DMG to mount it — a Finder window opens
3. Drag `MediaScanner.app` into the **Applications** folder shown in the window
4. Eject the DMG (drag it to Trash or press ⌘E)
5. Open `MediaScanner` from your Applications folder or Launchpad

### macOS Gatekeeper Bypass

Because MediaScanner v1 is distributed **unsigned**, macOS Gatekeeper will block the first launch:

> "MediaScanner" cannot be opened because it is from an unidentified developer.

**To bypass Gatekeeper:**

1. In Finder, navigate to **Applications**
2. Right-click (or Control-click) `MediaScanner.app`
3. Select **Open** from the context menu
4. In the dialog that appears, click **Open** again to confirm

After doing this once, MediaScanner will open normally from the Dock or Launchpad.

**Alternative (Terminal):**

```bash
xattr -d com.apple.quarantine /Applications/MediaScanner.app
```

### Uninstall

Drag `MediaScanner.app` from Applications to Trash. User data and settings are stored in `~/.mediascanner/` — delete that folder to remove all application data.

---

## Windows

### System Requirements

- Windows 10 (64-bit) or Windows 11
- No Java installation required — the JVM is bundled in the installer

### Install Steps

1. Download `MediaScanner-<version>.msi`
2. Double-click the `.msi` file
3. If prompted by User Account Control, click **Yes**
4. Follow the installation wizard:
   - Choose an installation directory (default is `C:\Program Files\MediaScanner\`)
   - Click **Install**
5. Click **Finish** when complete
6. Launch MediaScanner from the **Start Menu** under the **MediaScanner** group, or from the Desktop shortcut

### Uninstall

Open **Settings → Apps** (or **Control Panel → Programs and Features**), find **MediaScanner**, and click **Uninstall**.

User data and settings are stored in `%USERPROFILE%\.mediascanner\` — delete that folder to remove all application data.

---

## Build Prerequisites (Developers)

See [`specs/003-installable-builds/quickstart.md`](../specs/003-installable-builds/quickstart.md) for full developer build instructions.

### macOS Build Machine

| Requirement | Version | Notes |
|---|---|---|
| Azul Zulu JDK 21 Universal | 21.x | Must be `JAVA_HOME` for Universal Binary output |
| Maven | 3.8+ | Standard project build tool |

> **Important**: Using any other JDK (including Microsoft JDK 21 or Temurin) produces an architecture-specific binary instead of a Universal Binary. Only Azul Zulu JDK 21 Universal produces a true ARM64 + x86_64 fat binary.

### Windows Build Machine

| Requirement | Version | Notes |
|---|---|---|
| JDK 21 (any vendor, x64) | 21.x | Must be `JAVA_HOME` |
| WiX Toolset | v3.x (NOT v4) | Required by jpackage for MSI generation |
| Maven | 3.8+ | Standard project build tool |
