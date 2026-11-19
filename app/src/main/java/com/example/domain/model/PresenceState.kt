package com.example.domain.model

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.OceanDrift
import com.example.ui.theme.Slate
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed

/**
 * Full Presence & Monitoring Lifecycle State Machine covering:
 * - Monitoring disabled (INACTIVE)
 * - Monitoring enabled, no supported player active (WAITING_FOR_PLAYER)
 * - Supported player detected / intentionally paused (PLAYER_DETECTED)
 * - Player active and monitoring (PRESENT)
 * - Absence countdown & guard pause (ABSENCE_GRACE, PAUSED_BY_GUARD)
 * - Strict identity mismatch & obstruction (OTHER_FACE, UNKNOWN)
 * - Background / standby / unavailable / process recovery (PLAYER_IN_BACKGROUND, PLAYER_UNAVAILABLE, RECOVERING, ERROR)
 */
enum class PresenceState(
    val title: String,
    val description: String,
    val indicatorColor: Color
) {
    INACTIVE(
        title = "Inactive",
        description = "Monitoring is disabled.",
        indicatorColor = Slate
    ),
    WAITING_FOR_PLAYER(
        title = "Waiting for Player",
        description = "Guard armed. Waiting for a supported video player.",
        indicatorColor = StatusAmber
    ),
    PLAYER_DETECTED(
        title = "Player Detected",
        description = "Supported player ready (paused or buffering). Auto-play disabled.",
        indicatorColor = OceanDrift
    ),
    PRESENT(
        title = "User Present",
        description = "Viewer present in front camera. Playback monitored.",
        indicatorColor = StatusGreen
    ),
    ABSENCE_GRACE(
        title = "Absence Grace",
        description = "Viewer absent. Counting down adaptive grace period before pausing.",
        indicatorColor = StatusAmber
    ),
    PAUSED_BY_GUARD(
        title = "Paused by Guard",
        description = "Playback paused and rewound to departure frame. Awaiting return.",
        indicatorColor = OceanDrift
    ),
    OTHER_FACE(
        title = "Unrecognized Face",
        description = "Face does not match enrolled viewer profile. Counting down grace.",
        indicatorColor = StatusAmber
    ),
    UNKNOWN(
        title = "Unknown / Obstructed",
        description = "Camera view obstructed or face unavailable in strict mode.",
        indicatorColor = StatusAmber
    ),
    PLAYER_IN_BACKGROUND(
        title = "Standby (Background)",
        description = "Switched away from video player. Guard remains armed for return.",
        indicatorColor = Slate
    ),
    PLAYER_UNAVAILABLE(
        title = "Player Unavailable",
        description = "Previously active player became unavailable or uninstalled.",
        indicatorColor = StatusAmber
    ),
    RECOVERING(
        title = "Recovering Session",
        description = "Restoring armed guard session after system interruption.",
        indicatorColor = OceanDrift
    ),
    ERROR(
        title = "Error",
        description = "Camera or notification permission revoked, or hardware fault.",
        indicatorColor = StatusRed
    );

    val isMonitoringActive: Boolean
        get() = this != INACTIVE && this != ERROR
}

/**
 * Detection mode for face verification
 */
enum class DetectionMode(val title: String, val subtitle: String) {
    STRICT(
        title = "Strict Face ID",
        subtitle = "Matches enrolled facial geometry before resuming playback"
    ),
    RELAXED_FALLBACK(
        title = "Relaxed Presence",
        subtitle = "Auto-resumes on any face sustained > 2 sec"
    )
}

/**
 * Screen timeout override duration
 */
enum class ScreenTimeoutMode(val title: String, val durationMinutes: Int?) {
    OFF("Off", null),
    MIN_15("15 min", 15),
    MIN_30("30 min", 30),
    MIN_60("60 min", 60),
    UNTIL_STOPPED("Until stopped", -1)
}
