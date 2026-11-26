# StayFrame Architecture Reference

This document describes StayFrame's runtime architecture, package responsibilities, concurrency model, persistence stores, and safety invariants for engineers and AI agents.

---

## 1. High-Level Architectural Topology

StayFrame follows a reactive **MVVM + Service Bridge + Thread-Safe State Machine** architecture. All biometric processing, media session inspection, and preference storage remain strictly local on-device.

```text
┌───────────────────────────────────────────────────────────────────────────┐
│                         Presentation Layer (Compose)                      │
│  MainActivity ─► StayFrameApp (Compact Dock / Medium Rail / Expanded Pane)│
│  Screens: DashboardScreen │ DiagnosticsScreen │ EnrollmentScreen │ Settings│
│                                  ▲                                        │
│                                  │ StateFlow                              │
│                         StayFrameViewModel                                │
└──────────────────────────────────┼────────────────────────────────────────┘
                                   │ Delegates commands & observes flows
                                   ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                   StayFrameServiceBridge (Singleton Bus)                  │
│  - Owns PresenceStateEngine instance & PresenceEngineCallback adapter     │
│  - Exposes MutableStateFlows (presenceState, activeSession,               │
│    listenerConnected, lastErrorMessage, diagnosticEvents, progress)       │
│  - Holds weak/lifecycle references to foreground & listener services      │
└──────────────┬───────────────────────────────────────┬────────────────────┘
               │ Observations / Watchdog / Errors      │ Media / Transport
               ▼                                       ▼
┌─────────────────────────────────────┐ ┌───────────────────────────────────┐
│     MonitoringForegroundService     │ │ StayFrameNotificationListenerService│
│  - Type: camera (START_NOT_STICKY)  │ │  - MediaSessionManager active list │
│  - CameraX Preview + ImageAnalysis  │ │  - AdaptiveIntervalResolver sync   │
│  - ~1 FPS throttle + Y-plane Luma   │ │  - MediaPositionEstimator (speed)  │
│  - ML Kit FaceDetector (per-start)  │ │  - TransportControls (Boolean)     │
│  - CameraState + >5s Frame Watchdog │ │  - PackageChangeReceiver + WorkMgr │
└──────────────┬──────────────────────┘ └──────────────┬────────────────────┘
               │                                       │
               ▼                                       ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                     Domain & Persistence Subsystems                       │
│  - PresenceStateEngine (synchronized 12-state lifecycle machine)          │
│  - FaceDescriptorExtractor (24D scale/roll/translation-normalized vector) │
│  - FaceTemplateStore (AES-256-GCM encrypted SharedPreferences + Keystore) │
│  - StayFramePreferencesRepository (Jetpack DataStore)                     │
│  - StayFrameDatabase / PlayerRegistryRepository (Room + KSP)              │
│  - ScreenTimeoutController + ScreenTimeoutExpiryReceiver (AlarmManager)   │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Package & Layer Map (`app/src/main/java/com/example/`)

> **Important**: `applicationId` is `com.stayframe.app`, while `namespace` and Kotlin source packages remain `com.example.*`.

### `domain/` (Pure State, Biometrics, Policies, Controllers)
- `domain/engine/PresenceStateEngine.kt`: Thread-safe (`synchronized(lock)`) core state machine governing the 12 `PresenceState` enum values, grace countdown timers, pause ownership, manual pause immunity, buffering immunity, strict vs. relaxed return verification, and `ERROR` recovery.
- `domain/biometrics/FaceDescriptorExtractor.kt`: Extracts a 24-dimensional mean-centered, L2-normalized facial geometry vector from ML Kit landmarks & contours. Defines the canonical match threshold (`RECOMMENDED_MATCH_THRESHOLD = 0.72f`).
- `domain/model/PresenceState.kt`: Defines `PresenceState`, `DetectionMode` (`STRICT`, `RELAXED_FALLBACK`), and `ScreenTimeoutMode`.
- `domain/model/AdaptiveIntervalPolicy.kt`: Contains `AdaptiveIntervalResolver` (resolves effective grace/seek interval from user override -> observed step -> player profile -> global fallback), `PlayerCapabilityInspector`, and `MediaPositionEstimator` (extrapolates position across `elapsedRealtime` and `playbackSpeed` only during `STATE_PLAYING`).
- `domain/model/PlayerRegistry.kt`: Defines `ActiveMediaSessionInfo` (including `isBuffering`, `canSeek`, `effectiveIntervalSeconds`, `syncSource`), known player adapters (`YouTube`, `VLC`, `MX Player`, `Chrome`, `NewPipe`, `mpv`, `Plex`, `Kodi`), and dynamic Room-backed allowlist sync.
- `domain/model/DiagnosticEvent.kt`: Structured diagnostic event record displayed on `DiagnosticsScreen`.
- `domain/controller/ScreenTimeoutController.kt`: Safely backs up `Settings.System.SCREEN_OFF_TIMEOUT`, applies temporary overrides, schedules Play-safe inexact `AlarmManager.set(AlarmManager.ELAPSED_REALTIME, ...)` expiry broadcasts, and restores on `OFF`, stop, destroy, or crash recovery.

### `data/` (Encrypted Biometrics, DataStore, Room Registry)
- `data/FaceTemplateStore.kt`: Encrypts/decrypts the enrolled 24D `FloatArray` using Android Keystore (`AES/GCM/NoPadding`) and `SharedPreferences`. Uses `internal interface KeyStoreDelegate` so Robolectric tests on OpenJDK can swap in real JCA AES keys. Re-runs `ensureKeyStoreKey()` on every `saveTemplate()` so `delete -> re-enroll` succeeds.
- `data/StayFramePreferencesRepository.kt`: DataStore wrapper persisting `gracePeriodSeconds`, `detectionMode`, `screenTimeoutMode`, and `isMonitoringArmed`.
- `data/registry/`: Room database (`StayFrameDatabase`), DAO (`DiscoveredPlayerDao`), entity (`DiscoveredPlayerEntity`), system scanner (`PlayerDiscoveryScanner` using Android 11+ `<queries>`), and `PlayerRegistryRepository`.

### `service/` (Android System Services, Receivers, Bridge, Workers)
- `service/StayFrameServiceBridge.kt`: Singleton coordinator between background Android services and Jetpack Compose `StateFlow`s. Translates `PresenceEngineCallback` transport actions into `StayFrameNotificationListenerService` calls, reports null-listener errors cleanly, and records recoverable exceptions via `recordRecoverableException(...)`.
- `service/MonitoringForegroundService.kt`: CameraX `camera`-typed foreground service (`START_NOT_STICKY`). Manages per-start `cameraExecutor` & `faceDetector`, Y-plane mean luminance computation (`MIN_ADEQUATE_LUMA = 18f`), >5s frame stall watchdog, CameraX `CameraState` observer, unlimited 30s-capped exponential rebind backoff, and `ScreenTimeoutController` teardown restoration.
- `service/StayFrameNotificationListenerService.kt`: Observes `MediaSessionManager.getActiveSessions(...)` and `MediaController.Callback`. Distinguishes `STATE_BUFFERING`/`STATE_CONNECTING`, requests rebind in `onListenerDisconnected()`, returns `Boolean` from transport commands, and posts a tap-to-resume notification when armed sessions recover after OS interruption.
- `service/ScreenTimeoutExpiryReceiver.kt`: Manifest-registered (`exported="false"`) receiver handling `ACTION_RESTORE_SCREEN_TIMEOUT` alarms.
- `service/PackageChangeReceiver.kt` & `service/PlayerRescanWorker.kt`: Keep the Room player registry updated on package install/uninstall/replace and periodic 24-hour WorkManager rescans.

### `ui/` & `MainActivity.kt`
- `MainActivity.kt`: Hosts adaptive `BoxWithConstraints` layout (Compact floating bottom dock, Medium side `NavigationRail`, Expanded dual-pane Dashboard + live Diagnostics) and processes `EXTRA_RESUME_MONITORING` from tap-to-resume recovery notifications.
- `ui/StayFrameViewModel.kt`: Exposes bridge & repository `StateFlow`s, handles permissions/enrollment/reset actions, and triggers `StayFrameServiceBridge.notifyTemplateChanged()` when templates change.
- `ui/screens/`: `DashboardScreen`, `DiagnosticsScreen`, `EnrollmentScreen`, `SettingsScreen`.

---

## 3. Core Invariants & Threading Model

1. **Engine Lock Safety**: All mutable state inside `PresenceStateEngine` is guarded by `synchronized(lock)`. Camera analysis runs on `cameraExecutor` (`Executors.newSingleThreadExecutor()`), template loading/DataStore/Room run on `Dispatchers.IO`, and `StateFlow` updates are thread-safe.
2. **Command Ownership & Dispatch Safety**: `PresenceStateEngine` never sets `PAUSED_BY_GUARD` or `pauseOwnership = true` unless `onSendPauseCommand(...)` returns `true`. Similarly, `autoResume` / `userRequestResume` never leave `PAUSED_BY_GUARD` unless `onSendPlayCommand(...)` returns `true`.
3. **Fail-Closed Biometrics & Lighting**:
   - Y-plane mean luminance `< 18f` routes to `UNKNOWN` even in `RELAXED_FALLBACK`.
   - In `STRICT` mode, `DescriptorExtractionResult.Rejected` or `cachedEnrolledTemplate == null` sets `identityUncertain = true`, routing to `UNKNOWN` rather than `OTHER_FACE`.
4. **FGS Lifecycle Compliance (Android 12–15)**:
   - `MonitoringForegroundService` always returns `START_NOT_STICKY`.
   - `startForeground(...)` is wrapped in `try/catch`; failures log diagnostics, report `ERROR`, post a tap-to-resume fallback notification, release processing resources, and `stopSelf()`.
   - Background `attemptArmedSessionRecovery()` never calls `MonitoringForegroundService.start(...)`; recovery always routes through user tap -> `MainActivity` -> `viewModel.resumeMonitoringFromNotification()`.
5. **Single Threshold Constant & Observability**:
   - `0.72f` lives solely in `FaceDescriptorExtractor.RECOMMENDED_MATCH_THRESHOLD` and is referenced by `FaceTemplateStore.MATCH_THRESHOLD` and `PresenceStateEngine.matchThreshold`.
   - Non-fatal exception catches route to `StayFrameServiceBridge.recordRecoverableException(...)` so diagnostics always capture failures.
