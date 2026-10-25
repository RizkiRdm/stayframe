# Product Requirements Document (PRD)

## Document Metadata

| Field | Value |
|---|---|
| Product name | StayFrame (working title) |
| Document | Product Requirements Document |
| Version | 0.1 |
| Status | Draft for technical validation |
| Last updated | 2026-10-25 |
| Product owner | TBD |
| Platform | Android mobile application |
| Primary users | People who watch videos on Android and frequently move away from their phone |
| Business model | Free utility for MVP; monetization is out of scope |

---

## 1. Product Summary

StayFrame is an Android utility that pauses supported video playback when the enrolled user is no longer visible to the front camera and resumes playback when that same user returns. It is designed for situations such as watching tutorials while cooking, studying, doing household tasks, or briefly stepping away from the phone.

The app runs on-device face verification during an explicitly started monitoring session. It detects active Android media sessions, identifies supported video-capable players, and sends pause/play commands through Android media controls. An optional screen-timeout override can keep the display awake for a selected duration or until the user stops the session.

StayFrame is a convenience tool, not a security or authentication product. It does not guarantee compatibility with every Android video application, and it does not determine whether the user is paying attention to the video.

## 2. Problem Statement

People watching videos on a phone often leave their seat or turn away for a short task. Playback continues while they are absent, causing missed content, unnecessary data use, and repeated manual seeking or pausing. Manually pausing and resuming creates friction, especially when the user is occupied with another task.

Current video players may offer their own playback and screen behavior, but they do not consistently offer cross-app, identity-aware pause/resume behavior. A separate utility could fill this gap, provided Android permits it to observe the active player and control playback without fragile UI automation.

### Problem hypothesis

For users who frequently watch longer videos while doing other tasks, automatic pause-on-absence and resume-on-return will reduce missed content and manual interaction enough to justify granting camera and media-notification permissions.

### Validation questions

1. Do target users encounter this problem at least several times per week?
2. Is camera-based presence detection acceptable when camera frames remain on-device?
3. Do the video apps users care about expose reliable Android media sessions and playback controls?
4. Is the benefit worth the battery drain and permission setup?

The product must validate these questions before expanding beyond an MVP.

## 3. Goals and Non-Goals

### 3.1 Goals

- Automatically detect active, supported video playback without requiring the user to select a player for every session.
- Pause playback after the enrolled user has been absent for a configurable grace period.
- Resume only when the enrolled user's face is verified, not merely when any face appears.
- Never auto-resume content that the user manually paused.
- Provide an optional camera preview and clear status indicators so users can validate camera and identity detection.
- Allow the user to keep the display awake for a limited duration or until monitoring is stopped.
- Keep face processing and biometric templates on-device.
- Make monitoring status, permissions, supported-player limitations, and stop controls obvious.

### 3.2 Non-goals

- Guaranteeing compatibility with every video, browser, social-media, or streaming application.
- Controlling a player that does not expose a usable Android media session or playback actions.
- Using Accessibility Services to click buttons or manipulate other apps' interfaces.
- Detecting attention, comprehension, eye contact, or whether the user is actually watching the content.
- Cloud-based face recognition, remote video processing, account creation, or biometric synchronization.
- Using face recognition for security, identity proofing, access control, or payments.
- Recording, saving, uploading, or analyzing video footage for later use.
- iOS, desktop, multi-user profiles, parental controls, or enterprise management.
- Automatically starting camera monitoring at boot or covertly monitoring users.
- Full presentation-attack/liveness detection in the MVP.

## 4. Target Users

### Primary persona: Multitasking video watcher

A person who watches tutorials, lectures, long-form video, or other content while cooking, cleaning, studying, or doing small tasks away from the phone. They want playback to pause when they leave and continue when they return, without repeatedly touching the screen.

### Secondary persona: Hands-busy learner

A person following a lesson on a phone while taking notes or using tools. They benefit from pause-on-absence but may still need to pause deliberately. The system must respect manual playback actions.

### Out-of-scope personas

- Users requiring biometric security or strong spoof resistance.
- Users expecting reliable control over every app installed on Android.
- Users who cannot or do not want to grant camera access or notification-listener access.

## 5. User Stories

1. As a viewer, I want StayFrame to detect a supported active video player automatically so I do not have to configure the player each time.
2. As a viewer, I want playback to pause after I leave the camera view so I do not miss content.
3. As a viewer, I want playback to resume only when my enrolled face returns so another person does not trigger playback.
4. As a viewer, I want StayFrame not to override a pause I made manually.
5. As a viewer, I want a camera preview and status diagnostics so I can check whether the app is detecting me correctly.
6. As a viewer, I want to hide the preview while detection continues, for privacy and less visual distraction.
7. As a viewer, I want to keep the screen awake for a chosen period or until I stop the session.
8. As a viewer, I want a persistent notification with the current status and a way to stop monitoring without reopening the app.
9. As a viewer, I want to delete my face enrollment and all locally stored data.
10. As a viewer, I want clear feedback when a player or permission is unsupported, rather than silent failure.

## 6. Product Principles

- **Explicit control:** Camera monitoring starts only after a visible user action and required permissions are granted.
- **Local-first:** Face frames and face templates never leave the device.
- **Conservative playback:** Uncertain identity results never trigger automatic playback.
- **No playback fighting:** The app only resumes playback if it previously paused that session itself.
- **Honest compatibility:** Support is based on tested integrations, not claims of universal Android control.
- **Small MVP:** No backend, account system, ads, or remote analytics.
- **Graceful degradation:** If camera, identity, media controls, or system settings become unavailable, explain the condition and keep the system in a safe state.

## 7. Core Capabilities

### 7.1 Monitoring Session Lifecycle

The user starts a monitoring session from a visible activity after setup is complete. The app checks permissions, detects active supported media sessions, and starts a foreground service for camera-based monitoring. Android requires camera access and foreground-service handling to follow OS restrictions; the service must be started while the app is visibly active, with the required service type and permissions for the OS version.

The app must show a persistent notification during monitoring. The notification must provide the current state and a Stop action. Monitoring ends when the user stops it, the session is no longer valid, or a fatal permission/system condition prevents it from operating.

**Requirements**
- MON-01: The app MUST require an explicit Start Monitoring action before continuous camera monitoring begins.
- MON-02: After permissions have been granted, the app MUST automatically inspect active media sessions when opened.
- MON-03: The user MUST NOT be required to select a player for each monitoring session.
- MON-04: The app MUST show whether it is waiting for a supported player, monitoring presence, or paused by the presence guard.
- MON-05: A persistent notification MUST be visible while the monitoring foreground service is active, subject to Android notification behavior.
- MON-06: The notification MUST include an action that stops monitoring.
- MON-07: If camera access is lost or the monitoring service fails, the app MUST stop issuing new playback commands and report the error.
- MON-08: Monitoring MUST stop when the user disables the feature or revokes the required permissions.

### 7.2 Active Video Player Detection and Control

StayFrame will use Android's active media-session mechanisms to identify active sessions and send supported play/pause transport commands. Access to sessions belonging to other apps requires the user to enable notification-listener access unless the app has privileged system permissions, which this product will not request.

Android does not provide a universal, authoritative classification that labels every installed application as a video player. Active media sessions can also represent audio playback. Therefore, the MVP MUST use a built-in, locally stored compatibility registry of video-capable apps that have been tested for media-session behavior. Active sessions from supported apps can be detected automatically; unknown or unsupported sessions must not be controlled by default.

The registry is an engineering compatibility list, not a scan of every installed package. The product MUST NOT request broad installed-app inventory access merely to discover video players. The initial supported-app list is a release-gate deliverable and MUST be validated on real devices before public compatibility claims are made.

**Requirements**
- MED-01: The app MUST detect active media sessions through Android-supported APIs.
- MED-02: The app MUST identify sessions by package and media-session state, then apply the local compatibility registry.
- MED-03: The app MUST issue Pause only when the target session is playing and the enrolled user is confirmed absent past the configured grace period.
- MED-04: The app MUST issue Play only if StayFrame previously paused that same session and the enrolled user is verified present.
- MED-05: The app MUST NOT auto-resume a session if the user manually paused it, the session changed, or StayFrame cannot prove that it initiated the pause.
- MED-06: The app MUST stop controlling a session when its media controller becomes invalid or no longer represents the relevant player.
- MED-07: Unknown and unsupported media sessions MUST be reported as unsupported and MUST NOT be controlled automatically.
- MED-08: The app MUST NOT use Accessibility Services or UI scraping as a fallback control mechanism.
- MED-09: The UI MUST distinguish “no active player,” “supported player active,” and “active player unsupported.”

### 7.3 Face Enrollment and Identity Verification

A face detector can locate faces but cannot identify an individual. StayFrame therefore needs two separate on-device steps: face detection and face verification against a locally enrolled user template. The precise embedding model and runtime are an implementation decision gated by an Android proof of concept; the chosen model must run locally and meet the performance and privacy requirements below.

Enrollment guides the user to capture a small set of clear views under adequate lighting. Only the derived face template/embedding is retained. Raw enrollment images and live camera frames MUST NOT be saved. Stored templates MUST be encrypted at rest using a key managed through Android Keystore-backed protection where technically applicable.

This is a convenience feature, not a security-grade biometric system. The MVP does not guarantee resistance to a photograph or replayed video of the enrolled user. The UI MUST disclose this limitation during enrollment.

**Requirements**
- FACE-01: Enrollment MUST be opt-in and occur in a visible in-app flow.
- FACE-02: Enrollment MUST fail with actionable guidance when face quality, lighting, framing, or capture consistency is insufficient.
- FACE-03: Identity verification MUST run on-device; biometric frames and templates MUST NOT be transmitted over a network.
- FACE-04: Live camera frames MUST be processed in memory and discarded after processing.
- FACE-05: The app MUST verify identity against the enrolled template rather than treating any detected face as the user.
- FACE-06: Any non-matching face MUST NOT trigger automatic playback.
- FACE-07: When identity confidence is ambiguous, the state MUST be UNKNOWN and MUST NOT trigger playback.
- FACE-08: The user MUST be able to delete enrollment data from the app.
- FACE-09: The app MUST explain that the MVP is vulnerable to some photo/video spoofing and is not an authentication/security product.
- FACE-10: The implementation MUST provide a configurable internal confidence threshold and validate it against representative faces, lighting, angles, and device cameras before release.

### 7.4 Presence State Engine

The presence engine converts face-verification observations into stable states. It must avoid rapid pause/play oscillation caused by temporary occlusion, low light, head movement, or dropped frames.

#### States

- `INACTIVE`: Monitoring has not started or has stopped.
- `WAITING_FOR_PLAYER`: Monitoring is active but no supported player is currently available.
- `PRESENT`: The enrolled user is verified in view.
- `ABSENCE_GRACE`: The enrolled user has not been verified, and the absence grace period has not elapsed.
- `PAUSED_BY_GUARD`: StayFrame paused the target media session because absence was confirmed.
- `OTHER_FACE`: One or more faces are visible, but none matches the enrolled user.
- `UNKNOWN`: Camera input or identity confidence is insufficient to decide presence.
- `ERROR`: Monitoring cannot safely continue due to a permission, camera, or media-control failure.

#### Default behavior

- Sample frames at a battery-conscious rate, initially targeting approximately 1 frame per second; implementation testing may adjust this rate.
- Default absence grace period: 5 seconds.
- Default stable-present confirmation before resuming: 1 second.
- `OTHER_FACE` and `UNKNOWN` never trigger Play.
- A short detection interruption enters `ABSENCE_GRACE`; playback is paused only after the grace period expires.
- If a user face is verified while another face is also visible, the enrolled user counts as present.
- If the system paused playback, it records ownership of that pause and tracks the media session identity/state needed to avoid resuming the wrong session.
- If the user manually pauses playback while monitoring is active, StayFrame relinquishes auto-resume ownership for that playback instance.

**Requirements**
- PRES-01: The default absence grace period MUST be 5 seconds.
- PRES-02: The absence grace period MUST be configurable to 3, 5, or 10 seconds in the MVP.
- PRES-03: The enrolled identity MUST be stably verified for at least 1 second before automatic resume is considered.
- PRES-04: `OTHER_FACE` and `UNKNOWN` MUST NOT result in a Play command.
- PRES-05: The app MUST use hysteresis/grace periods to avoid rapid state oscillation.
- PRES-06: The app MUST respect manual pause intent and MUST NOT repeatedly resume playback against the user's action.
- PRES-07: On restart or session change, the app MUST NOT assume that a previous pause still belongs to the current media session.
- PRES-08: If camera errors prevent verification, the app MUST notify the user and MUST NOT auto-resume.

### 7.5 Camera Preview and Diagnostics

The user can open a diagnostic view that displays the front-camera preview, face-detection status, identity-match status, supported-player state, permission state, and current presence-engine state. The preview is a diagnostic surface, not a recording feature.

The preview toggle controls whether camera imagery is displayed in the app UI. It is separate from the monitoring toggle: hiding the preview MUST NOT imply that camera monitoring has stopped. The app MUST show a persistent, understandable monitoring indicator while camera processing is active.

**Requirements**
- PREV-01: The camera preview MUST be off by default in the main monitoring UI.
- PREV-02: The user MUST be able to show or hide the preview while in the diagnostic screen.
- PREV-03: Hiding the preview MUST NOT stop presence processing while a monitoring session remains active.
- PREV-04: The app MUST provide a test flow that distinguishes face detected from enrolled identity verified.
- PREV-05: The UI MUST state when the camera is not active; it MUST NOT show a stale preview as if it were live.
- PREV-06: No frame-recording or frame-export feature may be included in the MVP.

### 7.6 Keep-Screen-Awake Control

The app offers an optional screen-timeout override for users who want the display to remain awake while the session runs. Because StayFrame does not own the video player's window, it cannot reliably apply a window-level keep-screen-on flag to another app. The proposed MVP approach is to request the Android “modify system settings” special access and temporarily update the system screen-off timeout, preserving the original value and restoring it when the selected period ends or monitoring stops.

Options:
- Off (default)
- 15 minutes
- 30 minutes
- 60 minutes
- Until monitoring is manually stopped

The feature changes a system-wide timeout, not just StayFrame's screen. The UI MUST explain the scope before requesting access. The user can still turn the display off manually, the battery can run out, and device/OEM policy can impose limits. The app must not claim that the display is guaranteed to remain on under all conditions.

**Requirements**
- DISP-01: The screen-timeout feature MUST be off by default.
- DISP-02: The user MUST explicitly enable the feature and grant Android modify-system-settings access before the timeout is changed.
- DISP-03: The app MUST persist the original timeout value before changing it.
- DISP-04: For timed modes, the app MUST restore the original timeout when the duration expires or monitoring stops, provided the process and system allow the restoration task to run.
- DISP-05: For “Until stopped,” the app MUST restore the original timeout when the user stops the session.
- DISP-06: The app MUST provide a visible Stop/Restore control in the app and monitoring notification.
- DISP-07: On the next app launch, the app MUST check for a stale override and offer or perform safe restoration when the saved state indicates an unfinished session.
- DISP-08: The UI MUST warn that force-stop, OS termination, reboot, OEM restrictions, or manual changes may interfere with restoration; the screen timeout may remain changed until recovery.
- DISP-09: If permission is denied or unsupported, monitoring MUST continue without this feature and the app MUST explain why.

### 7.7 Permissions and Privacy Controls

The app uses camera access, notification-listener access for other apps' media sessions, foreground-service permissions, and optional modify-system-settings access. Each permission must be requested only when needed and introduced with a plain-language explanation.

**Requirements**
- PRIV-01: No camera monitoring may occur before explicit user activation and camera permission grant.
- PRIV-02: The app MUST disclose that notification-listener access allows it to observe relevant notifications/media-session metadata needed for control.
- PRIV-03: The app MUST NOT upload camera frames, face templates, viewing history, or media metadata to a server.
- PRIV-04: The app MUST NOT include ad SDKs, third-party analytics, or remote logging in the MVP.
- PRIV-05: The app MUST offer a one-action deletion flow for the enrolled template and local app data.
- PRIV-06: Local event diagnostics MUST avoid saving raw images, face embeddings, notification text, or video titles.
- PRIV-07: The app MUST explain why a permission is needed, and provide a fallback when it is denied.

## 8. Main User Flows

### Flow A: First-time setup

1. User opens StayFrame.
2. App explains the pause/resume behavior, camera use, local processing, and compatibility limitations.
3. User grants camera permission.
4. User enables notification-listener access in Android Settings.
5. User optionally grants modify-system-settings access if they want screen-timeout control.
6. User enrolls their face in a visible flow.
7. App runs a verification preview and displays readiness checks.
8. App reaches `READY` when required permissions and enrollment are complete.

### Flow B: Start monitoring and auto-pause

1. User opens StayFrame; the app inspects active media sessions.
2. User taps Start Monitoring.
3. The app starts the camera foreground service while its activity is visible and displays the ongoing notification.
4. The app identifies an active supported media session and begins checking presence.
5. User leaves the camera view.
6. After the absence grace period, the app sends Pause to the supported session and enters `PAUSED_BY_GUARD`.
7. The notification indicates that playback is paused because the enrolled user is absent.

### Flow C: Resume on return

1. StayFrame is still monitoring and owns the pause of the same media session.
2. The enrolled user returns to the camera view.
3. Face verification passes continuously for the stable-present confirmation period.
4. StayFrame confirms that the same playback instance remains paused by its own action.
5. StayFrame sends Play and returns to `PRESENT`.

### Flow D: Another person appears

1. The enrolled user leaves.
2. Another person appears in view.
3. Face detection succeeds, but identity verification does not match the enrolled template.
4. StayFrame remains in `OTHER_FACE` and MUST NOT resume playback.

### Flow E: Manual pause

1. StayFrame is monitoring playback.
2. The user manually pauses the player.
3. StayFrame observes the state change and relinquishes its auto-resume ownership for that playback instance.
4. The enrolled user returning to the frame MUST NOT cause playback to resume.
5. A new playback instance or explicit user reset may establish new control ownership.

### Flow F: Preview validation

1. User opens Diagnostics.
2. User enables the preview.
3. UI separately shows face-detected and enrolled-user-verified status.
4. User hides the preview; monitoring can continue without displaying the live image.
5. User can stop monitoring and see confirmation that the camera is no longer active.

### Flow G: Keep screen awake

1. User selects a duration or “Until stopped.”
2. App explains that the system screen timeout will be changed and asks for the required special access.
3. After access is granted, the original timeout is saved and the temporary timeout is applied.
4. When the timer expires or user stops monitoring, the app restores the original value.
5. If an earlier override is detected on the next launch, the app provides recovery and restore status.

## 9. Functional Requirements Summary

| ID | Requirement | Priority |
|---|---|---|
| MON-01–08 | Explicit session lifecycle, automatic player scan, foreground notification, safe error handling | Must |
| MED-01–09 | Active media-session detection, supported-player registry, conservative play/pause ownership | Must |
| FACE-01–10 | On-device enrollment and identity verification, template deletion, honest spoofing limitation | Must |
| PRES-01–08 | Stable presence state machine, configurable absence delay, no false auto-resume | Must |
| PREV-01–06 | Optional preview, live diagnostics, no recording | Must |
| DISP-01–09 | Optional timeout override, duration settings, restore/recovery controls | Must for the timeout feature; feature itself may ship disabled if validation fails |
| PRIV-01–07 | Permission disclosures, no network transfer, local deletion and privacy controls | Must |

## 10. Non-Functional Requirements

### Reliability

- The app MUST never issue Play solely because a face was detected.
- The app MUST never resume a player it did not itself pause, unless the user explicitly initiates playback.
- A media-session change MUST invalidate previous automatic-resume ownership.
- Failure of face verification MUST fail closed for auto-resume.

### Performance and battery

- The first implementation SHOULD target approximately one analyzed frame per second during steady-state monitoring, with a bounded increase when needed to confirm a transition.
- The app MUST close camera resources promptly when monitoring ends.
- The app MUST measure incremental battery consumption on at least one low-end and two mid-range Android devices.
- MVP release target: additional drain no greater than 8 percentage points per hour over a defined screen-on baseline during a 60-minute monitoring test. If the target is not met, frame rate and model execution must be optimized before release or the target must be explicitly revised with measurement evidence.
- Face verification must not block the UI thread.

### Privacy and security

- All face verification MUST be local.
- Raw live frames MUST be short-lived in memory.
- Face templates MUST be encrypted at rest where technically applicable and removed when enrollment is deleted.
- No account, server, cloud inference, advertising SDK, or analytics service is required for the MVP.
- Camera activation and background operation MUST be visible and controllable by the user.

### Usability

- First-time setup target: under 2 minutes for a user who can grant permissions and complete face enrollment without errors.
- The main status must be understandable without opening technical logs.
- Every required permission must have a short explanation and a recovery path.
- The user must be able to stop monitoring from both the app and the persistent notification.

### Compatibility

- Minimum OS target: Android 10 (API 29), subject to a technical proof of concept and release-policy validation.
- The app MUST be tested on supported Android versions and multiple OEM implementations, particularly for camera foreground services, notification access, and screen-timeout modification.
- The project MUST publish an explicit list of tested media-player apps and known limitations.

## 11. Success Metrics and Acceptance Targets

Metrics are release targets to verify, not claims of existing performance.

| Metric | Target | Measurement |
|---|---|---|
| Absence pause latency | 95% of supported-player test runs pause within 2 seconds after the configured grace period expires | Automated and manual scenario tests |
| Identity-aware resume | At least 99% of controlled non-enrolled-person trials do not trigger Play | Test matrix covering different faces, distances, angles, and lighting |
| Correct auto-resume | At least 95% of trials resume within 3 seconds after stable enrolled-user verification | Supported-player test suite |
| Manual-pause respect | 100% of defined manual-pause test cases remain paused until user action or a new explicit playback instance | State-machine tests and device tests |
| Supported-player compatibility | At least 80% of the release-gate app/device combinations successfully pause and resume | Published compatibility matrix |
| Setup completion | At least 90% of test participants complete setup within 2 minutes without assistance | Usability test |
| Preview correctness | 100% of diagnostic scenarios distinguish face detection from enrolled identity match | QA test suite |
| Timeout restoration | 100% restoration after normal expiry/stop across supported test devices | Device tests; interrupted-process cases recorded separately |
| Battery drain | No more than 8 percentage points/hour incremental drain against a documented screen-on baseline | 60-minute device tests |
| Privacy | Zero network requests carrying frames, face templates, or viewing content metadata | Network inspection and code review |

### Release gate

Do not release automatic resume until identity false-resume tests, manual-pause tests, and supported-player controls pass. Do not enable screen-timeout modification by default. If reliable automatic control cannot be demonstrated for at least a small, clearly named group of players, narrow the MVP to those players instead of claiming universal compatibility.

## 12. MVP Scope and Prioritization

### In scope

- Native Android app.
- Local face enrollment and verification.
- Explicit start/stop monitoring session.
- Automatic active media-session inspection.
- Built-in compatibility registry for tested video-capable apps.
- Automatic pause-on-absence and resume-on-return for supported players.
- Safe manual-pause handling.
- Optional front-camera diagnostic preview.
- Presence and permission status indicators.
- Persistent notification with Stop control.
- Configurable absence grace period: 3, 5, or 10 seconds.
- Optional temporary screen-timeout override with 15/30/60 minutes and Until stopped modes.
- Local diagnostics that contain state transitions and errors only, without face data or content titles.
- Local enrollment deletion and privacy disclosures.

### Out of scope for MVP

- Universal support for all installed video apps.
- Automatic detection/control using Accessibility Services or screen scraping.
- Background auto-start after reboot or without explicit user action.
- Cloud sync, user accounts, subscriptions, advertisements, and remote analytics.
- Custom user-defined media-player integration rules.
- Voice commands, gestures, gaze tracking, and attention scoring.
- Multi-face profiles, multiple enrolled users, and household profiles.
- Liveness/anti-spoofing guarantees.
- Cross-device sync or wearables.

## 13. Recommended Technical Direction

This section defines a direction for feasibility, not a final architecture lock.

- **Platform:** Native Android, Kotlin.
- **UI:** Jetpack Compose.
- **Camera:** CameraX with a camera foreground service for a user-started monitoring session.
- **Face detection:** ML Kit Face Detection or an equivalent on-device detector. Detection alone is not sufficient for identity verification.
- **Face verification:** On-device face-embedding model/runtime selected by a benchmark spike. Do not select a model until accuracy, model size, licensing, offline operation, and performance have been tested on low-end devices.
- **Media control:** Android `MediaSessionManager` / `MediaController` mechanisms with notification-listener access where required.
- **Local settings:** Jetpack DataStore or equivalent lightweight local storage.
- **Template protection:** Android Keystore-backed encryption where applicable.
- **Networking:** No backend and no network calls in the MVP.
- **Permissions:** Camera; notification-listener access; foreground-service camera permissions required by the Android version; notification permission where applicable; optional modify-system-settings special access.
- **Prohibited implementation shortcuts:** `QUERY_ALL_PACKAGES` as a blanket discovery mechanism; Accessibility Service UI automation; cloud face recognition; storing raw camera images; auto-resume on uncertain identity.

### Required feasibility spikes before full implementation

1. Confirm a camera foreground service can continue processing after the user switches to a target video app on each supported Android version.
2. Confirm active media-session visibility and pause/play controls for the initial candidate player apps.
3. Benchmark an on-device face-verification model on at least one low-end device and two mid-range devices.
4. Verify how screen-timeout modification behaves across target OS versions and OEMs, including user revocation, process death, and force-stop cases.
5. Test state reconciliation when multiple media sessions exist, the user switches apps, playback ends, or the user manually pauses.

A failure in any spike MUST reduce MVP scope or change the implementation plan before production implementation begins.

## 14. Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Some apps do not expose usable media sessions or pause/play actions | Core behavior fails for those apps | Keep a tested compatibility registry; report unsupported apps honestly; do not use UI automation as a fallback |
| Media sessions may represent audio, not video | Audio may be paused accidentally | Control only sessions from the built-in tested video-app registry; never control unknown sessions by default |
| Face model falsely matches a different person | Unwanted auto-resume | Calibrate the threshold; require stable verification; test diverse conditions; never resume on uncertain identity |
| Low light, occlusion, phone movement, or camera contention | False absence or service failure | Grace periods, explicit UNKNOWN state, camera diagnostics, clear recovery path |
| Battery drain from continuous camera processing | Reduced usability | Low frame sampling, bounded inference, measure against baseline, stop camera immediately when monitoring stops |
| Android/OEM background limits terminate the service | Detection stops unexpectedly | Foreground service, persistent notification, device testing, explicit service health indicator, no claim of guaranteed uninterrupted operation |
| Screen timeout remains changed after force-stop or crash | Unexpected system behavior | Persist original value, restoration recovery on next launch, visible restore control, disclose limitations, test interrupted-process behavior |
| Notification-listener permission discourages users | Onboarding drop-off | Explain exactly why it is needed; request it only after explaining value; provide a working diagnostics screen |
| Spoofed image of enrolled user triggers resume | False resume | Disclose limitation; liveness/anti-spoofing is a future enhancement, not implied by MVP |

## 15. Analytics and Data Policy

The MVP does not require remote analytics. Product success can be evaluated through opt-in user testing and local diagnostic events. Local events may include timestamps and state transitions such as `monitoring_started`, `supported_session_detected`, `absence_confirmed`, `pause_sent`, `identity_verified`, `play_sent`, and `monitoring_stopped`. Events MUST NOT contain image data, embeddings, face landmarks, notification text, video titles, or unrelated app data.

Any future remote telemetry requires a separate privacy review and explicit scope approval. It is not implied by this PRD.

## 16. QA Acceptance Scenarios

1. **Enrolled user leaves:** A supported player is playing; no matching face is verified for longer than the selected grace period; the player pauses.
2. **Enrolled user returns:** StayFrame owns the pause; the same media session remains valid; identity is stable; the player resumes.
3. **Different person appears:** A non-enrolled face is detected; no Play command is issued.
4. **No face / obstructed camera:** The system waits through the grace period and pauses if absence remains confirmed; it never resumes based on uncertainty.
5. **User manually pauses:** StayFrame does not resume when the enrolled user returns.
6. **Playback changes to a different app/session:** Old pause ownership is invalidated; the new session is not resumed based on a prior session's state.
7. **Unsupported player:** The app reports unsupported status and does not issue playback commands.
8. **No media session:** Monitoring remains in `WAITING_FOR_PLAYER`; no player is controlled.
9. **Preview hidden:** Face verification continues while monitoring remains active; no live imagery is displayed.
10. **Monitoring stopped:** Camera processing ends and the persistent monitoring notification is removed or updated appropriately.
11. **Camera permission revoked:** The app stops camera processing, reports the permission problem, and does not resume playback.
12. **Notification-listener access revoked:** The app reports that cross-app media control is unavailable and does not pretend monitoring is functioning.
13. **Timeout expires normally:** The original screen-timeout value is restored.
14. **Session is force-stopped or interrupted:** On the next launch, the app detects saved timeout-override state and offers or performs recovery; test results and OS limitations are documented.
15. **Face enrollment deleted:** The template and related local enrollment data are deleted, and monitoring cannot perform identity verification until re-enrollment.

## 17. Dependencies and Open Decisions

These are explicit implementation gates, not permission to expand scope implicitly.

- Select and benchmark an on-device face-embedding model with a suitable license.
- Define the initial supported-player list from target-user research and real-device validation.
- Decide exact minimum Android version after foreground camera service and media-session tests.
- Validate Play Store policy requirements for notification-listener access, camera foreground service declarations, privacy disclosures, and requested permissions before distribution.
- Determine which OEM/OS combinations support the screen-timeout override reliably enough to ship.
- Choose a local event-log retention period; default proposal is the latest 100 state/error events, stored locally and deletable by the user.

No open decision may be silently treated as completed. If a dependency fails feasibility testing, update this PRD and narrow the feature scope before implementation proceeds.

## 18. Future Roadmap (Not MVP Commitments)

### Phase 2: Reliability improvements

- Expand the supported-player registry based on measured demand.
- Add an optional per-app allow/deny control if automatic classification causes false positives.
- Improve robustness across lighting, orientation, and camera-quality variations.
- Add a privacy-reviewed liveness check if false resumes caused by photos/videos prove to be a material user problem.

### Phase 3: Quality-of-life improvements

- Optional user-defined grace-period presets.
- More detailed, privacy-safe local diagnostics.
- Device/OEM-specific compatibility guidance.
- Optional shortcut or Quick Settings entry for starting/stopping a session, subject to Android lifecycle constraints.

### Explicitly not planned without new evidence

- Cloud AI, social features, user accounts, remote viewing, or broad installed-app inventory.
- Full support claims for apps that cannot be controlled through supported Android media APIs.

## 19. Technical References

These references justify key Android feasibility constraints and must be rechecked during implementation because Android behavior and distribution policies can change.

1. Android Developers — [Foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start): camera foreground services must follow background-start and while-in-use permission restrictions.
2. Android Developers — [Foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types): camera foreground service type and permission requirements.
3. Android Developers — [MediaSessionManager](https://developer.android.com/reference/android/media/session/MediaSessionManager): active media-session access requirements.
4. Android Developers — [MediaController.TransportControls](https://developer.android.com/reference/android/media/session/MediaController.TransportControls): supported pause and play transport commands.
5. Android Developers — [Package visibility](https://developer.android.com/training/package-visibility): limits on visibility into installed apps and the approval implications of broad package visibility.
6. Google Developers — [ML Kit Face Detection](https://developers.google.com/ml-kit/vision/face-detection): face detection locates faces and facial features but does not recognize individuals.
7. Android Developers — [Settings.System](https://developer.android.com/reference/android/provider/Settings.System): modifying system settings requires declared permission and user-granted special access where applicable.
8. Google Play Console Help — [Developer Program Policy](https://support.google.com/googleplay/android-developer/answer/18258653): Accessibility API policy constraints relevant to avoiding UI-automation workarounds.

---

## Final Product Decision

Proceed only with a technical proof of concept for camera background operation, face verification, media-session control, and screen-timeout restoration. The product's core value is identity-aware pause/resume, but it is viable only for player apps and Android device configurations that can be controlled reliably through supported APIs. The MVP must favor correct behavior and transparent limitations over a misleading promise to work with every installed video player.
