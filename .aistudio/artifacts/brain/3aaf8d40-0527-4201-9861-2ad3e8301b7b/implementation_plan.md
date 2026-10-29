# StayFrame — Production Deployment & Release Pipeline Plan

StayFrame is a privacy-first, on-device Android utility that pauses, rewinds, and resumes video playback based on camera face presence. This deployment plan defines the complete production engineering roadmap, including GitHub Actions CI/CD workflows, secure release keystore handling, direct signed APK distribution infrastructure with checksum manifests, automated rollback procedures, and a 4-week milestone schedule leading to public launch.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The following parameters were confirmed during requirements alignment:

- **Confirmed Decision 1 (CI/CD Automation Platform)**: **GitHub Actions** will orchestrate automated validation, unit/Robolectric test execution, release APK assembly, ProGuard minification checks, and GitHub Releases asset distribution.
- **Confirmed Decision 2 (Distribution Channel)**: **Direct signed APK distribution** via GitHub Releases / CDN object storage with automated SHA-256 integrity verification and an `update-manifest.json` endpoint for in-app update checks.
- **Confirmed Decision 3 (Release Schedule)**: **4-week phased release cycle** spanning Toolchain & Pipeline Setup (Week 1), Dogfood & Alpha Testing (Week 2), Closed Beta & Stress Testing (Week 3), and General Availability (Week 4).

---

## 1. Overview & Core Concept

### Delivery Objective
Deliver a repeatable, reproducible, and zero-touch deployment pipeline that automates the release of production-grade, signed Android APKs with semantic versioning (`StayFrame-vX.Y.Z-release.apk`), cryptographic checksums (`.sha256`), and instant rollback capability without third-party app store approval latency.

### Deployment Guarantees
1. **Reproducibility**: Builds execute in hermetic GitHub Actions container environments running JDK 17 and pinned Android SDK platforms (API 36).
2. **Security & Integrity**: Release signing keys are injected solely at CI runtime via GitHub Secrets; APK binaries are signed with v2/v3 signing schemes and published alongside SHA-256 manifests.
3. **Fail-Closed Verification**: Commits must pass static verification, Room compiler schema validation, Robolectric tests, and ProGuard minification checks before binary release assets are produced.
4. **Resilient Rollback**: A dual-layer rollback strategy providing both emergency release retraction via distribution API updates and rapid hotfix cherry-picking (`vX.Y.Z+1`) within 30 minutes.

---

## 2. Pipeline Architecture & Infrastructure Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│                        DEVELOPER / GIT REPOSITORY                      │
│   Feature Branch  ──►  Pull Request  ──►  Tag Release (v1.0.0)         │
└───────────────────────────────┬────────────────────────────────────────┘
                                │ Webhook Trigger
┌───────────────────────────────▼────────────────────────────────────────┐
│                      GITHUB ACTIONS CI/CD ENGINE                       │
│                                                                        │
│   STAGE 1: Quality Gate & Validation                                   │
│   ├── Gradle Cache Restoration (.gradle/caches)                        │
│   ├── Dependency Resolution & Integrity Verification                   │
│   ├── Spotless / Lint / Room Schema Validation                         │
│   └── Robolectric Test Suite (:app:testDebugUnitTest)                  │
│                                                                        │
│   STAGE 2: Production Build & Minification                             │
│   ├── Keystore Decryption from Repository Secrets                      │
│   ├── ProGuard Obfuscation & R8 Minification (:app:assembleRelease)   │
│   ├── Semantic Version Injection (v1.0.0)                              │
│   └── Checksum Generation (sha256sum)                                  │
│                                                                        │
│   STAGE 3: Distribution & Asset Artifacts                              │
│   ├── GitHub Releases Tagged Release Asset Creation                    │
│   ├── CDN Object Storage Sync (S3/Cloud Storage)                       │
│   └── In-App Update Manifest Generation (update-manifest.json)         │
└───────────────────────────────┬────────────────────────────────────────┘
                                │
┌───────────────────────────────▼────────────────────────────────────────┐
│                   DISTRIBUTION & CLIENT ECOSYSTEM                      │
│  ┌─────────────────────────┐  ┌─────────────────────────────────────┐  │
│  │    GitHub Releases      │  │      Direct Download / CDN          │  │
│  │  StayFrame-v1.0.0.apk   │  │  https://releases.stayframe.app/    │  │
│  │  StayFrame-v1.0.0.sha256│  │  update-manifest.json               │  │
│  └───────────┬─────────────┘  └──────────────────┬──────────────────┘  │
│              │                                   │                     │
│              ▼                                   ▼                     │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                 StayFrame Android Client Runtime                 │  │
│  │  • Verifies SHA-256 before package installer handoff             │  │
│  │  • Enforces Package Signature Identity matching previous build   │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Technical Deliverables by Pipeline Stage

### Stage A: CI/CD Automation (GitHub Actions)
- **Workflow Specification (`.github/workflows/ci.yml`)**:
  - Triggers on all pull requests and merges to `main`.
  - Configures OpenJDK 17, caches Gradle packages (`~/.gradle/caches`, `~/.gradle/wrapper`).
  - Executes dependency check (`make install-deps`) and unit tests (`gradle :app:testDebugUnitTest`).
- **Release Workflow Specification (`.github/workflows/release.yml`)**:
  - Triggers on Git tags matching `v*.*.*`.
  - Extracts semantic version from Git ref and asserts parity with `app/build.gradle.kts`.
  - Injects `STORE_PASSWORD`, `KEY_PASSWORD`, and `KEYSTORE_BASE64` into volatile runner storage.
  - Executes `./build_apk.sh release`.
  - Generates `StayFrame-vX.Y.Z-release.apk` and `StayFrame-vX.Y.Z-release.apk.sha256`.
  - Automatically publishes a GitHub Release with changelog and attached binaries.

### Stage B: Infrastructure & Secrets Management
- **Key Storage & Cryptography**:
  - Production upload keystore (`my-upload-key.jks`) generated with RSA 4096-bit key and 25-year validity.
  - Keystore base64-encoded and stored inside GitHub Repository Secrets (`KEYSTORE_BASE64`).
  - Passwords stored in `STORE_PASSWORD` and `KEY_PASSWORD`.
  - Build script automatically reconstructs temporary keystore and safely shreds it post-build.
- **Hosting & Update Manifest Service**:
  - Direct download endpoint hosting static artifacts:
    - `StayFrame-vX.Y.Z-release.apk`
    - `StayFrame-vX.Y.Z-release.apk.sha256`
    - `update-manifest.json` (defines `latest_version`, `version_code`, `download_url`, `sha256`, `min_supported_version`, `critical_update`).
  - Cloudflare/CDN edge distribution with cache invalidation on new tag release.

### Stage C: Code Deployment & Packaging
- **R8 / ProGuard Optimization**:
  - Enabled `isMinifyEnabled = true` with tested keep rules (`proguard-rules.pro`) for ML Kit Face Detection, CameraX, and MediaSession.
  - Symbol mapping file (`app/build/outputs/mapping/release/mapping.txt`) archived as a private CI artifact for deobfuscation of production crashes.
- **Deterministic Checksums**:
  - Generation of SHA-256 integrity hash during build time (`sha256sum`).
  - Users and automated update checkers verify checksum prior to triggering Android package manager install prompt.

### Stage D: Rollback Strategy & Incident Handling
- **Immediate Mitigation (Update Manifest Killswitch)**:
  - If a critical defect or regression is identified post-release, the CDN `update-manifest.json` is updated within 5 minutes to set `latest_version` back to the last known stable release and toggle `deprecated_versions: ["1.0.0"]`.
  - The client displays an informational banner advising the user to revert or pause background monitoring.
- **Emergency Hotfix Pipeline**:
  - Fast-track cherry-pick workflow: Hotfix PR directly merged to `main`, tagged `v1.0.1`, triggering immediate 8-minute automated CI build and distribution.
- **Android Downgrade Protection Compatibility**:
  - Android OS security prevents installing an APK with a lower `versionCode` over a higher `versionCode` without user data wipe.
  - Therefore, rollbacks are always deployed forward as a fast-track build with incremented `versionCode` containing the reverted codebase.

---

## 4. 4-Week Milestone Timeline Leading to Production

| Week | Milestone | Focus Areas | Key Technical Deliverables | Gate Criteria |
|---|---|---|---|---|
| **Week 1** | **CI/CD Foundation & Keystore Setup** | Automation & Infrastructure | - `.github/workflows/ci.yml` & `release.yml`<br>- Production keystore generation & GitHub Secrets configuration<br>- CDN bucket & `update-manifest.json` schema setup<br>- Release ProGuard mapping archive task | Clean CI run on test tags; release APK successfully builds and signs in under 6 minutes. |
| **Week 2** | **Internal Dogfooding & Alpha Phase** | Hardware & Media Interop | - Distribute v1.0.0-alpha.1 to 10 internal test devices<br>- Battery drain testing during active 1 FPS monitoring (target < 3.5% battery/hr)<br>- Media session transport controls testing on YouTube, VLC, MX Player, Chrome<br>- Device restart and screen timeout restore test | 0 crash reports; camera release cleanly handled across all app-switch scenarios. |
| **Week 3** | **Closed Beta & Stress Testing** | Scale & Edge Cases | - Onboard 50 external beta testers via direct APK link<br>- Diverse lighting testing (low-light, backlight, multi-face)<br>- In-app update check integration and update-manifest polling<br>- Automated mock rollback drill executed on staging endpoint | Absence grace auto-rewind precision confirmed; 0 false resumes in relaxed mode; rollback drill completed in < 15 mins. |
| **Week 4** | **Production Release (v1.0.0 GA)** | Public Launch & Monitoring | - Final version bump (`versionCode 1`, `versionName 1.0.0`)<br>- Tag `v1.0.0` triggering production CI build<br>- Deploy production release to GitHub Releases & CDN<br>- Publish release notes, installation documentation, and SHA-256 verification guide | All automated tests passing; production APK verified on Android 10 through 15; checksum verified on CDN. |

---

## 5. Verification & Risk Mitigation Matrix

| Risk Factor | Probability | Impact | Mitigation Strategy |
|---|---|---|---|
| **Keystore Compromise or Loss** | Very Low | Critical | Keystore backed up in encrypted cold vault; credentials strictly scoped to CI runner memory. |
| **ProGuard Aggressive Stripping** | Medium | High | Automated Robolectric tests execute against release configuration; ML Kit and CameraX classes protected in `proguard-rules.pro`. |
| **Android Version Incompatibility (API 24 to 36)** | Medium | Medium | Automated CI matrix tests API 24 (Nougat), API 30 (R), and API 34+ (UpsideDownCake) edge cases. |
| **Broken Update Manifest Distribution** | Low | High | CDN caching with 60-second TTL on manifest; static fallback mirror on GitHub Pages / GitHub Releases. |
| **Camera Lockout on Force-Stop** | Low | Medium | Service explicitly registers unbind and shutdown hooks in `onDestroy` and handles lifecycle disconnection gracefully. |
