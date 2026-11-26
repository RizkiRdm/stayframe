<div align="center">

# 🚀 StayFrame

Privacy-first Android playback guard that watches your presence via on-device front-camera face detection and automatically pauses, rewinds, and resumes video playback when you step away.

![License](https://img.shields.io/badge/license-MIT-blue)
![Version](https://img.shields.io/badge/version-1.0.0-green)
![Platform](https://img.shields.io/badge/Android-SDK%2024%E2%80%9336-blue)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-purple)
![Status](https://img.shields.io/badge/status-active-success)

</div>

---

# 📖 Overview

- **What is this project?** StayFrame is an Android foreground companion utility built with Kotlin, Jetpack Compose, CameraX, and bundled Google ML Kit Face Detection.
- **Why was it built?** Stepping away from a phone or tablet while watching lectures, tutorials, or long-form videos leaves playback running and causes you to miss key context.
- **What problem does it solve?** It monitors viewer presence 100% locally on-device—without cloud uploads or stored camera frames—automatically pausing and rewinding the exact adaptive grace interval when you leave, and resuming only when you return.
- **Who is it for?** Android users watching video across apps like YouTube, VLC, MX Player, Chrome, Kodi, and Plex who want hands-free pause, rewind, and identity-aware resume protection.

> An on-device Android Presence Guard that pairs CameraX + ML Kit facial geometry with system MediaSession controls so you never lose your place when stepping away from a video.

---

# ✨ Features

- ✅ **Adaptive Pause & Precision Rewind**: Automatically pauses playback after a configurable grace period (synchronized per player or global fallback) and rewinds using speed-aware position extrapolation.
- ✅ **Relaxed & Strict Face ID Modes**: Supports 2-second sustained presence detection in Relaxed Mode or encrypted 24-dimensional facial geometry matching in Strict Mode (backed by Android Keystore AES-256-GCM).
- ✅ **Fail-Closed Privacy & Lighting Guard**: Low-light frames (`< 18` mean Y-luma), covered lenses, rejected landmarks, or missing templates transition safely to `UNKNOWN` rather than misidentifying viewers.
- ✅ **Manual Pause & Buffering Immunity**: Respects intentional user pauses (never auto-resumes over manual pauses) while distinguishing `STATE_BUFFERING` / `STATE_CONNECTING` without resetting active grace timers.
- ✅ **Camera Resilience & Watchdog**: Observes CameraX `CameraState`, enforces a >5s frame watchdog with unlimited 30s-capped exponential rebind backoff, and recovers cleanly from `ERROR` states.
- ✅ **Foreground Service Safety & Tap-to-Resume**: Uses `START_NOT_STICKY`, guarded `startForeground` error handling, per-session detector/executor lifecycle, and Android 14+ safe tap-to-resume notifications.
- ✅ **Dynamic Player Registry & Screen Timeout Override**: Discovers installed media/video players via Room + WorkManager and restores system screen-off timeouts via `AlarmManager` and service teardown hooks.
- 🚧 **Adaptive Gaze / Eye-Attention Tuning**: Fine-grained glance vs. away weighting for desk/stand setups.

---

# 📸 Screenshots

| Guard Dashboard | Face ID Enrollment | Live Diagnostics |
|---|---|---|
| Real-time `PresenceState` hero card, player sync badge, grace & timeout controls | Front-camera alignment guide & encrypted 24D local vector enrollment | Live bounding box overlay, luma/match confidence, and structured `DiagnosticEvent` feed |

*(Run `./build_apk.sh debug` and install on a device or emulator to inspect all screens in Compact, Medium, and Expanded dual-pane layouts.)*

---

# 🎥 Demo & Documentation

- **Architecture Deep Dive**: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- **End-to-End App & State Flows**: [`docs/APP_FLOW.md`](docs/APP_FLOW.md)
- **Manual Player QA Matrix**: [`docs/TESTING.md`](docs/TESTING.md)
- **AI Agent Handoff Guide**: [`AGENTS.md`](AGENTS.md)
- **Project Engineering Memory**: [`PROJECT_MEMORY.md`](PROJECT_MEMORY.md)
- **Release & Rollback Pipeline**: [`DEPLOYMENT.md`](DEPLOYMENT.md)

---

# 🏗 Architecture

```text
Jetpack Compose UI (Dashboard / Diagnostics / Enrollment / Settings)
   │  StateFlow & Actions
   ▼
StayFrameViewModel ◄──────────────────────┐
   │                                      │
   ▼                                      │ StateFlows & DiagnosticEvents
StayFrameServiceBridge (Singleton Bus) ───┤
   │                                      │
   ├──► PresenceStateEngine (Synchronized 12-State Machine)
   │       ▲                        │ Boolean Dispatch (pause / seekTo / play)
   │       │ FaceObservation        ▼
   ├──► MonitoringForegroundService   StayFrameNotificationListenerService
   │    (CameraX ~1 FPS + Watchdog     (MediaSessionManager + Adaptive Sync
   │     + Y-Luma + ML Kit 24D)         + Tap-to-Resume Recovery Notification)
   │
   └──► Local Persistence Layer
        ├── Room (DiscoveredPlayerEntity registry + WorkManager rescan)
        ├── DataStore (Grace period, DetectionMode, Timeout, Armed flag)
        ├── FaceTemplateStore (Android Keystore AES-256-GCM vector storage)
        └── ScreenTimeoutController (Settings.System + AlarmManager expiry)
```

---

# ⚙ Tech Stack

## UI & Presentation

- Kotlin
- Jetpack Compose & Material Design 3 (Adaptive `BoxWithConstraints` for Compact, Medium rail, Expanded dual-pane)
- Lifecycle `AndroidViewModel` & `StateFlow`

## Camera & On-Device Vision

- AndroidX CameraX (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`)
- Google ML Kit Face Detection (`com.google.mlkit:face-detection`, bundled landmarks & contours)
- Custom 24D scale/translation/roll-normalized `FaceDescriptorExtractor`

## System Integration & Persistence

- Android `NotificationListenerService` & `MediaSessionManager` / `MediaController`
- Room Database + KSP + WorkManager (`PlayerRescanWorker`)
- Jetpack DataStore Preferences & Android Keystore (`AES/GCM/NoPadding`)
- `AlarmManager` + `BroadcastReceiver` (`ScreenTimeoutExpiryReceiver`)

## Build, Testing & CI/CD

- Gradle Kotlin DSL (`compileSdk 36`, `minSdk 24`, `targetSdk 36`, JDK 17)
- JUnit 4 + Robolectric (`@Config(sdk = [34])`)
- GitHub Actions (`.github/workflows/ci.yml`, `.github/workflows/release.yml`), `Makefile`, `build_apk.sh`

---

# 📂 Project Structure

```text
stayframe/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/example/
│       │   │   ├── MainActivity.kt
│       │   │   ├── data/                # Preferences, FaceTemplateStore, Room player registry
│       │   │   ├── domain/              # PresenceStateEngine, biometrics, controllers, models
│       │   │   ├── service/             # Foreground camera service, NotificationListener, Bridge, Workers
│       │   │   └── ui/                  # ViewModel, Compose screens, Sleeve theme/components
│       │   └── res/
│       └── test/java/com/example/       # JVM & Robolectric unit test suites
├── docs/
│   ├── ARCHITECTURE.md
│   ├── APP_FLOW.md
│   └── TESTING.md
├── .github/workflows/                   # CI validation and tagged APK release workflows
├── AGENTS.md
├── PROJECT_MEMORY.md
├── DEPLOYMENT.md
├── Makefile
├── build_apk.sh
└── README.md
```

---

# 🚀 Getting Started

## Requirements

- JDK 17+
- Android SDK 36 (with `ANDROID_SDK_ROOT` configured)
- Gradle 9.x (`gradle` CLI available in `PATH`)
- Physical Android device (Android 8.0+ / API 26+ recommended) for front camera & Notification Listener playback testing

---

## Installation

```bash
git clone https://github.com/<your-org>/stayframe.git
cd stayframe
```

Resolve and cache Gradle dependencies:

```bash
make install-deps
```

---

## Keystore & Signing Setup

Debug builds use the repository's base64-encoded debug keystore (`debug.keystore.base64`), decoded automatically by `./build_apk.sh debug` and CI. No cloud API keys or `.env` files are required—all vision and storage run 100% locally on-device.

For release builds, export keystore environment variables before building:

```bash
export KEYSTORE_PATH=/path/to/my-upload-key.jks
export STORE_PASSWORD=your_store_password
export KEY_PASSWORD=your_key_password
```

---

## Build & Run

Build signed debug APK (`dist/StayFrame-v1.0.0-debug.apk`):

```bash
make build
```

Build signed release APK (`dist/StayFrame-v1.0.0-release.apk`):

```bash
make build-release
```

Install onto a connected Android device via ADB:

```bash
adb install -r dist/StayFrame-v1.0.0-debug.apk
```

---

# 📚 Component & Bridge Overview

Core internal interfaces coordinating UI, camera vision, and media control:

| Component | Role | Key Methods / Flows |
|---|---|---|
| `PresenceStateEngine` | Thread-safe 12-state guard machine | `processObservation`, `updateActiveMediaSession`, `handleCameraWatchdogTimeout`, `recoverFromError` |
| `StayFrameServiceBridge` | Reactive singleton bus | `presenceState`, `listenerConnected`, `lastErrorMessage`, `diagnosticEvents`, `recordRecoverableException` |
| `MonitoringForegroundService` | CameraX & ML Kit pipeline | `startForeground` guard, 5s frame watchdog, 30s-capped rebind backoff, `computeMeanLuminanceFromYPlane` |
| `StayFrameNotificationListenerService` | MediaSession observer & transport | `refreshActiveSessions`, `sendPauseCommand`, `sendRewindCommand`, `sendPlayCommand`, `attemptArmedSessionRecovery` |
| `FaceTemplateStore` | Encrypted local 24D vector store | `saveTemplate`, `getTemplate`, `deleteTemplate`, `computeCosineSimilarity` |
| `ScreenTimeoutController` | Screen-off override + Alarm safety | `applyTimeout`, `restoreTimeout`, `checkAndRecoverStaleOverride` |

---

# 🧪 Testing

Run the full JVM + Robolectric unit test suite:

```bash
gradle --no-daemon :app:testDebugUnitTest
```

Or via Makefile:

```bash
make test
```

View generated HTML/XML test reports at `app/build/reports/tests/testDebugUnitTest/index.html`. For device QA across YouTube, VLC, MX Player, and Chrome, see [`docs/TESTING.md`](docs/TESTING.md).

---

# 📈 Roadmap

- [x] Core Presence State Machine (`PresenceStateEngine`) & Manual Pause Immunity
- [x] Strict Mode 24D Facial Geometry & Android Keystore Encryption (`FaceTemplateStore`)
- [x] Dynamic Player Discovery Registry (Room + WorkManager + `PackageChangeReceiver`)
- [x] Camera Resilience (`CameraState` observer, >5s frame watchdog, 30s-capped unlimited rebind)
- [x] Fail-Closed Low-Light & Uncertain Identity Routing + Safe FGS Tap-to-Resume Recovery
- [ ] Per-player gaze sensitivity & adaptive battery-saver FPS throttling
- [ ] Play Store production metadata & screenshot capture automation

---

# 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feat/your-feature`)
3. Run `gradle --no-daemon :app:testDebugUnitTest` to verify all unit and Robolectric tests pass
4. Commit your changes with clear messages
5. Push your branch and open a Pull Request

---

# 🔒 Security

Please do not create public issues for security or biometric/privacy vulnerabilities.

Instead contact:

rizkiromdhoni28@gmail.com

---

# 🙏 Acknowledgements

- AndroidX CameraX & Jetpack Compose Teams
- Google ML Kit On-Device Face Detection
- Robolectric & JUnit Open Source Communities
