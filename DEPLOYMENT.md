# StayFrame Production Deployment Guide

This document outlines the CI/CD pipeline, signing infrastructure, distribution setup, and rollback protocols for StayFrame.

---

## 1. CI/CD Architecture Overview

StayFrame uses **GitHub Actions** for automated quality gates and production releases.

### Workflows

- **`.github/workflows/ci.yml`**:
  - Triggers on: Pull Requests & pushes to `main`.
  - Runs: Dependency check, unit/Robolectric test suite (`:app:testDebugUnitTest`), and debug assembly.
- **`.github/workflows/release.yml`**:
  - Triggers on: Tag pushes matching `v*.*.*` (e.g. `git tag v1.0.0 && git push origin v1.0.0`).
  - Decrypts the release keystore, builds the release APK (`:app:assembleRelease`) with R8 minification, computes SHA-256 checksums, generates `update-manifest.json`, and publishes a GitHub Release.

---

## 2. Secrets Configuration

Configure the following secrets in GitHub Repository Settings (`Settings > Secrets and variables > Actions`):

| Secret Name | Description | Example / Format |
|---|---|---|
| `KEYSTORE_BASE64` | Base64-encoded production keystore (`.jks`) | `base64 -w 0 my-upload-key.jks` |
| `STORE_PASSWORD` | Password protecting the keystore file | Alphanumeric secret |
| `KEY_PASSWORD` | Password protecting the key alias (`upload`) | Alphanumeric secret |

> **Note**: If `KEYSTORE_BASE64` is omitted, the release workflow generates a standalone signing key with the provided passwords for test releases.

---

## 3. Local Release Build Execution

You can build and test release artifacts locally using the included `Makefile` and `build_apk.sh`:

```bash
# 1. Warm/verify dependency cache
make install-deps

# 2. Run automated test suite
make test

# 3. Build release APK with semantic versioning and checksums
make build-release
```

Outputs are written to the `dist/` directory:
- `dist/StayFrame-v1.0.0-release.apk`
- `dist/StayFrame-v1.0.0-release.apk.sha256`

---

## 4. 4-Week Milestone Timeline & Gates

| Week | Objective | Gate Criteria |
|---|---|---|
| **Week 1** | **Pipeline & Keystore Validation** | GitHub Actions passes on test tags; release APK verified in under 6 minutes. |
| **Week 2** | **Internal Dogfood & Alpha (10 devices)** | Battery consumption < 3.5%/hr; seamless media controls across YouTube, VLC, MX Player, Chrome. |
| **Week 3** | **Closed Beta (50 testers) & Rollback Drill** | 0 false resumes in relaxed mode; automated mock rollback drill completed in < 15 minutes. |
| **Week 4** | **Production GA (v1.0.0)** | Clean test run; production tag published with SHA-256 checksum on CDN / GitHub Releases. |

---

## 5. Rollback & Incident Recovery Protocols

### Scenario A: Immediate Defect Mitigation via Update Manifest
If an issue is found in a deployed build, update the CDN `update-manifest.json` within 5 minutes:
1. Revert `latest_version` to the previous stable release.
2. Mark the flawed version in `deprecated_versions`:
   ```json
   {
     "latest_version": "0.9.8",
     "deprecated_versions": ["1.0.0"],
     "critical_update": true
   }
   ```
3. The client flags the running build and prompts the user to download the stable version.

### Scenario B: Fast-Track Hotfix (`vX.Y.Z+1`)
Because Android prevents downgrading APKs with lower `versionCode` values:
1. Create a hotfix branch from `main`: `git checkout -b hotfix/revert-regression`.
2. Revert the offending commit: `git revert <commit-sha>`.
3. Increment `versionCode` in `app/build.gradle.kts` (e.g. from 1 to 2) and set `versionName = "1.0.1"`.
4. Commit, push, and tag:
   ```bash
   git tag v1.0.1
   git push origin v1.0.1
   ```
5. GitHub Actions builds, signs, and publishes the hotfix within 8 minutes.
