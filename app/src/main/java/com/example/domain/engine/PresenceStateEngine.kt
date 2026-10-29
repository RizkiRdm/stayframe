package com.example.domain.engine

import com.example.domain.model.ActiveMediaSessionInfo
import com.example.domain.model.DetectionMode
import com.example.domain.model.DiagnosticEvent
import com.example.domain.model.PresenceState
import kotlin.math.max

/**
 * Presence observation data passed from camera frame processor
 */
data class FaceObservation(
    val faceDetected: Boolean,
    val identityMatchConfidence: Float, // 0.0f to 1.0f
    val isEnrolledMatch: Boolean,
    val faceBoundingBox: List<Float>? = null, // [left, top, right, bottom] normalized
    val lightingAdequate: Boolean = true
)

interface PresenceEngineCallback {
    fun onStateChanged(newState: PresenceState, previousState: PresenceState)
    fun onSendPauseCommand(targetPackage: String, currentPositionMs: Long)
    fun onSendRewindCommand(targetPackage: String, targetPositionMs: Long, rewindOffsetMs: Long)
    fun onSendPlayCommand(targetPackage: String)
    fun onDiagnosticLog(event: DiagnosticEvent)
}

/**
 * Core Presence State Machine enforcing all rules from PRD Section 7.4,
 * Thread-safety (Point 2), Strict Non-Match Absence Countdown (Point 3),
 * Low-Light Unblocked Relaxed Resume (Point 4),
 * and Not-Playing Ownership Guard (Point 5).
 */
class PresenceStateEngine(
    private var absenceGracePeriodSeconds: Int = 5,
    private var detectionMode: DetectionMode = DetectionMode.RELAXED_FALLBACK,
    private val callback: PresenceEngineCallback? = null
) {
    private val lock = Any()

    var currentState: PresenceState = PresenceState.INACTIVE
        get() = synchronized(lock) { field }
        private set

    /** Active player session being observed */
    var activeSession: ActiveMediaSessionInfo = ActiveMediaSessionInfo()
        get() = synchronized(lock) { field }
        private set

    /** Ownership tracking: Did StayFrame initiate the pause on this exact session? */
    var pauseOwnership: Boolean = false
        get() = synchronized(lock) { field }
        private set

    /** Package name of the media session StayFrame currently owns a pause for */
    private var ownedPackageName: String? = null

    /** Manual pause guard: true if player was paused by the user */
    var manualPauseDetected: Boolean = false
        get() = synchronized(lock) { field }
        private set

    /** Real error message for UI display (Point 6) */
    var lastErrorMessage: String? = null
        get() = synchronized(lock) { field }
        private set

    /** Timers and tracking */
    private var absenceStartTimeMs: Long = 0L
    private var stablePresentStartTimeMs: Long = 0L
    private var sustainedPresenceStartTimeMs: Long = 0L

    /** Enrolled face similarity threshold */
    var matchThreshold: Float = 0.72f

    /** Required stable present duration in strict mode */
    private val strictStableResumeDurationMs = 1000L

    /** Required sustained presence duration in relaxed fallback mode (filters passersby) */
    private val relaxedSustainedPresenceDurationMs = 2000L

    fun setAbsenceGracePeriod(seconds: Int) = synchronized(lock) {
        absenceGracePeriodSeconds = when (seconds) {
            3, 5, 10 -> seconds
            else -> 5
        }
    }

    fun getAbsenceGracePeriodSeconds(): Int = synchronized(lock) { absenceGracePeriodSeconds }

    fun setDetectionMode(mode: DetectionMode) = synchronized(lock) {
        detectionMode = mode
    }

    fun getDetectionMode(): DetectionMode = synchronized(lock) { detectionMode }

    /**
     * Start monitoring session
     */
    fun startMonitoring() = synchronized(lock) {
        if (currentState == PresenceState.INACTIVE || currentState == PresenceState.ERROR) {
            lastErrorMessage = null
            val nextState = if (activeSession.isSupported && activeSession.isPlaying) {
                PresenceState.PRESENT
            } else {
                PresenceState.WAITING_FOR_PLAYER
            }
            transitionTo(nextState, "Monitoring started by user")
            logEvent("monitoring_started", "Session initialized in mode: ${detectionMode.name}")
        }
    }

    /**
     * Stop monitoring session
     */
    fun stopMonitoring() = synchronized(lock) {
        if (currentState != PresenceState.INACTIVE) {
            pauseOwnership = false
            ownedPackageName = null
            manualPauseDetected = false
            resetTimers()
            transitionTo(PresenceState.INACTIVE, "Monitoring stopped by user")
            logEvent("monitoring_stopped", "Session cleanly terminated")
        }
    }

    /**
     * Update active media session info from NotificationListenerService
     */
    fun updateActiveMediaSession(session: ActiveMediaSessionInfo) = synchronized(lock) {
        val previous = activeSession
        activeSession = session

        if (currentState == PresenceState.INACTIVE) return

        // Session package changed
        if (session.packageName != previous.packageName) {
            if (pauseOwnership && previous.packageName == ownedPackageName) {
                // Invalidate old ownership on session change (PRD PRES-07)
                pauseOwnership = false
                ownedPackageName = null
                logEvent("session_changed", "Package changed to ${session.packageName}; prior pause ownership cleared")
            }
        }

        // Detect external manual pause
        if (!session.isPlaying && previous.isPlaying && currentState != PresenceState.PAUSED_BY_GUARD) {
            manualPauseDetected = true
            pauseOwnership = false
            ownedPackageName = null
            logEvent("manual_pause_detected", "User or external action paused playback. Auto-resume locked.")
        }

        // Detect external play / resume
        if (session.isPlaying && !previous.isPlaying) {
            manualPauseDetected = false
            pauseOwnership = false
            ownedPackageName = null
            if (currentState == PresenceState.PAUSED_BY_GUARD || currentState == PresenceState.WAITING_FOR_PLAYER) {
                transitionTo(PresenceState.PRESENT, "External playback detected")
            }
        }

        // Transition from WAITING_FOR_PLAYER if supported player starts playing
        if (currentState == PresenceState.WAITING_FOR_PLAYER && session.isSupported && session.isPlaying) {
            transitionTo(PresenceState.PRESENT, "Supported player is now playing: ${session.playerDisplayName}")
        } else if (currentState != PresenceState.WAITING_FOR_PLAYER && (!session.isSupported || session.packageName == null)) {
            if (currentState != PresenceState.PAUSED_BY_GUARD) {
                transitionTo(PresenceState.WAITING_FOR_PLAYER, "No active supported player")
            }
        }
    }

    /**
     * Process face observation from camera analysis (sampled ~1 FPS)
     */
    fun processObservation(observation: FaceObservation, currentTimeMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (currentState == PresenceState.INACTIVE || currentState == PresenceState.ERROR) return

        if (!activeSession.isSupported || activeSession.packageName == null) {
            if (currentState != PresenceState.PAUSED_BY_GUARD) {
                if (currentState != PresenceState.WAITING_FOR_PLAYER) {
                    transitionTo(PresenceState.WAITING_FOR_PLAYER, "Waiting for supported video player")
                }
                return
            }
        }

        if (!observation.faceDetected) {
            handleFaceAbsent(currentTimeMs)
        } else {
            handleFacePresent(observation, currentTimeMs)
        }
    }

    private fun handleFaceAbsent(currentTimeMs: Long) {
        // Reset resume timers
        stablePresentStartTimeMs = 0L
        sustainedPresenceStartTimeMs = 0L

        when (currentState) {
            PresenceState.PRESENT, PresenceState.OTHER_FACE, PresenceState.UNKNOWN -> {
                absenceStartTimeMs = currentTimeMs
                transitionTo(PresenceState.ABSENCE_GRACE, "User not detected; started grace period")
                logEvent("absence_detected", "Starting ${absenceGracePeriodSeconds}s grace countdown")
            }
            PresenceState.ABSENCE_GRACE -> {
                val elapsedMs = currentTimeMs - absenceStartTimeMs
                val graceLimitMs = absenceGracePeriodSeconds * 1000L

                if (elapsedMs >= graceLimitMs) {
                    triggerAutoPauseAndRewind(currentTimeMs)
                }
            }
            PresenceState.PAUSED_BY_GUARD, PresenceState.WAITING_FOR_PLAYER, PresenceState.INACTIVE, PresenceState.ERROR -> {
                // Remain in current state
            }
        }
    }

    /**
     * Point 5: If player is NOT playing, do NOT set ownership and do NOT enter PAUSED_BY_GUARD
     */
    private fun triggerAutoPauseAndRewind(currentTimeMs: Long) {
        val targetPackage = activeSession.packageName

        // Guard: if player is not playing or not supported, do NOT set ownership or enter PAUSED_BY_GUARD
        if (targetPackage == null || !activeSession.isSupported || !activeSession.isPlaying) {
            pauseOwnership = false
            ownedPackageName = null
            logEvent("skip_auto_pause", "Player is not playing (isPlaying=${activeSession.isPlaying}); skipping pause and guard ownership")
            return
        }

        val currentPos = activeSession.playbackPositionMs
        val rewindOffsetMs = absenceGracePeriodSeconds * 1000L
        val targetPositionMs = max(0L, currentPos - rewindOffsetMs)

        // 1. Send pause command
        callback?.onSendPauseCommand(targetPackage, currentPos)

        // 2. Send immediate rewind command (seekTo departure moment)
        callback?.onSendRewindCommand(targetPackage, targetPositionMs, rewindOffsetMs)

        pauseOwnership = true
        ownedPackageName = targetPackage
        manualPauseDetected = false

        logEvent(
            "auto_pause_and_rewind",
            "Paused $targetPackage and rewound -${absenceGracePeriodSeconds}s to departure timestamp ($targetPositionMs ms)",
            rewindOffsetMs
        )

        transitionTo(PresenceState.PAUSED_BY_GUARD, "Absence grace expired; video paused and rewound")
    }

    private fun handleFacePresent(observation: FaceObservation, currentTimeMs: Long) {
        val isStrictMatch = observation.isEnrolledMatch || observation.identityMatchConfidence >= matchThreshold

        if (detectionMode == DetectionMode.STRICT) {
            if (isStrictMatch) {
                // Valid enrolled user in view
                absenceStartTimeMs = 0L
                handleStrictUserPresent(currentTimeMs)
            } else {
                // Point 3: Strict mode non-match is treated as ABSENT (timer grace continues to tick down)
                handleStrictUnrecognizedFace(currentTimeMs)
            }
        } else {
            // RELAXED_FALLBACK Mode: Any face sustained for > 2 seconds
            absenceStartTimeMs = 0L
            handleRelaxedFacePresent(currentTimeMs)
        }
    }

    /**
     * Point 3: Non-matching face in strict mode is treated as absent; grace countdown continues
     */
    private fun handleStrictUnrecognizedFace(currentTimeMs: Long) {
        stablePresentStartTimeMs = 0L
        sustainedPresenceStartTimeMs = 0L

        when (currentState) {
            PresenceState.PRESENT -> {
                absenceStartTimeMs = currentTimeMs
                transitionTo(PresenceState.OTHER_FACE, "Detected face does not match enrolled user; absence grace started")
                logEvent("unrecognized_face_absent", "Non-matching face in strict mode; starting ${absenceGracePeriodSeconds}s grace countdown")
            }
            PresenceState.OTHER_FACE, PresenceState.ABSENCE_GRACE -> {
                val elapsedMs = currentTimeMs - absenceStartTimeMs
                val graceLimitMs = absenceGracePeriodSeconds * 1000L

                if (elapsedMs >= graceLimitMs) {
                    triggerAutoPauseAndRewind(currentTimeMs)
                }
            }
            PresenceState.PAUSED_BY_GUARD -> {
                // Non-matching face in strict mode never triggers auto-resume
            }
            else -> {}
        }
    }

    private fun handleStrictUserPresent(currentTimeMs: Long) {
        if (stablePresentStartTimeMs == 0L) {
            stablePresentStartTimeMs = currentTimeMs
        }

        val stableDuration = currentTimeMs - stablePresentStartTimeMs

        if (currentState == PresenceState.PAUSED_BY_GUARD) {
            if (manualPauseDetected) {
                logEvent("resume_ignored", "User manually paused playback; automatic resume skipped")
                transitionTo(PresenceState.PRESENT, "User present (manual pause respected)")
                return
            }

            if (stableDuration >= strictStableResumeDurationMs) {
                triggerAutoResume()
            }
        } else if (currentState == PresenceState.ABSENCE_GRACE ||
            currentState == PresenceState.OTHER_FACE ||
            currentState == PresenceState.UNKNOWN
        ) {
            transitionTo(PresenceState.PRESENT, "Enrolled user verified present")
        }
    }

    private fun handleRelaxedFacePresent(currentTimeMs: Long) {
        if (sustainedPresenceStartTimeMs == 0L) {
            sustainedPresenceStartTimeMs = currentTimeMs
            logEvent("face_detected_relaxed", "Face in view, validating 2.0s sustained presence...")
        }

        val sustainedDuration = currentTimeMs - sustainedPresenceStartTimeMs

        if (currentState == PresenceState.PAUSED_BY_GUARD) {
            if (manualPauseDetected) {
                transitionTo(PresenceState.PRESENT, "Face present (manual pause respected)")
                return
            }

            // Must be sustained > 2.0 seconds to filter out passersby under 2 seconds!
            if (sustainedDuration >= relaxedSustainedPresenceDurationMs) {
                triggerAutoResume()
            }
        } else if (currentState == PresenceState.ABSENCE_GRACE ||
            currentState == PresenceState.OTHER_FACE ||
            currentState == PresenceState.UNKNOWN
        ) {
            transitionTo(PresenceState.PRESENT, "Sustained face presence detected in relaxed mode")
        }
    }

    private fun triggerAutoResume() {
        val targetPackage = activeSession.packageName
        if (pauseOwnership && targetPackage != null && targetPackage == ownedPackageName) {
            callback?.onSendPlayCommand(targetPackage)
            pauseOwnership = false
            ownedPackageName = null
            logEvent("auto_resume_sent", "Resuming playback on $targetPackage")
        }
        transitionTo(PresenceState.PRESENT, "Viewer returned; playback resumed")
        resetTimers()
    }

    /**
     * Point 4: In RELAXED_FALLBACK mode, low-light / obstruction does not block resume
     */
    fun handleUnknownOrObstructed(currentTimeMs: Long, reason: String) = synchronized(lock) {
        if (detectionMode == DetectionMode.RELAXED_FALLBACK) {
            // Do not block resume in relaxed mode
            return
        }
        stablePresentStartTimeMs = 0L
        sustainedPresenceStartTimeMs = 0L

        if (currentState == PresenceState.PRESENT) {
            absenceStartTimeMs = currentTimeMs
            transitionTo(PresenceState.UNKNOWN, reason)
        } else if (currentState == PresenceState.UNKNOWN || currentState == PresenceState.ABSENCE_GRACE) {
            val elapsedMs = currentTimeMs - absenceStartTimeMs
            if (elapsedMs >= (absenceGracePeriodSeconds * 1000L)) {
                triggerAutoPauseAndRewind(currentTimeMs)
            }
        }
    }

    /**
     * Manual resume by user from app UI or notification
     */
    fun userRequestResume() = synchronized(lock) {
        val targetPackage = activeSession.packageName
        if (targetPackage != null && activeSession.isSupported) {
            callback?.onSendPlayCommand(targetPackage)
            pauseOwnership = false
            manualPauseDetected = false
            transitionTo(PresenceState.PRESENT, "User manually triggered Play")
            logEvent("manual_play_triggered", "Play sent to $targetPackage")
        }
    }

    fun reportError(errorMessage: String) = synchronized(lock) {
        lastErrorMessage = errorMessage
        transitionTo(PresenceState.ERROR, errorMessage)
        logEvent("engine_error", errorMessage)
    }

    private fun resetTimers() {
        absenceStartTimeMs = 0L
        stablePresentStartTimeMs = 0L
        sustainedPresenceStartTimeMs = 0L
    }

    private fun transitionTo(newState: PresenceState, reason: String) {
        if (currentState != newState) {
            val prev = currentState
            currentState = newState
            callback?.onStateChanged(newState, prev)
        }
    }

    private fun logEvent(name: String, details: String, rewindOffsetMs: Long? = null) {
        val event = DiagnosticEvent(
            state = currentState,
            eventName = name,
            details = details,
            rewindOffsetMs = rewindOffsetMs
        )
        callback?.onDiagnosticLog(event)
    }

    fun getRemainingGraceSeconds(currentTimeMs: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        if (currentState != PresenceState.ABSENCE_GRACE &&
            currentState != PresenceState.UNKNOWN &&
            currentState != PresenceState.OTHER_FACE
        ) return absenceGracePeriodSeconds

        if (absenceStartTimeMs == 0L) return absenceGracePeriodSeconds
        val elapsed = (currentTimeMs - absenceStartTimeMs) / 1000L
        return max(0L, absenceGracePeriodSeconds - elapsed).toInt()
    }

    fun getSustainedPresenceProgress(currentTimeMs: Long = System.currentTimeMillis()): Float = synchronized(lock) {
        if (sustainedPresenceStartTimeMs == 0L) return 0f
        val elapsed = currentTimeMs - sustainedPresenceStartTimeMs
        return (elapsed.toFloat() / relaxedSustainedPresenceDurationMs).coerceIn(0f, 1f)
    }
}
