# AGENTS.md — AI Coding Agent Guide for StayFrame

Welcome to **StayFrame**. Read this file alongside [`PROJECT_MEMORY.md`](PROJECT_MEMORY.md), [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), and [`docs/APP_FLOW.md`](docs/APP_FLOW.md) before modifying code.

---

## 1. Project Identity & Critical Guardrails

- **Application ID**: `com.stayframe.app` (in `app/build.gradle.kts`).
- **Namespace / Kotlin Package Root**: `com.example` (`app/src/main/java/com/example/...`). **Do NOT** rename `namespace` or move `com/example/` directories—resources (`com.example.R`), manifests, and tests depend on this namespace.
- **Keystores & Signing Configs**:
  - Never modify `debug.keystore.base64` or remove `signingConfigs` (`debugConfig`, `release`) in `app/build.gradle.kts`.
  - If `debug.keystore` is missing locally before a Gradle build, decode it from `debug.keystore.base64` (never commit binary `debug.keystore`).
- **AI Studio Metadata (`metadata.json`)**:
  - Keep `name` synced with `res/values/strings.xml` (`StayFrame`).
  - Under AI Studio platform rules, preserve `MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API` inside `metadata.json` (`majorCapabilities`) even though the app itself uses zero Gemini APIs and runs 100% locally.
- **No Cloud AI / No Broad Storage Permissions**:
  - Keep all face detection and biometric matching strictly local on-device via CameraX + bundled ML Kit + Android Keystore.
  - Never request `READ_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, or `READ_MEDIA_VIDEO`, and never replace `AlarmManager.set(...)` with `setExactAndAllowWhileIdle()` unless Play `SCHEDULE_EXACT_ALARM` justification is explicitly approved.

---

## 2. Build & Verification Commands

This repository runs inside environments where `./gradlew` is **not** committed; use `gradle` directly:

```bash
# Run all unit and Robolectric tests (fast, no emulator required)
gradle --no-daemon :app:testDebugUnitTest

# Force clean re-run of all unit tests
gradle --no-daemon :app:testDebugUnitTest --rerun-tasks --console=plain

# Build debug APK with semantic versioning + SHA-256 (decodes debug.keystore.base64)
./build_apk.sh debug

# Compile check via Gradle
gradle --no-daemon :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
```

> **Note**: There is no Android emulator or `adb` inside standard cloud build containers. Rely on JVM unit tests and **Robolectric** (`@Config(sdk = [34])`) rather than `androidTest/` Espresso tests.

---

## 3. Architectural & Engineering Invariants (Must Preserve)

1. **Thread-Safe State Machine (`PresenceStateEngine`)**:
   - All state mutations run inside `synchronized(lock)`.
   - Never transition to `PAUSED_BY_GUARD` or set `pauseOwnership = true` unless `PresenceEngineCallback.onSendPauseCommand()` returns `true`.
   - Never leave `PAUSED_BY_GUARD` on auto-resume or manual resume unless `onSendPlayCommand()` returns `true`.
2. **Camera Resilience (`MonitoringForegroundService`)**:
   - Observe `camera.cameraInfo.cameraState` and run the `> 5s` frame watchdog (`FRAME_WATCHDOG_TIMEOUT_MS`).
   - Retry camera rebinds indefinitely with exponential backoff capped at `30_000L` ms; enter `ERROR` after 3 attempts while continuing retries, and allow exit from `ERROR` via `recoverFromError()` and incoming frames.
   - Wrap `startForeground(...)` in `try/catch`, return `START_NOT_STICKY` from `onStartCommand`, and recreate `cameraExecutor` + `faceDetector` on every `startMonitoringService()` (closing/nulling both on `stopMonitoringService()` and `onDestroy()`).
3. **No Background Camera FGS Launch**:
   - `StayFrameNotificationListenerService.attemptArmedSessionRecovery()` must **never** call `MonitoringForegroundService.start(...)`. Always post the tap-to-resume notification routing `MainActivity.EXTRA_RESUME_MONITORING = true` into `MainActivity` so the user starts monitoring from the foreground.
4. **Fail-Closed Biometrics & Lighting**:
   - Y-plane mean luma `< 18f` (`MIN_ADEQUATE_LUMA`) sets `lightingAdequate = false`, routing to `PresenceState.UNKNOWN` even in `RELAXED_FALLBACK`.
   - In `STRICT` mode, rejected landmarks or missing enrolled template (`cachedEnrolledTemplate == null`) set `identityUncertain = true` and route to `PresenceState.UNKNOWN` (never `OTHER_FACE`).
   - Cache the decrypted template off the main thread (`Dispatchers.IO`) and notify the service via `StayFrameServiceBridge.notifyTemplateChanged()` on enroll/delete.
5. **Buffering & Manual Pause Immunity**:
   - `PlaybackState.STATE_BUFFERING` and `STATE_CONNECTING` set `ActiveMediaSessionInfo.isBuffering = true`. Never mark buffering as `manualPauseDetected`, and never call `resetTimers()` during `ABSENCE_GRACE`.
6. **Single Match Threshold & Keystore Durability**:
   - Always reference `FaceDescriptorExtractor.RECOMMENDED_MATCH_THRESHOLD` (`0.72f`)—never duplicate magic `0.72f` literals.
   - `FaceTemplateStore.saveTemplate()` must call `ensureKeyStoreKey()` before encrypting so `delete -> re-enroll` succeeds.
7. **Screen Timeout Safety**:
   - Call `ScreenTimeoutController.restoreTimeout()` in `stopMonitoringService()` and `onDestroy()`, and keep timed expiry backed by inexact `AlarmManager` + `ScreenTimeoutExpiryReceiver`.
8. **No Swallowed Exceptions**:
   - Do not write empty `catch (_: Exception) {}` blocks. Route recoverable failures through `StayFrameServiceBridge.recordRecoverableException(eventName, contextMessage, error)`.

---

## 4. Testing Hooks Available for Robolectric

- `FaceTemplateStore` accepts an internal `KeyStoreDelegate` constructor parameter for real AES-256-GCM testing on OpenJDK without `AndroidKeyStore`.
- `MonitoringForegroundService.foregroundStarterOverride` allows simulating `ForegroundServiceStartNotAllowedException` safely in Robolectric.
- `MonitoringForegroundService.computeMeanLuminanceFromYPlane(...)` and `hasActiveProcessingResourcesForTest()` allow direct testing of luma cutoffs and start/stop resource recreation.
- When testing `StayFrameNotificationListenerService.onListenerConnected()` or `attemptArmedSessionRecovery()` in Robolectric, initialize `WorkManager` in `@Before` if not already initialized.
