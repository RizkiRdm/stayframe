# PROJECT_MEMORY.md — StayFrame Engineering & Decision Log

Last Updated: 2026-11-26

## 1. Current Project Snapshot

- **App Name**: `StayFrame`
- **Package Identity**: `applicationId = "com.stayframe.app"` | `namespace = "com.example"`
- **Version**: `1.0.0` (`versionCode = 1`)
- **SDK Targets**: `minSdk = 24`, `targetSdk = 36`, `compileSdk = 36` (minor API 1)
- **Architecture**: Single-module Android Kotlin + Jetpack Compose (M3) + CameraX + bundled ML Kit Face Detection + `PresenceStateEngine` + `StayFrameServiceBridge` + Room + DataStore + Android Keystore + WorkManager.
- **Test Status**: All JVM & Robolectric unit test suites run via `gradle --no-daemon :app:testDebugUnitTest`.

---

## 2. Chronological Engineering Milestones (T1–T8)

### Build & CI Keystore Fix
- **Issue**: CI `:app:validateSigningDebug` failed because binary `debug.keystore` is gitignored.
- **Resolution**: `debug.keystore.base64` is decoded in `.github/workflows/ci.yml` and `build_apk.sh` prior to Gradle assembly, with signed debug APK + `.sha256` artifact uploads.

### T1 — Camera Resilience & Hardware Recovery
- **Changes**:
  - `MonitoringForegroundService`: Observed `camera.cameraInfo.cameraState`; added 5s frame watchdog (`FRAME_WATCHDOG_TIMEOUT_MS = 5_000L`) transitioning stalled streams to `PresenceState.UNKNOWN`; implemented unlimited rebind retries with exponential backoff capped at 30s (`1s -> 2s -> 4s -> 8s -> 16s -> 30s`); escalated to `PresenceState.ERROR` at attempt 3 while continuing background retries.
  - `PresenceStateEngine`: Added explicit `recoverFromError()` hook and automatic `ERROR` exit when frames arrive, preserving `manualPauseDetected` state (`PLAYER_DETECTED` vs `PRESENT`).
- **Proof**: Verified by `ERROR -> recover` and backoff unit tests in `StayFramePresenceEngineTest`.

### T2 — Notification Listener & Command Dispatch Safety
- **Changes**:
  - `StayFrameNotificationListenerService`: Added `requestRebind(componentName)` in `onListenerDisconnected()`, updated `sendPauseCommand` / `sendRewindCommand` / `sendPlayCommand` to return `Boolean` via `resolveController()`, and wired `reportListenerDisconnectedError` / `reportListenerReconnected`.
  - `PresenceStateEngine`: Updated `PresenceEngineCallback` transport methods to return `Boolean`; gated `PAUSED_BY_GUARD` & `pauseOwnership` on pause dispatch `true`, and gated resume transitions on play dispatch `true`.
  - `StayFrameServiceBridge`, `StayFrameViewModel`, `DashboardScreen`: Exposed `listenerConnected` and `lastErrorMessage` `StateFlow`s; surfaced a red **Media Listener Disconnected** error card on the Dashboard when monitoring runs without an active listener.

### T3 — Fail-Closed Identity & Real Lighting Pipeline
- **Changes**:
  - `FaceObservation`: Added `identityUncertain: Boolean = false`.
  - `MonitoringForegroundService`: Cached enrolled template in memory off the main thread (`Dispatchers.IO`) at service start and refreshed via `StayFrameServiceBridge.notifyTemplateChanged()` on enroll/delete; added real Y-plane luminance calculation (`computeMeanLuminanceFromYPlane`, strict cutoff `< 18f`); marked missing template or `DescriptorExtractionResult.Rejected` as `identityUncertain = true`.
  - `PresenceStateEngine`: Routed `!lightingAdequate` (even in Relaxed Mode) and Strict Mode uncertain identity to `PresenceState.UNKNOWN` via `handleUnknownOrObstructedLocked`, never misclassifying uncertain frames as `OTHER_FACE`.

### T4 — Buffering Handling
- **Changes**:
  - `ActiveMediaSessionInfo`: Added `isBuffering: Boolean = false` set for `PlaybackState.STATE_BUFFERING` and `STATE_CONNECTING`.
  - `StayFrameNotificationListenerService`: Prioritized buffering sessions above paused sessions when choosing the active player.
  - `PresenceStateEngine`: Treated buffering as non-manual pause and preserved active timers (`absenceStartTimeMs`) without calling `resetTimers()` during `ABSENCE_GRACE` / `UNKNOWN` / `OTHER_FACE`.

### T5 — Screen Timeout Restoration & AlarmManager Expiry
- **Changes**:
  - `ScreenTimeoutController`: Replaced UI-bound `Handler` timer with Play-safe inexact `AlarmManager.set(AlarmManager.ELAPSED_REALTIME, ...)` targeting manifest-registered `ScreenTimeoutExpiryReceiver` (`exported="false"`), canceling alarms on `restoreTimeout()` and `OFF`.
  - `MonitoringForegroundService`: Called `screenTimeoutController.restoreTimeout()` + reset bridge mode to `OFF` in both `stopMonitoringService()` and `onDestroy()`.

### T6 — Keystore Delete -> Re-Enroll Fix
- **Changes**:
  - `FaceTemplateStore`: Introduced `internal interface KeyStoreDelegate` (for testability on OpenJDK Robolectric) and called `ensureKeyStoreKey()` at the start of `saveTemplate()` so deleting an enrolled template/key alias and immediately re-enrolling on the same store instance succeeds.

### T7 — Foreground Service Lifecycle Safety
- **Changes**:
  - `MonitoringForegroundService`: Wrapped `startForeground(...)` in `try/catch` (logging diagnostics, reporting FGS failure cleanly without wiping error state, posting tap-to-resume fallback notification, releasing resources, calling `stopSelf()`); changed `ACTION_START` return to `START_NOT_STICKY`; recreated `cameraExecutor` and `faceDetector` fresh on each `startMonitoringService()` and released/nulled both on `stopMonitoringService()` and `onDestroy()`.
  - `StayFrameNotificationListenerService` + `MainActivity` + `StayFrameViewModel`: Removed background FGS auto-recovery in `attemptArmedSessionRecovery()`; now posts a tap-to-resume notification launching `MainActivity` with `EXTRA_RESUME_MONITORING = true`, which triggers `viewModel.resumeMonitoringFromNotification()` from the foreground Activity.

### T8 — Codebase Cleanup & Observability
- **Changes**:
  - Deleted stale `public/update-manifest.json` (release CI dynamically creates `dist/update-manifest.json`) and unused `.env.example`.
  - Renamed `applicationId` from `com.aistudio.stayframe.vwmzqx` to `com.stayframe.app` (keeping `namespace = "com.example"` unchanged).
  - Unified all `0.72f` similarity threshold constants onto `FaceDescriptorExtractor.RECOMMENDED_MATCH_THRESHOLD`.
  - Replaced silent `catch {}` blocks across services, data stores, registry scanner/entity, timeout controller, and enrollment camera screen with `StayFrameServiceBridge.recordRecoverableException(...)`.
  - Added full onboarding docs (`README.md`, `AGENTS.md`, `PROJECT_MEMORY.md`, `docs/ARCHITECTURE.md`, `docs/APP_FLOW.md`).

---

## 3. Known Constraints & Gotchas for Future Agents

1. **Protected `metadata.json` Entry**: `MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API` inside `metadata.json` is preserved solely due to AI Studio host-environment validation rules; there is **no** Gemini SDK or API usage anywhere in StayFrame.
2. **Gradle Command**: Use `gradle` (not `./gradlew`, which does not exist in this repo).
3. **Robolectric WorkManager Initialization**: Service tests invoking `StayFrameNotificationListenerService.onListenerConnected()` or `attemptArmedSessionRecovery()` must ensure `WorkManager.initialize(...)` is called in `@Before` (since AndroidX Startup `WorkManagerInitializer` behaves lazily in some Robolectric test setups).
4. **`NameNotFoundException` in `PlayerDiscoveryScanner`**: Missing optional player packages (e.g., Kodi, Plex, MX Pro when not installed) intentionally throw `PackageManager.NameNotFoundException` and return `null` without spamming diagnostics; unexpected `Exception`s log via `recordRecoverableException`.
