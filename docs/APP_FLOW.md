# StayFrame Application & State Flows

This guide traces every major user-facing and system-level execution flow in StayFrame.

---

## 1. Onboarding & Permissions Flow

```text
Launch MainActivity
  └─► StayFrameViewModel.init
        ├─► refreshPermissions() (CAMERA, NotificationListenerEnabled, WRITE_SETTINGS)
        ├─► screenTimeoutController.checkAndRecoverStaleOverride() (crash recovery)
        ├─► PlayerRescanWorker.schedulePeriodicRescan() (24h WorkManager job)
        └─► registryRepository.rescanAndValidateRegistry()
```

- If `CAMERA` or Notification Listener access is missing:
  - `DashboardScreen` displays a **Permissions Required** banner pointing to `SettingsScreen`.
  - Tapping **Start Monitoring** without required permissions redirects to `SettingsScreen` (or `startMonitoring()` reports an error).
- Once both `CAMERA` and Notification Listener permissions are granted, monitoring can be armed.

---

## 2. Face ID Enrollment Flow (Strict Mode Setup)

1. User opens **Face ID** (`EnrollmentScreen`).
2. Local Compose CameraX `ImageAnalysis` captures frames every ~350ms on a dedicated executor.
3. `FaceDescriptorExtractor.extractFromMlKitFace(...)` validates required landmarks (`LEFT_EYE`, `RIGHT_EYE`, `NOSE_BASE`, `MOUTH_BOTTOM`), pose angles (`<= 25°` Euler Y/Z), and inter-ocular distance (`>= 12px`), returning `Success(24D vector)` or `Rejected(reason)`.
4. Multiple high-quality samples are averaged and L2-normalized (`averageAndNormalize`).
5. `StayFrameViewModel.enrollFace(...)` calls `FaceTemplateStore.saveTemplate(descriptor, name)`:
   - Re-ensures Android Keystore AES-256-GCM key (`ensureKeyStoreKey()`).
   - Encrypts and persists ciphertext + IV to `stayframe_biometrics`.
   - Calls `StayFrameServiceBridge.notifyTemplateChanged()` so `MonitoringForegroundService` refreshes `cachedEnrolledTemplate` off the main thread (`Dispatchers.IO`).
6. Deleting enrollment (`deleteEnrollment()`) wipes preferences + deletes the Keystore key alias, refreshes the service cache to `null`, and falls back from `STRICT` to `RELAXED_FALLBACK`.

---

## 3. Active Monitoring & Presence State Machine

```text
               Start Monitoring (Foreground)
                           │
                           ▼
                   WAITING_FOR_PLAYER
                ┌──────────┴──────────┐
       (Supported, Playing       (Supported, Paused)
        or Buffering)                 │
                │                     ▼
                │              PLAYER_DETECTED ◄───(Manual User Pause)
                ▼                     │
             PRESENT ◄────────────────┘ (User resumes externally)
      ┌─────────┼─────────┐
 (No face)  (Dark <18  (Strict mismatch)
      │      or unc.)     │
      ▼         ▼         ▼
 ABSENCE_GRACE  UNKNOWN  OTHER_FACE
      │         │         │
      └────┬────┴─────────┘
           │  Grace timer >= effectiveGracePeriodSeconds
           ▼
   triggerAutoPauseAndRewind()
     ├─ onSendPauseCommand() == false ─► Error / Stay active, NO PAUSED_BY_GUARD
     └─ onSendPauseCommand() == true  ─► onSendRewindCommand() ─► PAUSED_BY_GUARD
                                                                     │
          ┌──────────────────────────────────────────────────────────┘
          │ Viewer returns:
          │  - STRICT: Enrolled match >= 0.72f sustained >= 1.0s
          │  - RELAXED: Any face sustained >= 2.0s (passersby <2s ignored)
          ▼
     triggerAutoResume()
       ├─ onSendPlayCommand() == false ─► Stay PAUSED_BY_GUARD (keep ownership)
       └─ onSendPlayCommand() == true  ─► Clear ownership ─► PRESENT
```

### Key State Rules
- **Adaptive Timing**: `AdaptiveIntervalResolver` computes `effectiveGracePeriodSeconds` from user per-player sync -> observed seek step -> built-in player default -> global app fallback (`3s`, `5s`, or `10s`).
- **Position Extrapolation**: `MediaPositionEstimator` calculates `targetPosition = max(0, estimatedCurrentPos - graceMs)` using `elapsedRealtime` and `playbackSpeed` when `STATE_PLAYING`. Unseekable/live streams pause without dispatching `seekTo`.
- **Buffering Immunity (T4)**: `PlaybackState.STATE_BUFFERING` and `STATE_CONNECTING` set `isBuffering = true`. Buffering is never treated as a manual user pause and never calls `resetTimers()` during `ABSENCE_GRACE`.
- **Session Switch**: Changing active player package invalidates prior `pauseOwnership` so StayFrame never resumes the wrong app.

---

## 4. Camera Watchdog, Hardware Fault & Recovery Flow (T1)

1. Upon binding CameraX in `MonitoringForegroundService`, `camera.cameraInfo.cameraState` is observed via `cameraStateObserver`, and `frameWatchdogRunnable` ticks every 1s.
2. Every delivered frame updates `lastFrameReceivedElapsedMs`.
3. If `> 5000ms` elapse without frames:
   - Watchdog triggers `reportCameraWatchdogTimeout()` -> `PresenceState.UNKNOWN` (starts grace countdown so playback pauses safely if camera stays dead).
   - Unbinds CameraX and schedules exponential backoff rebind (`1s, 2s, 4s, 8s, 16s, 30s cap`, unlimited retries).
4. If rebind/hardware errors reach 3 attempts (`ERROR_ESCALATION_RETRY_THRESHOLD`), `PresenceStateEngine` enters `ERROR` while background retries continue every 30s.
5. When `CameraState.Type.OPEN` fires or new valid frames arrive:
   - Retry counter resets, `recoverFromError()` / frame arrival exits `ERROR`, and the engine resolves back to `PRESENT` or `PLAYER_DETECTED` (preserving manual pauses).

---

## 5. Listener Disconnect & Tap-to-Resume FGS Recovery (T2, T7)

- **Listener Disconnect (`T2`)**:
  - `StayFrameNotificationListenerService.onListenerDisconnected()` sets `listenerServiceInstance = null` (`listenerConnected = false`), calls `requestRebind(componentName)`, and if monitoring is active surfaces an error state + **Media Listener Disconnected** card on `DashboardScreen`.
  - Any pause/rewind/play command dispatched while listener is null returns `false`, withholds `PAUSED_BY_GUARD`, and reports an `ERROR` message to the UI.
  - When `onListenerConnected()` fires again, `reportListenerReconnected()` restores active state automatically.
- **Process Kill & Tap-to-Resume (`T7`)**:
  - `MonitoringForegroundService` returns `START_NOT_STICKY`, wraps `startForeground(...)` in `try/catch`, and allocates a fresh `ExecutorService` + ML Kit `FaceDetector` on each start (releasing both on stop/destroy).
  - When `StayFrameNotificationListenerService.onListenerConnected()` sees `isMonitoringArmed == true` and monitoring inactive, it **never** starts a camera FGS from background. Instead, it posts a **"StayFrame Guard Interrupted — Tap to resume"** notification.
  - Tapping the notification launches `MainActivity` with `EXTRA_RESUME_MONITORING = true`, which clears the extra and calls `viewModel.resumeMonitoringFromNotification()` -> `startMonitoring()` safely from the foreground Activity.

---

## 6. Screen Timeout Override & Restoration Flow (T5)

1. When monitoring is active and `WRITE_SETTINGS` is granted, `ScreenTimeoutController.applyTimeout(mode)` backs up the original `Settings.System.SCREEN_OFF_TIMEOUT` into `stayframe_screen_timeout` prefs and applies `15m`, `30m`, `60m`, or `UNTIL_STOPPED`.
2. Timed modes (`15m`, `30m`, `60m`) schedule a Play-safe inexact `AlarmManager.set(AlarmManager.ELAPSED_REALTIME, ...)` targeting `ScreenTimeoutExpiryReceiver`.
3. Original timeout is restored and any pending alarm is canceled whenever:
   - The scheduled alarm fires (`ScreenTimeoutExpiryReceiver`),
   - The user switches mode to `OFF` or stops monitoring (`ViewModel.stopMonitoring()`),
   - `MonitoringForegroundService.stopMonitoringService()` or `onDestroy()` executes,
   - The app cold-starts after a crash (`checkAndRecoverStaleOverride()`).
