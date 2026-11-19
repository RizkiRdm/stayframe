package com.example.domain.engine

import com.example.domain.model.AdaptiveIntervalResolver
import com.example.domain.model.ActiveMediaSessionInfo
import com.example.domain.model.DetectionMode
import com.example.domain.model.DiagnosticEvent
import com.example.domain.model.PresenceState
import com.example.domain.model.SyncSource
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
 * Core Presence & Monitoring Lifecycle State Machine enforcing:
 * - Adaptive absence-period & seek-interval synchronization
 * - Persistent monitoring across app switches (PLAYER_IN_BACKGROUND) and OS recovery (RECOVERING)
 * - Strict intentional-pause immunity (never auto-plays if paused manually or opened paused)
 * - Thread-safe synchronized state mutations
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

    var activeSession: ActiveMediaSessionInfo = ActiveMediaSessionInfo()
        get() = synchronized(lock) { field }
        private set

    var effectiveGracePeriodSeconds: Int = absenceGracePeriodSeconds
        get() = synchronized(lock) { field }
        private set

    var activeSyncSource: SyncSource = SyncSource.APP_GLOBAL_FALLBACK
        get() = synchronized(lock) { field }
        private set

    /** Ownership tracking: Did StayFrame initiate the pause on this exact session? */
    var pauseOwnership: Boolean = false
        get() = synchronized(lock) { field }
        private set

    private var ownedPackageName: String? = null
    private var lastObservedPlayerPackage: String? = null

    /** Manual pause guard: true if player was paused by the user */
    var manualPauseDetected: Boolean = false
        get() = synchronized(lock) { field }
        private set

    var lastErrorMessage: String? = null
        get() = synchronized(lock) { field }
        private set

    private var absenceStartTimeMs: Long = 0L
    private var stablePresentStartTimeMs: Long = 0L
    private var sustainedPresenceStartTimeMs: Long = 0L

    var matchThreshold: Float = 0.72f

    private val strictStableResumeDurationMs = 1000L
    private val relaxedSustainedPresenceDurationMs = 2000L

    fun setAbsenceGracePeriod(seconds: Int) = synchronized(lock) {
        absenceGracePeriodSeconds = seconds.coerceIn(
            AdaptiveIntervalResolver.MIN_INTERVAL_SECONDS,
            AdaptiveIntervalResolver.MAX_INTERVAL_SECONDS
        )
        recomputeEffectiveTimingLocked()
    }

    fun getAbsenceGracePeriodSeconds(): Int = synchronized(lock) { absenceGracePeriodSeconds }

    fun setDetectionMode(mode: DetectionMode) = synchronized(lock) {
        detectionMode = mode
    }

    fun getDetectionMode(): DetectionMode = synchronized(lock) { detectionMode }

    fun startMonitoring() = synchronized(lock) {
        if (currentState == PresenceState.INACTIVE ||
            currentState == PresenceState.ERROR ||
            currentState == PresenceState.RECOVERING
        ) {
            lastErrorMessage = null
            recomputeEffectiveTimingLocked()
            val nextState = resolveStateForSessionLocked(activeSession)
            transitionTo(nextState, "Monitoring started")
            logEvent("monitoring_started", "Session armed in ${detectionMode.name} mode (grace=${effectiveGracePeriodSeconds}s)")
        }
    }

    fun beginRecovery() = synchronized(lock) {
        if (currentState == PresenceState.INACTIVE || currentState == PresenceState.ERROR) {
            lastErrorMessage = null
            transitionTo(PresenceState.RECOVERING, "Recovering armed session after process interruption")
            logEvent("monitoring_recovering", "Restoring persisted guard state")
        }
    }

    fun stopMonitoring() = synchronized(lock) {
        if (currentState != PresenceState.INACTIVE) {
            pauseOwnership = false
            ownedPackageName = null
            manualPauseDetected = false
            lastObservedPlayerPackage = null
            resetTimers()
            transitionTo(PresenceState.INACTIVE, "Monitoring stopped by user")
            logEvent("monitoring_stopped", "Session cleanly terminated")
        }
    }

    fun markPlayerUnavailable(packageName: String) = synchronized(lock) {
        if (currentState == PresenceState.INACTIVE) return
        if (activeSession.packageName == packageName || ownedPackageName == packageName) {
            pauseOwnership = false
            ownedPackageName = null
            manualPauseDetected = false
            resetTimers()
            activeSession = ActiveMediaSessionInfo()
            recomputeEffectiveTimingLocked()
            transitionTo(PresenceState.PLAYER_UNAVAILABLE, "Player $packageName became unavailable")
            logEvent("player_unavailable", "Package $packageName uninstalled or disabled")
        }
    }

    fun updateActiveMediaSession(session: ActiveMediaSessionInfo) = synchronized(lock) {
        val previous = activeSession
        val previousEffectiveSec = effectiveGracePeriodSeconds
        activeSession = session
        recomputeEffectiveTimingLocked()

        if (session.isSupported && session.packageName != null) {
            lastObservedPlayerPackage = session.packageName
        }

        if (currentState == PresenceState.INACTIVE) return

        // Log mid-session adaptive interval updates cleanly without disrupting playback
        if (session.isSupported &&
            (effectiveGracePeriodSeconds != previousEffectiveSec || session.packageName != previous.packageName)
        ) {
            logEvent(
                "adaptive_interval_synced",
                "Effective interval ${effectiveGracePeriodSeconds}s (${activeSyncSource.label}) for ${session.playerDisplayName}"
            )
        }

        // Session package changed -> invalidate old ownership
        if (session.packageName != previous.packageName) {
            if (pauseOwnership && previous.packageName == ownedPackageName) {
                pauseOwnership = false
                ownedPackageName = null
                logEvent("session_changed", "Package changed to ${session.packageName}; prior pause ownership cleared")
            }
            // Reset manual pause flag if switching to a different player unless new session is already paused
            manualPauseDetected = session.isSupported && !session.isPlaying
        }

        // Detect external manual pause while actively playing
        if (!session.isPlaying && previous.isPlaying &&
            previous.packageName == session.packageName &&
            currentState != PresenceState.PAUSED_BY_GUARD
        ) {
            manualPauseDetected = true
            pauseOwnership = false
            ownedPackageName = null
            resetTimers()
            logEvent("manual_pause_detected", "User or external action paused playback. Auto-resume locked.")
        }

        // Detect external play / resume
        if (session.isPlaying && !previous.isPlaying) {
            manualPauseDetected = false
            pauseOwnership = false
            ownedPackageName = null
            resetTimers()
            if (currentState == PresenceState.PAUSED_BY_GUARD ||
                currentState == PresenceState.WAITING_FOR_PLAYER ||
                currentState == PresenceState.PLAYER_DETECTED ||
                currentState == PresenceState.PLAYER_IN_BACKGROUND ||
                currentState == PresenceState.PLAYER_UNAVAILABLE ||
                currentState == PresenceState.RECOVERING
            ) {
                transitionTo(PresenceState.PRESENT, "Playback active on ${session.playerDisplayName}")
                return
            }
        }

        // Handle returning to or leaving a supported player while keeping monitoring armed
        if (!session.isSupported || session.packageName == null) {
            if (currentState != PresenceState.PAUSED_BY_GUARD) {
                val targetState = if (lastObservedPlayerPackage != null) {
                    PresenceState.PLAYER_IN_BACKGROUND
                } else {
                    PresenceState.WAITING_FOR_PLAYER
                }
                if (currentState != targetState) {
                    resetTimers()
                    transitionTo(targetState, "No active supported player in foreground")
                }
            }
        } else if (session.isSupported && !session.isPlaying) {
            // Supported player detected but currently paused (do NOT auto-play)
            if (currentState != PresenceState.PAUSED_BY_GUARD) {
                manualPauseDetected = true
                resetTimers()
                transitionTo(PresenceState.PLAYER_DETECTED, "Player detected paused; intentional pause preserved")
            }
        } else if (session.isSupported && session.isPlaying) {
            if (currentState == PresenceState.WAITING_FOR_PLAYER ||
                currentState == PresenceState.PLAYER_DETECTED ||
                currentState == PresenceState.PLAYER_IN_BACKGROUND ||
                currentState == PresenceState.PLAYER_UNAVAILABLE ||
                currentState == PresenceState.RECOVERING
            ) {
                manualPauseDetected = false
                transitionTo(PresenceState.PRESENT, "Supported player playing: ${session.playerDisplayName}")
            }
        }
    }

    fun processObservation(observation: FaceObservation, currentTimeMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (currentState == PresenceState.INACTIVE ||
            currentState == PresenceState.ERROR ||
            currentState == PresenceState.RECOVERING
        ) return

        if (!activeSession.isSupported || activeSession.packageName == null) {
            if (currentState != PresenceState.PAUSED_BY_GUARD) {
                val target = if (lastObservedPlayerPackage != null) {
                    PresenceState.PLAYER_IN_BACKGROUND
                } else {
                    PresenceState.WAITING_FOR_PLAYER
                }
                if (currentState != target) {
                    transitionTo(target, "Waiting for active video player")
                }
                return
            }
        }

        // If player is open/detected but paused intentionally (not by guard), never countdown or auto-play
        if (currentState == PresenceState.PLAYER_DETECTED && !activeSession.isPlaying) {
            return
        }

        if (!observation.faceDetected) {
            handleFaceAbsent(currentTimeMs)
        } else {
            handleFacePresent(observation, currentTimeMs)
        }
    }

    private fun handleFaceAbsent(currentTimeMs: Long) {
        stablePresentStartTimeMs = 0L
        sustainedPresenceStartTimeMs = 0L

        when (currentState) {
            PresenceState.PRESENT, PresenceState.OTHER_FACE, PresenceState.UNKNOWN -> {
                absenceStartTimeMs = currentTimeMs
                transitionTo(PresenceState.ABSENCE_GRACE, "User not detected; started ${effectiveGracePeriodSeconds}s grace period")
                logEvent("absence_detected", "Starting ${effectiveGracePeriodSeconds}s grace countdown (${activeSyncSource.label})")
            }
            PresenceState.ABSENCE_GRACE -> {
                val elapsedMs = currentTimeMs - absenceStartTimeMs
                val graceLimitMs = effectiveGracePeriodSeconds * 1000L
                if (elapsedMs >= graceLimitMs) {
                    triggerAutoPauseAndRewind()
                }
            }
            else -> {
                // Remain in PAUSED_BY_GUARD, PLAYER_DETECTED, PLAYER_IN_BACKGROUND, etc.
            }
        }
    }

    private fun triggerAutoPauseAndRewind() {
        val targetPackage = activeSession.packageName

        if (targetPackage == null || !activeSession.isSupported || !activeSession.isPlaying) {
            pauseOwnership = false
            ownedPackageName = null
            logEvent("skip_auto_pause", "Player is not playing (isPlaying=${activeSession.isPlaying}); skipping pause and ownership")
            return
        }

        val currentPos = activeSession.playbackPositionMs
        val rewindOffsetMs = if (activeSession.canSeek) effectiveGracePeriodSeconds * 1000L else 0L
        val targetPositionMs = max(0L, currentPos - rewindOffsetMs)

        callback?.onSendPauseCommand(targetPackage, currentPos)

        if (rewindOffsetMs > 0L) {
            callback?.onSendRewindCommand(targetPackage, targetPositionMs, rewindOffsetMs)
        }

        pauseOwnership = true
        ownedPackageName = targetPackage
        manualPauseDetected = false

        val detailMsg = if (rewindOffsetMs > 0L) {
            "Paused $targetPackage and rewound -${effectiveGracePeriodSeconds}s (${activeSyncSource.label}) to $targetPositionMs ms"
        } else {
            "Paused $targetPackage (seek unsupported on live/ad stream)"
        }
        logEvent("auto_pause_and_rewind", detailMsg, rewindOffsetMs)

        transitionTo(PresenceState.PAUSED_BY_GUARD, "Absence grace expired; playback guarded")
    }

    private fun handleFacePresent(observation: FaceObservation, currentTimeMs: Long) {
        val isStrictMatch = observation.isEnrolledMatch || observation.identityMatchConfidence >= matchThreshold

        if (detectionMode == DetectionMode.STRICT) {
            if (isStrictMatch) {
                absenceStartTimeMs = 0L
                handleStrictUserPresent(currentTimeMs)
            } else {
                handleStrictUnrecognizedFace(currentTimeMs)
            }
        } else {
            absenceStartTimeMs = 0L
            handleRelaxedFacePresent(currentTimeMs)
        }
    }

    private fun handleStrictUnrecognizedFace(currentTimeMs: Long) {
        stablePresentStartTimeMs = 0L
        sustainedPresenceStartTimeMs = 0L

        when (currentState) {
            PresenceState.PRESENT -> {
                absenceStartTimeMs = currentTimeMs
                transitionTo(PresenceState.OTHER_FACE, "Detected face does not match enrolled viewer; grace started")
                logEvent("unrecognized_face_absent", "Non-matching face in strict mode; starting ${effectiveGracePeriodSeconds}s countdown")
            }
            PresenceState.OTHER_FACE, PresenceState.ABSENCE_GRACE -> {
                val elapsedMs = currentTimeMs - absenceStartTimeMs
                val graceLimitMs = effectiveGracePeriodSeconds * 1000L
                if (elapsedMs >= graceLimitMs) {
                    triggerAutoPauseAndRewind()
                }
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
                transitionTo(PresenceState.PLAYER_DETECTED, "User present (manual pause respected)")
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
                transitionTo(PresenceState.PLAYER_DETECTED, "Face present (manual pause respected)")
                return
            }

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
        if (pauseOwnership && !manualPauseDetected && targetPackage != null && targetPackage == ownedPackageName) {
            callback?.onSendPlayCommand(targetPackage)
            pauseOwnership = false
            ownedPackageName = null
            logEvent("auto_resume_sent", "Resuming playback on $targetPackage")
        }
        transitionTo(PresenceState.PRESENT, "Viewer returned; playback resumed")
        resetTimers()
    }

    fun handleUnknownOrObstructed(currentTimeMs: Long, reason: String) = synchronized(lock) {
        if (detectionMode == DetectionMode.RELAXED_FALLBACK) {
            return
        }
        stablePresentStartTimeMs = 0L
        sustainedPresenceStartTimeMs = 0L

        if (currentState == PresenceState.PRESENT) {
            absenceStartTimeMs = currentTimeMs
            transitionTo(PresenceState.UNKNOWN, reason)
        } else if (currentState == PresenceState.UNKNOWN || currentState == PresenceState.ABSENCE_GRACE) {
            val elapsedMs = currentTimeMs - absenceStartTimeMs
            if (elapsedMs >= (effectiveGracePeriodSeconds * 1000L)) {
                triggerAutoPauseAndRewind()
            }
        }
    }

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

    private fun recomputeEffectiveTimingLocked() {
        val sessionOverride = activeSession.effectiveIntervalSeconds
        if (activeSession.isSupported && sessionOverride != null) {
            effectiveGracePeriodSeconds = sessionOverride.coerceIn(
                AdaptiveIntervalResolver.MIN_INTERVAL_SECONDS,
                AdaptiveIntervalResolver.MAX_INTERVAL_SECONDS
            )
            activeSyncSource = activeSession.syncSource
        } else {
            effectiveGracePeriodSeconds = absenceGracePeriodSeconds
            activeSyncSource = SyncSource.APP_GLOBAL_FALLBACK
        }
    }

    private fun resolveStateForSessionLocked(session: ActiveMediaSessionInfo): PresenceState {
        return when {
            session.isSupported && session.isPlaying -> PresenceState.PRESENT
            session.isSupported && !session.isPlaying -> PresenceState.PLAYER_DETECTED
            lastObservedPlayerPackage != null -> PresenceState.PLAYER_IN_BACKGROUND
            else -> PresenceState.WAITING_FOR_PLAYER
        }
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
        ) return effectiveGracePeriodSeconds

        if (absenceStartTimeMs == 0L) return effectiveGracePeriodSeconds
        val elapsed = (currentTimeMs - absenceStartTimeMs) / 1000L
        return max(0L, effectiveGracePeriodSeconds - elapsed).toInt()
    }

    fun getSustainedPresenceProgress(currentTimeMs: Long = System.currentTimeMillis()): Float = synchronized(lock) {
        if (sustainedPresenceStartTimeMs == 0L) return 0f
        val elapsed = currentTimeMs - sustainedPresenceStartTimeMs
        return (elapsed.toFloat() / relaxedSustainedPresenceDurationMs).coerceIn(0f, 1f)
    }
}
