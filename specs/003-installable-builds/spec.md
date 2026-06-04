# Feature Specification: Native Installer Packaging

**Feature Branch**: `003-installable-builds`

**Created**: 2026-06-03

**Status**: Draft

**Input**: User description: "Create a feature which will help us produce this application build as installable on mac and windows"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Build macOS Installer (Priority: P1)

A developer runs a single build command that produces a macOS `.dmg` or `.pkg` installer for MediaScanner. The installer bundles the JVM, all dependencies, and the application into a self-contained package that end users can install without having Java pre-installed.

**Why this priority**: macOS is a primary target platform per the project constitution. Delivering a self-contained installer removes the JVM installation barrier for end users.

**Independent Test**: Run the build command on macOS and verify a distributable installer file is produced that installs and launches the application on a clean macOS machine.

**Acceptance Scenarios**:

1. **Given** a developer runs the package command on macOS, **When** the build completes, **Then** a `.dmg` or `.pkg` installer file is produced in the output directory.
2. **Given** the macOS installer file, **When** installed on a machine without a pre-existing JVM, **Then** MediaScanner launches correctly.
3. **Given** the macOS installer, **When** the user opens it, **Then** the application appears in the Applications folder with the correct app name and icon.

---

### User Story 2 - Build Windows Installer (Priority: P1)

A developer runs a single build command that produces a Windows `.msi` or `.exe` installer for MediaScanner. The installer bundles the JVM, all dependencies, and the application so end users on Windows 10+ and Windows 11 can install it without a pre-existing JVM.

**Why this priority**: Windows 10+ and Windows 11 are primary target platforms per the project constitution. Self-contained installers are required for distribution.

**Independent Test**: Run the build command on Windows (or via cross-compilation if supported) and verify a `.msi` or `.exe` installer is produced that installs and launches on a clean Windows machine.

**Acceptance Scenarios**:

1. **Given** a developer runs the package command targeting Windows, **When** the build completes, **Then** a `.msi` or `.exe` installer file is produced in the output directory.
2. **Given** the Windows installer file, **When** installed on a Windows 10+ machine without a pre-existing JVM, **Then** MediaScanner launches correctly.
3. **Given** the Windows installer, **When** completed, **Then** the application appears in the Start Menu and optionally on the Desktop with the correct app name and icon.

---

### User Story 3 - Reproducible Version-Stamped Build (Priority: P2)

Each installer build is stamped with the application version so that distributed installers are identifiable. The version appears in the installer file name, the About dialog, and the platform's app metadata (e.g., Windows Add/Remove Programs, macOS Info.plist).

**Why this priority**: Version stamping is essential for support, update management, and release tracking once the app is distributed to end users.

**Independent Test**: Build an installer with a specified version string and verify the version appears consistently in the installer metadata and the running application.

**Acceptance Scenarios**:

1. **Given** a build with version `1.0.0`, **When** the installer is produced, **Then** the installer file name includes `1.0.0`.
2. **Given** an installed application, **When** the user opens the About screen, **Then** the version `1.0.0` is displayed.
3. **Given** an installed application on Windows, **When** viewed in Add/Remove Programs, **Then** the version `1.0.0` is shown.

---

### User Story 4 - Single Maven Command Build (Priority: P2)

A developer triggers the full packaging pipeline — compile, test, assemble, and package — using a single Maven command or Maven profile, without needing to manually run separate tools.

**Why this priority**: A one-command build reduces developer friction and enables CI/CD integration in the future.

**Independent Test**: Execute the documented Maven command on a clean checkout and verify the installer is produced end-to-end without manual intervention.

**Acceptance Scenarios**:

1. **Given** a clean project checkout with Maven and the correct JDK installed, **When** the developer runs the documented single command, **Then** the installer is produced with no additional manual steps.
2. **Given** a build failure during compilation or testing, **When** the package command is run, **Then** the build fails with a clear error message and no partial installer is produced.

---

### Edge Cases

- What happens when the signing certificate (macOS code signing / Windows Authenticode) is not available? The build should succeed and produce an unsigned installer, with a clear warning in the build output.
- What happens if the required JDK version (Java 21) is not installed on the build machine? The build should fail immediately with a clear, actionable error message.
- What happens when the application icon file is missing? The build should either fail with a descriptive error or fall back to a default icon, with a warning.
- What happens if the output directory already contains an installer from a previous build? The new build should overwrite it without error.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The build system MUST produce a self-contained macOS installer (`.dmg` or `.pkg`) that bundles the JVM and all application dependencies.
- **FR-002**: The build system MUST produce a self-contained Windows installer (`.msi` or `.exe`) that bundles the JVM and all application dependencies.
- **FR-003**: Installers MUST NOT require a pre-existing JVM installation on the end-user's machine.
- **FR-004**: The application MUST be launchable via a standard desktop shortcut or Start Menu entry after installation.
- **FR-005**: Each installer MUST be stamped with the application version, visible in the file name and platform metadata.
- **FR-006**: The version MUST be displayed in the application's About screen after installation.
- **FR-007**: The packaging pipeline MUST be invocable via a single Maven command or Maven profile.
- **FR-008**: The build MUST fail fast with a clear error if required prerequisites (JDK 21, packaging tools) are missing.
- **FR-009**: The macOS installer for v1 MUST be unsigned. The build MUST emit a warning noting the installer is unsigned, and the project documentation MUST include steps for users to bypass macOS Gatekeeper (right-click → Open). Apple Developer ID signing is explicitly deferred to a future release.
- **FR-010**: The installer MUST include the application icon for macOS (`.icns`) and Windows (`.ico`) platforms.
- **FR-011**: The macOS installer MUST target macOS 12 (Monterey) and later.
- **FR-013**: The macOS installer MUST be a Universal Binary supporting both ARM64 (Apple Silicon) and x86_64 (Intel) architectures natively.
- **FR-012**: The Windows installer MUST target Windows 10 and Windows 11.

### Key Entities

- **Installer Artifact**: The distributable file (`.dmg`, `.pkg`, `.msi`, or `.exe`) produced by the build pipeline for a given platform and version.
- **Application Bundle**: The self-contained directory containing the JVM runtime, application JAR, native libraries, and launcher scripts that the installer deploys.
- **Build Profile**: A named Maven configuration that activates the packaging pipeline for a specific platform (macOS or Windows).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer with the correct JDK and build tools can produce a macOS installer in under 5 minutes on a modern development machine.
- **SC-002**: A developer with the correct JDK and build tools can produce a Windows installer in under 5 minutes on a modern development machine.
- **SC-003**: The produced installer passes installation on a clean target OS instance (no JVM pre-installed) with a 100% success rate in manual verification.
- **SC-004**: The application launches within 5 seconds after being opened from the installed location on both macOS and Windows.
- **SC-005**: The correct version string appears in all three locations (installer metadata, file name, About screen) with zero discrepancy.
- **SC-006**: The packaging pipeline is triggered by a single documented command with no additional manual steps required.

## Clarifications

### Session 2026-06-03

- Q: Should the macOS installer target ARM64 only, x86_64 only, or a Universal Binary? → A: Universal Binary (ARM64 + x86_64), supporting both Apple Silicon and Intel Macs natively.
- Q: Should the macOS installer require Apple Developer ID signing/notarization, or is unsigned with Gatekeeper bypass docs acceptable for v1? → A: Unsigned for v1; documentation for Gatekeeper bypass (right-click → Open) is required; code signing deferred to a future release.
- Q: Should the packaging pipeline be validated for CI/CD (GitHub Actions) as part of this feature, or developer-machine only for v1? → A: Developer-machine only for v1; CI/CD integration explicitly deferred to a future feature.

## Assumptions

- The project already builds successfully via Maven (`mvn package`) on the developer's machine.
- Java 21 JDK is available on the build machine; the JRE bundled into the installer is sourced from this JDK.
- Platform-native packaging tools (`jpackage`, which ships with JDK 14+) are the primary packaging mechanism, consistent with the constitution's "Packaging: jpackage" constraint.
- macOS v1 installer is unsigned; users must bypass Gatekeeper manually (right-click → Open). Apple Developer ID signing is deferred to a future release. Windows code signing (Authenticode) is also out of scope for v1.
- Cross-platform builds (e.g., building a Windows installer from macOS) are out of scope for v1; each platform's installer is built on its respective OS.
- CI/CD pipeline integration (e.g., GitHub Actions workflows) is explicitly out of scope for v1; the packaging pipeline is validated for developer-machine use only.
- Application icons in the correct platform formats (`.icns` for macOS, `.ico` for Windows) will be provided or already exist in the project.
- The `1.0.0` version string is the initial release version; the versioning scheme follows semantic versioning.
- macOS minimum target is macOS 12 (Monterey); older macOS versions are out of scope.
- The macOS installer is a Universal Binary; separate ARM64 and x86_64 builds are not required.
- The Windows installer format is `.msi` (preferred for enterprise environments); an `.exe` wrapper is a secondary option.
