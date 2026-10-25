# StayFrame — Development Phase Implementation Plan (Revised)

StayFrame is a privacy-first, on-device Android utility that automatically pauses supported video playback when the enrolled user is no longer visible to the front camera, rewinds playback to the exact moment the user left, and resumes playback when the user returns.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The following product decisions were confirmed during requirements clarification and user revision:

- **Confirmed Decision 1 (Absence Grace Period)**: Default absence grace period is set to **5 seconds** (balanced sensitivity) with user-selectable options for 3s and 10s.
- **Confirmed Decision 2 (Active Player Registry)**: The initial compatibility registry prioritizes major players: **YouTube (`com.google.android.youtube`)**, **VLC (`org.videolan.vlc`)**, **MX Player (`com.mxtech.videoplayer.ad` / `.pro`)**, and **Google Chrome Media (`com.android.chrome`)**.
- **Confirmed Decision 3 (Camera Diagnostics & Test)**: The camera diagnostic preview and face alignment validation are accessible via a **dedicated Diagnostics screen** and an **inline setup card**.
- **Confirmed Decision 4 (Rewind on Absence)**: **Rewinds immediately upon pausing**. When the 5-second absence grace period triggers a pause, StayFrame automatically issues a `seekTo(currentPosition - gracePeriodMs)` so the video pauses at the exact second the viewer walked away, preventing missed content.
- **Confirmed Decision 5 (Low-Light & Fallback Detection with 2-Second Filter)**:
  - Supports two detection modes: **Strict Mode** (only enrolled biometric match) and **Relaxed / Low-Light Fallback Mode**.
  - In Relaxed Fallback Mode, any visible face will trigger auto-resume **only if the face remains stably present for > 2 seconds**. Brief passerby faces (< 2 seconds) are ignored to prevent accidental playback.
  - Manual playback is **never blocked**: Users can tap play/pause anytime via system media notifications, headset controls, or the app UI.
- **Confirmed Decision 6 (Design & Styling Direction)**: Implements the **Sleeve** design system (`DESIGN.md`): pure white canvas (`#ffffff`), paper-white cards (`#f9fafb`), 24dp card radii, 9999dp pill buttons, hairline borders (`#e5e7eb`), and Ocean Drift blue gradients (`#0e95ee` → `#27c9f5`).

---

## 1. Overview & Core Concept

### What It Does
StayFrame runs an explicit, user-initiated monitoring session via a Foreground Service. Using the front camera and an on-device face verification pipeline, it observes whether the viewer is present. When absence exceeds the grace period:
1. StayFrame records the current playback position.
2. It sends `pause` to the active supported video player.
3. It immediately rewinds the media session back by the grace period (e.g. 5 seconds) via `MediaController.TransportControls.seekTo(...)` so the video rests at the exact frame the user departed.
4. When the user returns (either verified identity in Strict Mode, or sustained face > 2s in Relaxed Mode), it sends `play`.
5. Manual actions are never blocked or fought: manual pause resets auto-resume ownership, and manual play is always honored.

### Target Audience & Persona
- **Multitasking video viewers**: Cooking, studying, cleaning, or following tutorials while watching long-form video on a phone or tablet.
- **Hands-busy learners**: Following workout drills, musical instrument lessons, or repair guides where repeatedly touching the screen causes friction.

### Key Value
- **Zero Missed Content + Auto-Rewind**: You don't have to seek backward manually—the video is waiting at the exact timestamp you left.
- **Reliable in Diverse Lighting**: The 2-second sustained presence filter in Relaxed Mode ensures auto-resume works seamlessly even in dim lighting while rejecting passersby.
- **100% Local Privacy**: Zero network calls, zero remote telemetry. Live camera frames are processed in volatile memory and immediately discarded. Biometric templates are encrypted via Android Keystore.
- **Unrestricted Manual Control**: The user always has total authority to play, pause, or seek manually.

---

## 2. User Experience & Visual Design (Sleeve Design System)

### Visual Identity & Theme
- **Canvas & Atmosphere**: Pure White (`#ffffff`) canvas with Paper White (`#f9fafb`) secondary surfaces and hairline Silver Mist (`#e5e7eb`) borders.
- **Typography & Scale**: System sans-serif with tight tracking for bold headers (`-0.025em`) and wide tracking (`+0.05em`) for uppercase category labels (`THEMING`, `PRESENCE ENGINE`, `ACTIVE PLAYER`).
- **Shapes & Elevation**: Signature 24dp rounded cards, 9999dp pill buttons, 12dp text inputs, subtle 1dp inset border strokes (`rgba(0,0,0,0.05)`), and zero heavy shadows.
- **Chromatic Accent**: Ocean Drift linear gradient (`#0e95ee` to `#27c9f5`) used exclusively for active monitoring pills, status badges, and decorative accents.

### Screen Layouts & Key User Flows

#### A. Main Dashboard (`DashboardScreen`)
- **Status Hero Card**: 24dp rounded card displaying the large Presence State badge (e.g., `PRESENT`, `ABSENCE_GRACE (3s)`, `PAUSED_BY_GUARD (Rewound -5s)`, `WAITING_FOR_PLAYER`), current active media player thumbnail/title, and primary Start/Stop Monitoring pill button.
- **Player Detection Pill**: Displays active package name, playback state (`Playing`, `Paused`), and whether it is within the compatibility registry.
- **Quick Controls & Rewind Indicator**:
  - Grace period selector pill row (3s / 5s / 10s).
  - Detection Mode selector (Strict Enrolled Only vs. Relaxed Low-Light with 2s Sustained Filter).
  - Rewind-on-Pause toggle (enabled by default, showing exact seconds rewound).
  - Screen-timeout keep-awake toggle (Off / 15m / 30m / 60m / Until Stopped).
- **Navigation Dock**: Floating pill dock for switching between **Dashboard**, **Diagnostics**, and **Settings**.

#### B. Face Enrollment Flow (`EnrollmentScreen`)
- **Step-by-Step Guided Capture**: Interactive circular viewfinder with alignment guides (Center, Slight Angle, Good Lighting).
- **Quality Feedback**: Real-time feedback pills: "Hold steady", "Adequate lighting confirmed", "Face centered".
- **Enrollment Summary**: Stores cryptographic hash/template via Android Keystore, with plain-language disclosure regarding MVP spoofing limitations.
- **Delete Enrollment Action**: One-tap action to wipe template and reset state.

#### C. Diagnostics & Live Preview (`DiagnosticsScreen`)
- **Camera Preview Toggle**: Camera preview is off by default for privacy and power saving. User can toggle live preview on demand.
- **Dual-State Visualizer**: Separately visualizes **Face Detected** (spatial bounding box) vs **Enrolled Identity Verified** (confidence percentage & green/amber badge).
- **Sustained Presence Timer Bar**: Shows progress bar (0 to 2.0s) for sustained presence verification in Relaxed Mode.
- **Real-Time State Machine Inspector**: Interactive timeline showing state transitions (`PRESENT` → `ABSENCE_GRACE` → `PAUSED_BY_GUARD` [Rewound -5s]).
- **Local Event Log**: Rolling list of recent state transitions, rewind offsets, and media commands (with zero sensitive content titles or biometric data).

#### D. Settings & Permissions Guide (`SettingsScreen`)
- **Permission Cards**: Camera permission, Notification Listener Access, and Modify System Settings with clear explanation pills and deep-link buttons to Android Settings.
- **Supported Player Compatibility Registry**: Searchable/browseable list of verified players (YouTube, VLC, MX Player, Chrome).
- **Data & Privacy Management**: Reset all settings, clear diagnostic logs, wipe enrolled biometric templates.

### Responsive & Adaptive Layout Patterns
- **Compact (Phones)**: Single-column flowing card layout with 16dp horizontal padding and floating bottom pill navigation dock.
- **Expanded / Tablet / Desktop**: Side-by-side dual-pane layout: Master pane with Status Hero and Quick Controls; Supporting pane with Diagnostics, Live Preview, and Event Log.

---

## 3. Technical Architecture & System Design

```
┌────────────────────────────────────────────────────────────────────────┐
│                          PRESENTATION LAYER                            │
│  MainActivity  •  Compose Navigation  •  Sleeve M3 Design Theme        │
│  ┌──────────────────────┬──────────────────────┬────────────────────┐  │
│  │   DashboardScreen    │   EnrollmentScreen   │ DiagnosticsScreen  │  │
│  └──────────┬───────────┴──────────┬───────────┴──────────┬─────────┘  │
│             │                      │                      │            │
│             ▼                      ▼                      ▼            │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                    StayFrameViewModel                            │  │
│  │   (StateFlow<MonitoringState>, StateFlow<PresenceState>, etc.)   │  │
│  └──────────────────────────────────┬───────────────────────────────┘  │
└─────────────────────────────────────┼──────────────────────────────────┘
                                      │
┌─────────────────────────────────────▼──────────────────────────────────┐
│                           DOMAIN LAYER                                 │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                      PresenceStateEngine                         │  │
│  │  8 States: INACTIVE, WAITING, PRESENT, ABSENCE_GRACE,            │  │
│  │            PAUSED_BY_GUARD, OTHER_FACE, UNKNOWN, ERROR           │  │
│  │  • Grace Period Timer (3s / 5s / 10s)                            │  │
│  │  • Auto-Rewind Handler: seekTo(max(0, pos - gracePeriodMs))      │  │
│  │  • Relaxed Mode: 2.0s Sustained Presence Validator               │  │
│  │  • Strict Mode: 1.0s Identity Stability Confirmation             │  │
│  │  • Manual Pause Immunity Guard (No locked controls)              │  │
│  └──────────────────┬───────────────────────────────┬───────────────┘  │
│                     │                               │                  │
│                     ▼                               ▼                  │
│  ┌──────────────────────────────────┐ ┌─────────────────────────────┐  │
│  │     FaceVerificationManager      │ │     MediaSessionController  │  │
│  │  • On-device Face Feature Vector │ │  • MediaSessionManager      │  │
│  │  • Cosine Similarity Matching    │ │  • TransportControls (Play, │  │
│  │  • Android Keystore Encryption   │ │    Pause, seekTo)           │  │
│  └──────────────────┬───────────────┘ └─────────────────────────────┘  │
└─────────────────────┼──────────────────────────────────────────────────┘
                      │
┌─────────────────────▼──────────────────────────────────────────────────┐
│                       SERVICE & INFRASTRUCTURE                         │
│  ┌───────────────────────────────┐  ┌───────────────────────────────┐  │
│  │  MonitoringForegroundService  │  │ StayFrameNotificationListener │  │
│  │  • CameraX ImageAnalysis      │  │ • Observes active sessions    │  │
│  │  • 1 FPS power-conscious loop │  │ • Tracks playback state & pos │  │
│  │  • Ongoing Persistent Notif   │  │ • Validates package registry  │  │
│  └───────────────────────────────┘  └───────────────────────────────┘  │
│  ┌───────────────────────────────┐  ┌───────────────────────────────┐  │
│  │    ScreenTimeoutController    │  │   Local Preferences & Room    │  │
│  │  • Settings.System timeout    │  │ • Encrypted Template Store    │  │
│  │  • Safe restore on exit/boot  │  │ • Diagnostic Event Log Entity │  │
│  └───────────────────────────────┘  └───────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Presence State Machine, Rewind & Playback Ownership Rules

### State Transitions & Rewind Logic
1. **Active Playback**: Media player in registry is playing. State is `PRESENT`.
2. **Absence Detected**: User walks away. State transitions to `ABSENCE_GRACE` with countdown (e.g. 5.0s → 0s). Video continues playing.
3. **Grace Expiration & Auto-Rewind**:
   - Absence grace reaches 0.
   - StayFrame retrieves current playback position from `PlaybackStateCompat.getPosition()`.
   - Sends `pause()` command.
   - Computes target position: `max(0L, currentPosition - gracePeriodMs)`.
   - Sends `seekTo(targetPosition)` immediately.
   - Logs diagnostic event: `auto_pause_and_rewind (offset: -5000ms)`.
   - State enters `PAUSED_BY_GUARD` with pause ownership active.
4. **Resuming in Strict Mode**:
   - Enrolled identity matches for ≥ 1.0 second continuously.
   - Sends `play()` command and transitions to `PRESENT`.
5. **Resuming in Relaxed / Low-Light Fallback Mode**:
   - A face is detected in front of the camera.
   - Sustained presence counter runs: must stay continuously in view for **> 2.0 seconds**.
   - If face disappears before 2.0 seconds (passerby), timer resets and NO play command is issued.
   - Once 2.0 seconds threshold is met, sends `play()` command and transitions to `PRESENT`.
6. **Unrestricted Manual Control**:
   - Manual user actions are NEVER blocked.
   - If user taps Play manually while in `PAUSED_BY_GUARD`, StayFrame detects external play and transitions cleanly to `PRESENT`.
   - If user taps Pause manually while watching, StayFrame detects external pause, clears auto-resume ownership, and will not resume playback automatically when someone enters the camera view.

---

## 5. Development Milestones & Deliverables

### Milestone 1: Core Domain, State Engine & Rewind Engine
- **Deliverables**:
  - `PresenceStateEngine` implementation with 8 states, timer flows, hysteresis, and unit tests.
  - Rewind-on-pause calculation and dispatch via `MediaController.TransportControls.seekTo()`.
  - Relaxed fallback mode with 2.0s sustained presence filter.
  - `PlayerRegistry`: Catalog of supported video players (`com.google.android.youtube`, `org.videolan.vlc`, `com.mxtech.videoplayer.ad`, `com.mxtech.videoplayer.pro`, `com.android.chrome`).
  - `FaceTemplateStore`: Biometric template encryption and Keystore integration.
  - `DiagnosticEventLog`: Rolling local storage (last 100 entries) without sensitive payload.

### Milestone 2: Background Services & Hardware Controllers
- **Deliverables**:
  - `StayFrameNotificationListenerService`: Android notification listener detecting active `MediaController`s, playback position, and transport controls.
  - `MonitoringForegroundService`: CameraX foreground service with ongoing status notification, Stop action, and 1 FPS frame sampling.
  - `ScreenTimeoutController`: `Settings.System.SCREEN_OFF_TIMEOUT` override, backup persistence, and auto-restore logic.

### Milestone 3: Sleeve Modular UI & Compose Navigation
- **Deliverables**:
  - Sleeve Theme (`Color.kt`, `Theme.kt`, `Type.kt`) with tokens from `DESIGN.md`.
  - Reusable components: `SleeveCard`, `SleevePillButton`, `SleeveBadge`, `SleeveSizeToggle`, `SleeveAnnouncementPill`.
  - `DashboardScreen`: Hero state card, active player card, rewind status, mode switches, quick test banner.
  - `EnrollmentScreen`: Guided multi-step face capture and Keystore registration.
  - `DiagnosticsScreen`: Toggleable CameraX preview, bounding box overlay, 2.0s sustained presence bar, identity confidence meter, and event log.
  - `SettingsScreen`: Permissions management, compatibility registry viewer, and data wiping.

### Milestone 4: Verification, Linting & Edge Cases
- **Deliverables**:
  - Integration of `BackHandler` on all secondary screens.
  - Full touch targets (minimum 48dp), TalkBack content descriptions, and test tags.
  - Robolectric test suite validating all 16 PRD acceptance scenarios plus rewind-on-pause and 2-second passerby rejection.
  - `compile_applet` verification with zero compilation warnings or errors.

---

## 6. Testing Strategy & QA Matrix

| Scenario | Test Target | Verification Method |
|---|---|---|
| **Scenario 1: User Leaves & Rewind** | PresenceStateEngine + TransportControls | Robolectric test: simulate absence for 5.1s -> assert `pause()` and `seekTo(pos - 5000)` invoked, state is `PAUSED_BY_GUARD`. |
| **Scenario 2: User Returns (Strict)** | PresenceStateEngine + Stability Timer | Robolectric test: simulate enrolled face for 1.1s -> assert `play()` invoked and state is `PRESENT`. |
| **Scenario 3: Passerby in Relaxed Mode (< 2s)** | Relaxed Mode Sustained Filter | Unit test: simulate face visible for 1.2s then gone -> assert sustained timer resets and ZERO play commands issued. |
| **Scenario 4: Valid Return in Relaxed Mode (> 2s)** | Relaxed Mode Sustained Filter | Unit test: simulate face visible for 2.2s continuously -> assert `play()` invoked and state is `PRESENT`. |
| **Scenario 5: Manual Pause Immunity** | Playback Ownership Guard | Robolectric test: session paused manually -> user returns -> verify `play()` is NEVER called. |
| **Scenario 6: Manual Play Freedom** | Playback Ownership Guard | Robolectric test: user manually plays while `PAUSED_BY_GUARD` -> system accepts and resumes monitoring without conflict. |
| **Scenario 7: Obstructed Camera** | PresenceStateEngine | Unit test: simulate blank frame -> enters `UNKNOWN` -> pauses and rewinds on grace expiry -> zero resume. |
| **Scenario 8: Screen Timeout Restore** | ScreenTimeoutController | Unit test: timeout set to 15m -> service stops -> verify initial system timeout restored. |
| **Scenario 9: Template Deletion** | FaceTemplateStore | Unit test: wipe data -> template file removed, Keystore entry cleared, state resets to un-enrolled. |

---

## 7. Build & Deployment Execution Strategy

1. **Phase 1: Foundation & Dependencies**
   - Update `libs.versions.toml` and `app/build.gradle.kts` to enable CameraX, Room, DataStore, and Navigation Compose.
   - Update `AndroidManifest.xml` with `<uses-permission>` for `CAMERA`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `POST_NOTIFICATIONS`, `WRITE_SETTINGS`, and register `MonitoringForegroundService` and `StayFrameNotificationListenerService`.
   - Update `metadata.json` and `strings.xml` to set the app name to **StayFrame**.

2. **Phase 2: Domain, Models & State Engine**
   - Implement `PresenceState`, `PresenceStateEngine`, `PlayerRegistry`, `DiagnosticEventLog`.
   - Implement auto-rewind logic and 2.0s sustained presence filter.
   - Implement on-device face feature extraction and Keystore storage.

3. **Phase 3: Media & Background Services**
   - Implement `StayFrameNotificationListenerService` and `MonitoringForegroundService`.
   - Implement `ScreenTimeoutController`.

4. **Phase 4: Sleeve Jetpack Compose UI**
   - Build theme tokens and modular components matching `DESIGN.md`.
   - Build `DashboardScreen`, `EnrollmentScreen`, `DiagnosticsScreen`, and `SettingsScreen`.
   - Support adaptive phone and tablet/foldable layouts.

5. **Phase 5: Automated Testing & Build Validation**
   - Implement comprehensive unit and Robolectric test suite.
   - Run `compile_applet` to verify clean compilation.
