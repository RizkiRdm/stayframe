# StayFrame — Manual Testing Matrix

This document provides the QA validation protocol and manual test matrix for StayFrame across supported Android video players (**YouTube**, **VLC**, **MX Player**, and **Google Chrome**).

---

## 1. Environment & Setup Checklist

Before initiating manual testing, verify the following prerequisites on the test device:
- [ ] Device running Android 8.0+ (API 26+) with working front-facing camera.
- [ ] Notification Listener Access granted for StayFrame (`Settings > Apps & notifications > Special app access > Notification access`).
- [ ] Camera runtime permission granted.
- [ ] Battery optimization disabled for StayFrame (ensures background foreground service is not killed).
- [ ] Test players installed:
  - **YouTube**: `com.google.android.youtube`
  - **VLC for Android**: `org.videolan.vlc`
  - **MX Player**: `com.mxtech.videoplayer.ad` or `com.mxtech.videoplayer.pro`
  - **Google Chrome**: `com.android.chrome` (playing HTML5 full-screen or background video)

---

## 2. Test Execution Matrix by Player

| # | Test Scenario | Player: YouTube | Player: VLC | Player: MX Player | Player: Chrome | Pass / Fail Criteria |
|---|---|---|---|---|---|---|
| **1** | **Absence Auto-Pause & Rewind** | Play video at timestamp 02:00. Step out of camera view. | Play video at 02:00. Step out of camera view. | Play video at 02:00. Step out of camera view. | Play web video at 02:00. Step out of camera view. | After 5s absence grace, player pauses automatically and rewinds exactly -5s (resting at ~01:55). |
| **2** | **Viewer Return Auto-Resume (Relaxed Mode)** | With video paused by guard, re-enter camera view (>2s). | With video paused by guard, re-enter camera view (>2s). | With video paused by guard, re-enter camera view (>2s). | With video paused by guard, re-enter camera view (>2s). | Sustained presence progress bar fills 0 to 2s. Video automatically resumes at ~01:55 without touching screen. |
| **3** | **Passerby Rejection (<2s)** | Walk past camera in <1.5s while paused by guard. | Walk past camera in <1.5s while paused by guard. | Walk past camera in <1.5s while paused by guard. | Walk past camera in <1.5s while paused by guard. | Presence timer starts but resets when face leaves. Video remains paused; zero false resumes. |
| **4** | **Manual User Pause Immunity** | While video is playing, manually tap Pause in YouTube. Walk away for 10s and return. | Manually tap Pause in VLC. Walk away for 10s and return. | Manually tap Pause in MX Player. Walk away for 10s and return. | Manually tap Pause in Chrome. Walk away for 10s and return. | Manual pause is detected (`manualPauseDetected=true`). Guard pause ownership is cleared. Video does **not** auto-resume upon returning. |
| **5** | **Manual User Play Freedom** | While paused by guard, tap Play in YouTube UI or notification. | While paused by guard, tap Play in VLC UI or notification. | While paused by guard, tap Play in MX Player. | While paused by guard, tap Play in Chrome media notification. | Video immediately starts playing. State transitions cleanly to `PRESENT`. No fight or pause-loop occurs. |
| **6** | **Camera Covered / Obstructed** | Place thumb or tape over front camera while video is playing. | Place thumb over front camera while video is playing. | Place thumb over front camera while video is playing. | Place thumb over front camera while video is playing. | State transitions to `UNKNOWN`. After 5s grace period expires, video pauses and rewinds -5s. |
| **7** | **Variable Playback Speed Extrapolation** | Set speed to 2.0x. Walk away at 01:00. | Set speed to 1.5x. Walk away at 01:00. | Set speed to 0.5x. Walk away at 01:00. | Normal 1.0x speed in Chrome. Walk away at 01:00. | Rewind target correctly accounts for elapsed time and speed rate (`targetPosition = max(0, current - (grace * speed))`). |
| **8** | **Player Switching & Backgrounding** | Switch from YouTube to VLC while monitoring is active. | Switch from VLC to Chrome while monitoring is active. | Switch from MX Player to Home screen. | Switch tab in Chrome while background audio plays. | Old pause ownership is invalidated on package change (`session_changed`). Active player card updates instantly. |
| **9** | **Screen Timeout Keep-Awake** | Select "Keep Screen On: 15m". Leave phone untouched. | Select "Keep Screen On: 30m". | Select "Until Stopped". | Select "Keep Screen On: OFF". | Screen remains awake while video plays. Upon stopping monitoring, initial system screen timeout is restored. |
| **10** | **Notification Controls Sync** | Tap "Stop Monitoring" button in StayFrame ongoing notification. | Tap "Stop Monitoring" in ongoing notification. | Tap "Stop Monitoring" in ongoing notification. | Tap "Stop Monitoring" in ongoing notification. | Foreground service stops cleanly, camera releases immediately, notification dismisses, and timeout restores. |

---

## 3. Player-Specific Nuances & Edge Cases

### YouTube (`com.google.android.youtube`)
- **Ad Breaks**: If an unskippable ad plays, YouTube session status remains `Playing`. Absence guard rewinds ad timestamp.
- **Picture-in-Picture (PiP)**: When minimized to PiP, StayFrame continues observing the active media session and controls playback normally.

### VLC for Android (`org.videolan.vlc`)
- **Seek Precision**: VLC supports millisecond-accurate seeking via `MediaController.TransportControls.seekTo()`.
- **Audio-Only Mode**: If video is backgrounded to background audio, playback state is maintained.

### MX Player (`com.mxtech.videoplayer.ad` / `.pro`)
- **Hardware Decoder (HW vs HW+ vs SW)**: Ensure playback state callback reflects `PlaybackState.STATE_PLAYING` across all decoding engines.
- **Lock Screen**: MX Player has a built-in touch lock. MediaController transport controls operate via system session and bypass UI touch lock.

### Google Chrome (`com.android.chrome`)
- **HTML5 Fullscreen**: Active session is identified under package `com.android.chrome`. Media metadata (page title or video title) is tracked.
- **Tab Backgrounding**: Chrome holds media notification controls during background tab playback.

---

## 4. Sign-off Criteria
A build is certified for release once all 10 scenarios achieve **PASS** on physical hardware across at least 2 distinct Android OS versions (e.g. Android 11 and Android 14).
