package com.example.domain.model

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import com.example.ui.theme.OceanDrift
import com.example.ui.theme.Slate

/**
 * The 8 core Presence Engine States specified in PRD Section 7.4
 */
enum class PresenceState(
    val title: String,
    val description: String,
    val indicatorColor: Color
) {
    INACTIVE(
        title = "Inactive",
        description = "Monitoring has not started or has stopped.",
        indicatorColor = Slate
    ),
    WAITING_FOR_PLAYER(
        title = "Waiting for Player",
        description = "Camera monitoring active. Waiting for supported video playback.",
        indicatorColor = StatusAmber
    ),
    PRESENT(
        title = "User Present",
        description = "Viewer present in front camera. Playback ongoing.",
        indicatorColor = StatusGreen
    ),
    ABSENCE_GRACE(
        title = "Absence Grace",
        description = "Viewer absent. Counting down grace period before pausing.",
        indicatorColor = StatusAmber
    ),
    PAUSED_BY_GUARD(
        title = "Paused by Guard",
        description = "Playback paused and rewound to departure frame. Awaiting return.",
        indicatorColor = OceanDrift
    ),
    OTHER_FACE(
        title = "Unrecognized Face",
        description = "A face is visible, but does not match enrolled template. Playback held.",
        indicatorColor = StatusAmber
    ),
    UNKNOWN(
        title = "Unknown / Obstructed",
        description = "Camera view obstructed or lighting insufficient for presence detection.",
        indicatorColor = StatusAmber
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
        title = "Strict Biometric",
        subtitle = "Resumes only when enrolled face matches"
    ),
    RELAXED_FALLBACK(
        title = "Relaxed Low-Light",
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
