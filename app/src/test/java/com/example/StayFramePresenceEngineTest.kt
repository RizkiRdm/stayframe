package com.example

import android.media.session.PlaybackState
import com.example.domain.engine.FaceObservation
import com.example.domain.engine.PresenceEngineCallback
import com.example.domain.engine.PresenceStateEngine
import com.example.domain.model.ActiveMediaSessionInfo
import com.example.domain.model.DetectionMode
import com.example.domain.model.DiagnosticEvent
import com.example.domain.model.MediaPositionEstimator
import com.example.domain.model.PlayerRegistry
import com.example.domain.model.PresenceState
import com.example.service.MonitoringForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StayFramePresenceEngineTest {

    private var lastPausePackage: String? = null
    private var lastPausePosition: Long? = null
    private var lastRewindPackage: String? = null
    private var lastRewindTargetMs: Long? = null
    private var lastRewindOffsetMs: Long? = null
    private var lastPlayPackage: String? = null
    private var pauseDispatchResult: Boolean = true
    private var rewindDispatchResult: Boolean = true
    private var playDispatchResult: Boolean = true
    private val loggedEvents = mutableListOf<DiagnosticEvent>()

    private val callback = object : PresenceEngineCallback {
        override fun onStateChanged(newState: PresenceState, previousState: PresenceState) {}

        override fun onSendPauseCommand(targetPackage: String, currentPositionMs: Long): Boolean {
            if (!pauseDispatchResult) return false
            lastPausePackage = targetPackage
            lastPausePosition = currentPositionMs
            return true
        }

        override fun onSendRewindCommand(targetPackage: String, targetPositionMs: Long, rewindOffsetMs: Long): Boolean {
            if (!rewindDispatchResult) return false
            lastRewindPackage = targetPackage
            lastRewindTargetMs = targetPositionMs
            lastRewindOffsetMs = rewindOffsetMs
            return true
        }

        override fun onSendPlayCommand(targetPackage: String): Boolean {
            if (!playDispatchResult) return false
            lastPlayPackage = targetPackage
            return true
        }

        override fun onDiagnosticLog(event: DiagnosticEvent) {
            loggedEvents.add(event)
        }
    }

    private lateinit var engine: PresenceStateEngine

    @Before
    fun setup() {
        lastPausePackage = null
        lastPausePosition = null
        lastRewindPackage = null
        lastRewindTargetMs = null
        lastRewindOffsetMs = null
        lastPlayPackage = null
        pauseDispatchResult = true
        rewindDispatchResult = true
        playDispatchResult = true
        loggedEvents.clear()

        engine = PresenceStateEngine(
            absenceGracePeriodSeconds = 5,
            detectionMode = DetectionMode.STRICT,
            callback = callback
        )
    }

    // =========================================================================
    // POINT 1 TEST: Position extrapolation using elapsedRealtime & playbackSpeed
    // =========================================================================
    @Test
    fun testPoint1_EstimatePlaybackPosition_StatePlaying_ExtrapolatesPosition() {
        val basePos = 10000L
        val lastUpdateTime = 50000L

        // 1. When STATE_PLAYING at 1.0x speed, 5 seconds (5000ms) elapsed:
        val estimatedPos1x = MediaPositionEstimator.estimatePosition(
            state = PlaybackState.STATE_PLAYING,
            basePosition = basePos,
            lastPositionUpdateTime = lastUpdateTime,
            playbackSpeed = 1.0f,
            currentElapsedRealtime = 55000L
        )
        // 10000 + (55000 - 50000) * 1.0 = 15000L
        assertEquals(15000L, estimatedPos1x)

        // 2. When STATE_PLAYING at 1.5x speed, 2 seconds (2000ms) elapsed:
        val estimatedPos15x = MediaPositionEstimator.estimatePosition(
            state = PlaybackState.STATE_PLAYING,
            basePosition = basePos,
            lastPositionUpdateTime = lastUpdateTime,
            playbackSpeed = 1.5f,
            currentElapsedRealtime = 52000L
        )
        // 10000 + (2000 * 1.5) = 13000L
        assertEquals(13000L, estimatedPos15x)

        // 3. When STATE_PAUSED, position does NOT advance with elapsedRealtime:
        val pausedPos = MediaPositionEstimator.estimatePosition(
            state = PlaybackState.STATE_PAUSED,
            basePosition = basePos,
            lastPositionUpdateTime = lastUpdateTime,
            playbackSpeed = 1.0f,
            currentElapsedRealtime = 55000L
        )
        assertEquals(10000L, pausedPos)
    }

    // =========================================================================
    // POINT 3 TEST: Strict Mode - Unrecognized face treated as absent (grace timer ticks)
    // =========================================================================
    @Test
    fun testPoint3_StrictMode_UnrecognizedFace_TreatedAsAbsent_GraceTimerTicks_PausesOnExpiry() {
        engine.setDetectionMode(DetectionMode.STRICT)
        engine.startMonitoring()
        engine.updateActiveMediaSession(
            ActiveMediaSessionInfo(
                packageName = "com.google.android.youtube",
                playerDisplayName = "YouTube",
                isPlaying = true,
                playbackPositionMs = 20000L,
                isSupported = true
            )
        )
        assertEquals(PresenceState.PRESENT, engine.currentState)

        val t0 = 100000L

        // Act 1: Non-matching face appears (faceDetected = true, isEnrolledMatch = false)
        engine.processObservation(
            FaceObservation(faceDetected = true, identityMatchConfidence = 0.30f, isEnrolledMatch = false),
            currentTimeMs = t0
        )
        // Must transition to OTHER_FACE and start absence countdown
        assertEquals(PresenceState.OTHER_FACE, engine.currentState)
        assertEquals(5, engine.getRemainingGraceSeconds(t0))

        // Act 2: Non-matching face still visible at t0 + 2000ms -> grace ticks down to 3s
        engine.processObservation(
            FaceObservation(faceDetected = true, identityMatchConfidence = 0.30f, isEnrolledMatch = false),
            currentTimeMs = t0 + 2000L
        )
        assertEquals(PresenceState.OTHER_FACE, engine.currentState)
        assertEquals(3, engine.getRemainingGraceSeconds(t0 + 2000L))
        assertEquals(null, lastPausePackage) // Not paused yet

        // Act 3: Grace period expires at t0 + 5000ms -> Triggers pause & rewind!
        engine.processObservation(
            FaceObservation(faceDetected = true, identityMatchConfidence = 0.30f, isEnrolledMatch = false),
            currentTimeMs = t0 + 5000L
        )
        assertEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)
        assertTrue(engine.pauseOwnership)
        assertEquals("com.google.android.youtube", lastPausePackage)
        assertEquals("com.google.android.youtube", lastRewindPackage)
        assertEquals(15000L, lastRewindTargetMs) // 20000 - 5000 = 15000L
    }

    // =========================================================================
    // POINT 5 TEST: triggerAutoPauseAndRewind when player is NOT playing
    // =========================================================================
    @Test
    fun testPoint5_TriggerAutoPauseAndRewind_WhenPlayerNotPlaying_DoesNotSetOwnershipOrEnterPausedByGuard() {
        engine.startMonitoring()
        // Player is supported, but NOT playing (isPlaying = false)
        engine.updateActiveMediaSession(
            ActiveMediaSessionInfo(
                packageName = "org.videolan.vlc",
                playerDisplayName = "VLC",
                isPlaying = false,
                playbackPositionMs = 8000L,
                isSupported = true
            )
        )

        val t0 = 100000L
        // User absent at t0 -> enters ABSENCE_GRACE
        engine.processObservation(
            FaceObservation(faceDetected = false, identityMatchConfidence = 0f, isEnrolledMatch = false),
            currentTimeMs = t0
        )

        // Grace period expires at t0 + 5000L
        engine.processObservation(
            FaceObservation(faceDetected = false, identityMatchConfidence = 0f, isEnrolledMatch = false),
            currentTimeMs = t0 + 5000L
        )

        // Point 5 assertion: Since player was NOT playing, ownership must NOT be set
        // and state must NOT enter PAUSED_BY_GUARD!
        assertFalse(engine.pauseOwnership)
        assertNotEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)
        assertEquals(null, lastPausePackage)
        assertEquals(null, lastRewindPackage)
    }

    // =========================================================================
    // SCENARIO 7 TEST: Obstructed / Covered Camera
    // =========================================================================
    @Test
    fun testScenario7_ObstructedCamera_EntersUnknown_PausesAndRewindsOnGraceExpiry_ZeroResume() {
        engine.setDetectionMode(DetectionMode.STRICT)
        engine.startMonitoring()
        engine.updateActiveMediaSession(
            ActiveMediaSessionInfo(
                packageName = "com.google.android.youtube",
                playerDisplayName = "YouTube",
                isPlaying = true,
                playbackPositionMs = 25000L,
                isSupported = true
            )
        )
        assertEquals(PresenceState.PRESENT, engine.currentState)

        val t0 = 100000L

        // Act 1: Camera is covered/obstructed -> enters UNKNOWN state
        engine.handleUnknownOrObstructed(t0, "Camera lens is covered or obstructed")
        assertEquals(PresenceState.UNKNOWN, engine.currentState)
        assertEquals(5, engine.getRemainingGraceSeconds(t0))

        // Act 2: Camera remains obstructed at t0 + 2500ms -> grace ticks down
        engine.handleUnknownOrObstructed(t0 + 2500L, "Camera lens is covered or obstructed")
        assertEquals(PresenceState.UNKNOWN, engine.currentState)
        assertEquals(null, lastPausePackage) // Not paused yet

        // Act 3: Grace period expires at t0 + 5000ms -> triggers auto-pause and rewind
        engine.handleUnknownOrObstructed(t0 + 5000L, "Camera lens is covered or obstructed")
        assertEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)
        assertTrue(engine.pauseOwnership)
        assertEquals("com.google.android.youtube", lastPausePackage)
        assertEquals(25000L, lastPausePosition)
        assertEquals("com.google.android.youtube", lastRewindPackage)
        assertEquals(20000L, lastRewindTargetMs)

        // Act 4: While still obstructed or blank, zero resume commands are sent
        engine.processObservation(FaceObservation(faceDetected = false, identityMatchConfidence = 0f, isEnrolledMatch = false), t0 + 8000L)
        assertEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)
        assertEquals(null, lastPlayPackage)
    }

    // =========================================================================
    // ADAPTIVE INTERVAL SYNC & UNSEEKABLE STREAM TESTS
    // =========================================================================
    @Test
    fun testAdaptiveSync_AppliesPlayerEffectiveIntervalAndUpdatesMidSession() {
        engine.setAbsenceGracePeriod(10) // App global = 10s
        engine.startMonitoring()

        // Active player specifies 5s synced interval -> overrides app's 10s
        engine.updateActiveMediaSession(
            ActiveMediaSessionInfo(
                packageName = "com.google.android.youtube",
                playerDisplayName = "YouTube",
                isPlaying = true,
                playbackPositionMs = 40000L,
                isSupported = true,
                canSeek = true,
                effectiveIntervalSeconds = 5,
                syncSource = com.example.domain.model.SyncSource.PLAYER_USER_SYNCED
            )
        )
        assertEquals(5, engine.effectiveGracePeriodSeconds)

        val t0 = 100000L
        engine.processObservation(FaceObservation(false, 0f, false), t0)
        assertEquals(PresenceState.ABSENCE_GRACE, engine.currentState)
        engine.processObservation(FaceObservation(false, 0f, false), t0 + 5000L)

        // Paused at 5s and rewound -5000ms (to 35000L) despite global 10s setting
        assertEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)
        assertEquals(35000L, lastRewindTargetMs)
        assertEquals(5000L, lastRewindOffsetMs)
    }

    @Test
    fun testUnseekableStream_PausesWithoutRewindCommand() {
        engine.startMonitoring()
        engine.updateActiveMediaSession(
            ActiveMediaSessionInfo(
                packageName = "com.google.android.youtube",
                playerDisplayName = "YouTube Live",
                isPlaying = true,
                playbackPositionMs = 15000L,
                isSupported = true,
                canSeek = false
            )
        )

        val t0 = 100000L
        engine.processObservation(FaceObservation(false, 0f, false), t0)
        engine.processObservation(FaceObservation(false, 0f, false), t0 + 5100L)

        assertEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)
        assertEquals("com.google.android.youtube", lastPausePackage)
        assertEquals(null, lastRewindPackage)
    }

    @Test
    fun testPersistentMonitoring_AppSwitchAndIntentionalPausePreservedOnReturn() {
        engine.startMonitoring()
        val playing = ActiveMediaSessionInfo(
            packageName = "org.videolan.vlc",
            playerDisplayName = "VLC",
            isPlaying = true,
            playbackPositionMs = 20000L,
            isSupported = true
        )
        engine.updateActiveMediaSession(playing)
        assertEquals(PresenceState.PRESENT, engine.currentState)

        // User intentionally pauses VLC, then switches to another app (no active session)
        engine.updateActiveMediaSession(playing.copy(isPlaying = false))
        assertTrue(engine.manualPauseDetected)

        engine.updateActiveMediaSession(ActiveMediaSessionInfo(packageName = null, isSupported = false))
        assertEquals(PresenceState.PLAYER_IN_BACKGROUND, engine.currentState)

        // User returns to VLC while still paused
        engine.updateActiveMediaSession(playing.copy(isPlaying = false))
        assertEquals(PresenceState.PLAYER_DETECTED, engine.currentState)
        assertEquals(null, lastPlayPackage)
    }

    @Test
    fun testRecoveryState_TransitionsCleanlyOnReconnectedSession() {
        engine.beginRecovery()
        assertEquals(PresenceState.RECOVERING, engine.currentState)

        engine.updateActiveMediaSession(
            ActiveMediaSessionInfo(
                packageName = "com.google.android.youtube",
                playerDisplayName = "YouTube",
                isPlaying = true,
                playbackPositionMs = 12000L,
                isSupported = true
            )
        )
        assertEquals(PresenceState.PRESENT, engine.currentState)
    }

    // =========================================================================
    // T1 CAMERA RESILIENCE TESTS (ERROR -> RECOVER, WATCHDOG, BACKOFF)
    // =========================================================================
    @Test
    fun testCameraResilience_ErrorToRecover_ExplicitHookRestoresPresent() {
        engine.startMonitoring()
        engine.updateActiveMediaSession(
            ActiveMediaSessionInfo(
                packageName = "com.google.android.youtube",
                playerDisplayName = "YouTube",
                isPlaying = true,
                playbackPositionMs = 24000L,
                isSupported = true
            )
        )
        assertEquals(PresenceState.PRESENT, engine.currentState)

        engine.reportError("Camera unavailable (attempt 3): CameraState CLOSED error code=1")
        assertEquals(PresenceState.ERROR, engine.currentState)
        assertTrue(engine.lastErrorMessage?.contains("Camera unavailable") == true)

        engine.recoverFromError("CameraX hardware state OPEN")
        assertEquals(PresenceState.PRESENT, engine.currentState)
        assertNull(engine.lastErrorMessage)
        assertTrue(loggedEvents.any { it.eventName == "camera_recovered" })
    }

    @Test
    fun testCameraResilience_ErrorToRecover_PreservesIntentionalManualPause() {
        engine.startMonitoring()
        engine.updateActiveMediaSession(
            ActiveMediaSessionInfo(
                packageName = "org.videolan.vlc",
                playerDisplayName = "VLC",
                isPlaying = false,
                playbackPositionMs = 15000L,
                isSupported = true
            )
        )
        assertEquals(PresenceState.PLAYER_DETECTED, engine.currentState)
        assertTrue(engine.manualPauseDetected)

        engine.reportError("Camera unavailable (attempt 3)")
        assertEquals(PresenceState.ERROR, engine.currentState)

        engine.recoverFromError("CameraX hardware state OPEN")
        assertEquals(PresenceState.PLAYER_DETECTED, engine.currentState)
        assertTrue(engine.manualPauseDetected)
        assertNull(engine.lastErrorMessage)
        assertNull(lastPlayPackage)
    }

    @Test
    fun testCameraResilience_ErrorToRecover_IncomingObservationExitsError() {
        engine.startMonitoring()
        engine.updateActiveMediaSession(
            ActiveMediaSessionInfo(
                packageName = "com.google.android.youtube",
                playerDisplayName = "YouTube",
                isPlaying = true,
                playbackPositionMs = 18000L,
                isSupported = true
            )
        )

        engine.reportError("Transient preemption")
        assertEquals(PresenceState.ERROR, engine.currentState)

        engine.processObservation(
            FaceObservation(faceDetected = true, identityMatchConfidence = 0.90f, isEnrolledMatch = true),
            currentTimeMs = 200000L
        )
        assertEquals(PresenceState.PRESENT, engine.currentState)
        assertNull(engine.lastErrorMessage)
        assertTrue(loggedEvents.any { it.eventName == "camera_recovered" })
    }

    @Test
    fun testCameraResilience_WatchdogTimeout_EntersUnknownInRelaxedModeAndPausesOnExpiry() {
        engine.setDetectionMode(DetectionMode.RELAXED_FALLBACK)
        engine.startMonitoring()
        engine.updateActiveMediaSession(
            ActiveMediaSessionInfo(
                packageName = "com.google.android.youtube",
                playerDisplayName = "YouTube",
                isPlaying = true,
                playbackPositionMs = 30000L,
                isSupported = true
            )
        )
        assertEquals(PresenceState.PRESENT, engine.currentState)

        val t0 = 100000L
        engine.handleCameraWatchdogTimeout(t0, "No camera frames received for >5s")
        assertEquals(PresenceState.UNKNOWN, engine.currentState)
        assertTrue(loggedEvents.any { it.eventName == "camera_watchdog_timeout" })

        engine.handleCameraWatchdogTimeout(t0 + 5000L, "Camera still stalled")
        assertEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)
        assertEquals("com.google.android.youtube", lastPausePackage)
        assertEquals(25000L, lastRewindTargetMs)
    }

    @Test
    fun testCameraResilience_BackoffExponentialAndCappedAt30SecondsWithNoRetryLimit() {
        assertEquals(1_000L, MonitoringForegroundService.computeRebindBackoffMs(1))
        assertEquals(2_000L, MonitoringForegroundService.computeRebindBackoffMs(2))
        assertEquals(4_000L, MonitoringForegroundService.computeRebindBackoffMs(3))
        assertEquals(8_000L, MonitoringForegroundService.computeRebindBackoffMs(4))
        assertEquals(16_000L, MonitoringForegroundService.computeRebindBackoffMs(5))
        assertEquals(30_000L, MonitoringForegroundService.computeRebindBackoffMs(6))
        assertEquals(30_000L, MonitoringForegroundService.computeRebindBackoffMs(25))
        assertEquals(30_000L, MonitoringForegroundService.computeRebindBackoffMs(500))
    }

    // =========================================================================
    // T2 COMMAND DISPATCH SAFETY TESTS
    // =========================================================================
    @Test
    fun testT2_PauseCommandFailure_WithholdsPausedByGuardAndOwnership() {
        pauseDispatchResult = false
        engine.startMonitoring()
        engine.updateActiveMediaSession(
            ActiveMediaSessionInfo(
                packageName = "com.google.android.youtube",
                playerDisplayName = "YouTube",
                isPlaying = true,
                playbackPositionMs = 30000L,
                isSupported = true
            )
        )

        val t0 = 100000L
        engine.processObservation(FaceObservation(false, 0f, false), t0)
        engine.processObservation(FaceObservation(false, 0f, false), t0 + 5000L)

        assertNotEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)
        assertFalse(engine.pauseOwnership)
        assertNull(lastPausePackage)
        assertNull(lastRewindPackage)
        assertTrue(loggedEvents.any { it.eventName == "pause_dispatch_failed" })
    }

    @Test
    fun testT2_PlayCommandFailure_KeepsPausedByGuardAndOwnership() {
        engine.startMonitoring()
        engine.updateActiveMediaSession(
            ActiveMediaSessionInfo(
                packageName = "org.videolan.vlc",
                playerDisplayName = "VLC",
                isPlaying = true,
                playbackPositionMs = 20000L,
                isSupported = true
            )
        )

        val t0 = 100000L
        engine.processObservation(FaceObservation(false, 0f, false), t0)
        engine.processObservation(FaceObservation(false, 0f, false), t0 + 5100L)
        assertEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)
        assertTrue(engine.pauseOwnership)

        playDispatchResult = false
        val tReturn = t0 + 10000L
        engine.processObservation(FaceObservation(true, 0.90f, true), tReturn)
        engine.processObservation(FaceObservation(true, 0.90f, true), tReturn + 1100L)

        assertEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)
        assertTrue(engine.pauseOwnership)
        assertNull(lastPlayPackage)
        assertTrue(loggedEvents.any { it.eventName == "play_dispatch_failed" })

        playDispatchResult = true
        engine.processObservation(FaceObservation(true, 0.90f, true), tReturn + 2000L)
        assertEquals(PresenceState.PRESENT, engine.currentState)
        assertFalse(engine.pauseOwnership)
        assertEquals("org.videolan.vlc", lastPlayPackage)
    }

    // =========================================================================
    // T3 FAIL-CLOSED IDENTITY & LOW-LIGHT TESTS
    // =========================================================================
    @Test
    fun testT3_StrictMode_UncertainIdentityOrMissingTemplate_EntersUnknownNotOtherFace() {
        engine.setDetectionMode(DetectionMode.STRICT)
        engine.startMonitoring()
        engine.updateActiveMediaSession(
            ActiveMediaSessionInfo(
                packageName = "com.google.android.youtube",
                playerDisplayName = "YouTube",
                isPlaying = true,
                playbackPositionMs = 25000L,
                isSupported = true
            )
        )
        assertEquals(PresenceState.PRESENT, engine.currentState)

        val t0 = 100000L
        engine.processObservation(
            FaceObservation(
                faceDetected = true,
                identityMatchConfidence = 0f,
                isEnrolledMatch = false,
                lightingAdequate = true,
                identityUncertain = true
            ),
            currentTimeMs = t0
        )

        assertEquals(PresenceState.UNKNOWN, engine.currentState)
        assertNotEquals(PresenceState.OTHER_FACE, engine.currentState)

        engine.processObservation(
            FaceObservation(
                faceDetected = true,
                identityMatchConfidence = 0f,
                isEnrolledMatch = false,
                lightingAdequate = true,
                identityUncertain = true
            ),
            currentTimeMs = t0 + 5000L
        )
        assertEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)
        assertEquals("com.google.android.youtube", lastPausePackage)
    }

    @Test
    fun testT3_LowLighting_TransitionsToUnknownEvenInRelaxedMode() {
        engine.setDetectionMode(DetectionMode.RELAXED_FALLBACK)
        engine.startMonitoring()
        engine.updateActiveMediaSession(
            ActiveMediaSessionInfo(
                packageName = "com.google.android.youtube",
                playerDisplayName = "YouTube",
                isPlaying = true,
                playbackPositionMs = 18000L,
                isSupported = true
            )
        )
        assertEquals(PresenceState.PRESENT, engine.currentState)

        val t0 = 100000L
        engine.processObservation(
            FaceObservation(
                faceDetected = false,
                identityMatchConfidence = 0f,
                isEnrolledMatch = false,
                lightingAdequate = false,
                identityUncertain = true
            ),
            currentTimeMs = t0
        )
        assertEquals(PresenceState.UNKNOWN, engine.currentState)

        engine.processObservation(
            FaceObservation(
                faceDetected = false,
                identityMatchConfidence = 0f,
                isEnrolledMatch = false,
                lightingAdequate = false,
                identityUncertain = true
            ),
            currentTimeMs = t0 + 5000L
        )
        assertEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)
        assertEquals("com.google.android.youtube", lastPausePackage)
        assertEquals(13000L, lastRewindTargetMs)
    }

    // =========================================================================
    // T4 BUFFERING & NON-MANUAL PAUSE TESTS
    // =========================================================================
    @Test
    fun testT4_Buffering_NotTreatedAsManualPause_AllowsLaterAutoResume() {
        engine.startMonitoring()
        val playing = ActiveMediaSessionInfo(
            packageName = "com.google.android.youtube",
            playerDisplayName = "YouTube",
            isPlaying = true,
            isBuffering = false,
            playbackPositionMs = 20000L,
            isSupported = true
        )
        engine.updateActiveMediaSession(playing)
        assertEquals(PresenceState.PRESENT, engine.currentState)

        // Transition into STATE_BUFFERING / STATE_CONNECTING
        engine.updateActiveMediaSession(playing.copy(isPlaying = false, isBuffering = true))
        assertFalse(engine.manualPauseDetected)
        assertEquals(PresenceState.PRESENT, engine.currentState)

        // Resume active playback after buffering
        engine.updateActiveMediaSession(playing.copy(isPlaying = true, isBuffering = false))
        assertFalse(engine.manualPauseDetected)
        assertEquals(PresenceState.PRESENT, engine.currentState)

        // Now user leaves -> guard pauses -> user returns -> auto-resume must succeed
        val t0 = 100000L
        engine.processObservation(FaceObservation(false, 0f, false), t0)
        engine.processObservation(FaceObservation(false, 0f, false), t0 + 5000L)
        assertEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)

        engine.processObservation(FaceObservation(true, 0.90f, true), t0 + 8000L)
        engine.processObservation(FaceObservation(true, 0.90f, true), t0 + 9100L)
        assertEquals(PresenceState.PRESENT, engine.currentState)
        assertEquals("com.google.android.youtube", lastPlayPackage)
    }

    @Test
    fun testT4_BufferingDuringAbsenceGrace_DoesNotResetTimers() {
        engine.startMonitoring()
        val playing = ActiveMediaSessionInfo(
            packageName = "com.google.android.youtube",
            playerDisplayName = "YouTube",
            isPlaying = true,
            isBuffering = false,
            playbackPositionMs = 40000L,
            isSupported = true
        )
        engine.updateActiveMediaSession(playing)

        val t0 = 100000L
        engine.processObservation(FaceObservation(false, 0f, false), t0)
        assertEquals(PresenceState.ABSENCE_GRACE, engine.currentState)
        assertEquals(5, engine.getRemainingGraceSeconds(t0))

        // Mid-grace buffering occurs at t0 + 2500ms -> must NOT reset absence timer or flip to PLAYER_DETECTED
        engine.updateActiveMediaSession(playing.copy(isPlaying = false, isBuffering = true))
        assertEquals(PresenceState.ABSENCE_GRACE, engine.currentState)
        assertFalse(engine.manualPauseDetected)
        assertEquals(2, engine.getRemainingGraceSeconds(t0 + 3000L))

        // Buffering finishes back to PLAYING at t0 + 3500ms -> timer STILL preserved
        engine.updateActiveMediaSession(playing.copy(isPlaying = true, isBuffering = false))
        assertEquals(PresenceState.ABSENCE_GRACE, engine.currentState)
        assertEquals(1, engine.getRemainingGraceSeconds(t0 + 4000L))

        // At t0 + 5000ms original grace expires -> auto-pauses cleanly
        engine.processObservation(FaceObservation(false, 0f, false), t0 + 5000L)
        assertEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)
        assertEquals("com.google.android.youtube", lastPausePackage)
    }

    // =========================================================================
    // LIVE POSITION EXTRAPOLATION TESTS
    // =========================================================================
    @Test
    fun testLivePositionExtrapolation_PlaybackSpeedVariations() {
        val basePos = 30000L
        val updateTime = 10000L

        // 0.5x speed after 4000ms -> +2000ms
        val posHalfSpeed = MediaPositionEstimator.estimatePosition(
            state = PlaybackState.STATE_PLAYING,
            basePosition = basePos,
            lastPositionUpdateTime = updateTime,
            playbackSpeed = 0.5f,
            currentElapsedRealtime = 14000L
        )
        assertEquals(32000L, posHalfSpeed)

        // 2.0x speed after 3000ms -> +6000ms
        val posDoubleSpeed = MediaPositionEstimator.estimatePosition(
            state = PlaybackState.STATE_PLAYING,
            basePosition = basePos,
            lastPositionUpdateTime = updateTime,
            playbackSpeed = 2.0f,
            currentElapsedRealtime = 13000L
        )
        assertEquals(36000L, posDoubleSpeed)

        // Non-positive or zero playback speed defaults safely to 1.0x
        val posDefaultSpeed = MediaPositionEstimator.estimatePosition(
            state = PlaybackState.STATE_PLAYING,
            basePosition = basePos,
            lastPositionUpdateTime = updateTime,
            playbackSpeed = 0.0f,
            currentElapsedRealtime = 15000L
        )
        assertEquals(35000L, posDefaultSpeed)
    }

    @Test
    fun testLivePositionExtrapolation_NonPlayingStates_DoNotAdvance() {
        val basePos = 40000L
        val updateTime = 10000L

        // STATE_BUFFERING -> keeps base position
        val bufferingPos = MediaPositionEstimator.estimatePosition(
            state = PlaybackState.STATE_BUFFERING,
            basePosition = basePos,
            lastPositionUpdateTime = updateTime,
            playbackSpeed = 1.0f,
            currentElapsedRealtime = 20000L
        )
        assertEquals(basePos, bufferingPos)

        // STATE_STOPPED -> keeps base position
        val stoppedPos = MediaPositionEstimator.estimatePosition(
            state = PlaybackState.STATE_STOPPED,
            basePosition = basePos,
            lastPositionUpdateTime = updateTime,
            playbackSpeed = 1.0f,
            currentElapsedRealtime = 20000L
        )
        assertEquals(basePos, stoppedPos)

        // Null state or zero update time -> returns base position coerced >= 0
        val nullStatePos = MediaPositionEstimator.estimatePosition(
            state = null,
            basePosition = basePos,
            lastPositionUpdateTime = 0L,
            playbackSpeed = 1.0f,
            currentElapsedRealtime = 20000L
        )
        assertEquals(basePos, nullStatePos)
    }

    // =========================================================================
    // MULTI-THREAD CONCURRENCY / RACE CONDITION STRESS TEST
    // =========================================================================
    @Test
    fun testEngineConcurrency_MultiThreadedStressTest_NoCrashesOrDeadlocks() {
        val threadCount = 8
        val iterationsPerThread = 150
        val threads = mutableListOf<Thread>()
        val exceptions = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

        engine.startMonitoring()

        for (i in 0 until threadCount) {
            val thread = Thread {
                try {
                    for (j in 0 until iterationsPerThread) {
                        val fakeTime = 100000L + (j * 100L)

                        when (j % 5) {
                            0 -> {
                                engine.updateActiveMediaSession(
                                    ActiveMediaSessionInfo(
                                        packageName = if (j % 2 == 0) "com.google.android.youtube" else "org.videolan.vlc",
                                        isPlaying = j % 3 != 0,
                                        playbackPositionMs = (j * 1000L),
                                        isSupported = true
                                    )
                                )
                            }
                            1 -> {
                                engine.processObservation(
                                    FaceObservation(
                                        faceDetected = j % 2 == 0,
                                        identityMatchConfidence = if (j % 4 == 0) 0.85f else 0.40f,
                                        isEnrolledMatch = j % 4 == 0
                                    ),
                                    currentTimeMs = fakeTime
                                )
                            }
                            2 -> {
                                engine.setAbsenceGracePeriod(if (j % 2 == 0) 3 else 5)
                                engine.setDetectionMode(if (j % 2 == 0) DetectionMode.STRICT else DetectionMode.RELAXED_FALLBACK)
                            }
                            3 -> {
                                engine.getRemainingGraceSeconds(fakeTime)
                                engine.getSustainedPresenceProgress(fakeTime)
                                val state = engine.currentState
                                assertFalse(state.title.isEmpty())
                            }
                            4 -> {
                                if (j % 20 == 0) {
                                    engine.stopMonitoring()
                                    engine.startMonitoring()
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    exceptions.add(t)
                }
            }
            threads.add(thread)
        }

        // Launch all threads simultaneously
        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }

        // Assert zero exceptions or deadlocks occurred
        assertTrue("Expected 0 exceptions during concurrent stress test, found: ${exceptions.size}", exceptions.isEmpty())
        assertTrue("Engine must remain in a valid state", engine.currentState in PresenceState.values())
    }

    // =========================================================================
    // EXISTING SCENARIO TESTS
    // =========================================================================
    @Test
    fun testAbsenceGrace_TriggersPauseAndImmediateRewind() {
        engine.startMonitoring()
        engine.updateActiveMediaSession(
            ActiveMediaSessionInfo(
                packageName = "com.google.android.youtube",
                playerDisplayName = "YouTube",
                isPlaying = true,
                playbackPositionMs = 30000L,
                isSupported = true
            )
        )
        assertEquals(PresenceState.PRESENT, engine.currentState)

        val t0 = 100000L
        engine.processObservation(FaceObservation(false, 0f, false), t0)
        assertEquals(PresenceState.ABSENCE_GRACE, engine.currentState)

        engine.processObservation(FaceObservation(false, 0f, false), t0 + 3000L)
        assertEquals(PresenceState.ABSENCE_GRACE, engine.currentState)
        assertEquals(null, lastPausePackage)

        engine.processObservation(FaceObservation(false, 0f, false), t0 + 5000L)
        assertEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)
        assertTrue(engine.pauseOwnership)
        assertEquals("com.google.android.youtube", lastPausePackage)
        assertEquals(30000L, lastPausePosition)
        assertEquals("com.google.android.youtube", lastRewindPackage)
        assertEquals(25000L, lastRewindTargetMs)
        assertEquals(5000L, lastRewindOffsetMs)
    }

    @Test
    fun testStrictResume_RequiresOneSecondContinuousMatch() {
        engine.startMonitoring()
        engine.updateActiveMediaSession(
            ActiveMediaSessionInfo(
                packageName = "org.videolan.vlc",
                playerDisplayName = "VLC",
                isPlaying = true,
                playbackPositionMs = 20000L,
                isSupported = true
            )
        )

        val t0 = 100000L
        engine.processObservation(FaceObservation(false, 0f, false), t0)
        engine.processObservation(FaceObservation(false, 0f, false), t0 + 5100L)
        assertEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)

        val tReturn = t0 + 10000L
        engine.processObservation(FaceObservation(true, 0.88f, true), tReturn)
        assertEquals(null, lastPlayPackage)

        engine.processObservation(FaceObservation(true, 0.88f, true), tReturn + 1050L)
        assertEquals(PresenceState.PRESENT, engine.currentState)
        assertEquals("org.videolan.vlc", lastPlayPackage)
    }

    @Test
    fun testRelaxedMode_PasserbyUnderTwoSecondsIgnored_SustainedOverTwoSecondsResumes() {
        engine.setDetectionMode(DetectionMode.RELAXED_FALLBACK)
        engine.startMonitoring()
        engine.updateActiveMediaSession(
            ActiveMediaSessionInfo(
                packageName = "com.google.android.youtube",
                isPlaying = true,
                playbackPositionMs = 15000L,
                isSupported = true
            )
        )

        val t0 = 50000L
        engine.processObservation(FaceObservation(false, 0f, false), t0)
        engine.processObservation(FaceObservation(false, 0f, false), t0 + 5100L)
        assertEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)

        val tPasserby = t0 + 8000L
        engine.processObservation(FaceObservation(true, 0.35f, false), tPasserby)
        engine.processObservation(FaceObservation(true, 0.35f, false), tPasserby + 1200L)
        engine.processObservation(FaceObservation(false, 0f, false), tPasserby + 1500L)

        assertEquals(null, lastPlayPackage)
        assertEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)

        val tSustained = t0 + 12000L
        engine.processObservation(FaceObservation(true, 0.40f, false), tSustained)
        engine.processObservation(FaceObservation(true, 0.40f, false), tSustained + 1000L)
        assertEquals(null, lastPlayPackage)

        engine.processObservation(FaceObservation(true, 0.40f, false), tSustained + 2200L)
        assertEquals(PresenceState.PRESENT, engine.currentState)
        assertEquals("com.google.android.youtube", lastPlayPackage)
    }

    @Test
    fun testManualPauseImmunity_UserPauseNeverAutoResumed() {
        engine.startMonitoring()
        val sessionPlaying = ActiveMediaSessionInfo(
            packageName = "org.videolan.vlc",
            isPlaying = true,
            playbackPositionMs = 12000L,
            isSupported = true
        )
        engine.updateActiveMediaSession(sessionPlaying)
        assertEquals(PresenceState.PRESENT, engine.currentState)

        val sessionPausedManually = sessionPlaying.copy(isPlaying = false)
        engine.updateActiveMediaSession(sessionPausedManually)

        assertTrue(engine.manualPauseDetected)
        assertFalse(engine.pauseOwnership)

        val t0 = 80000L
        engine.processObservation(FaceObservation(false, 0f, false), t0)
        engine.processObservation(FaceObservation(false, 0f, false), t0 + 6000L)

        engine.processObservation(FaceObservation(true, 0.95f, true), t0 + 8000L)
        engine.processObservation(FaceObservation(true, 0.95f, true), t0 + 10000L)

        assertEquals(null, lastPlayPackage)
    }

    @Test
    fun testSessionChange_InvalidatesOldPauseOwnership() {
        engine.startMonitoring()
        engine.updateActiveMediaSession(
            ActiveMediaSessionInfo(
                packageName = "org.videolan.vlc",
                isPlaying = true,
                playbackPositionMs = 20000L,
                isSupported = true
            )
        )
        val t0 = 10000L
        engine.processObservation(FaceObservation(false, 0f, false), t0)
        engine.processObservation(FaceObservation(false, 0f, false), t0 + 5100L)
        assertTrue(engine.pauseOwnership)

        engine.updateActiveMediaSession(
            ActiveMediaSessionInfo(
                packageName = "com.google.android.youtube",
                isPlaying = false,
                playbackPositionMs = 5000L,
                isSupported = true
            )
        )
        assertFalse(engine.pauseOwnership)
    }

    @Test
    fun testPlayerRegistry() {
        assertTrue(PlayerRegistry.isSupported("com.google.android.youtube"))
        assertTrue(PlayerRegistry.isSupported("org.videolan.vlc"))
        assertTrue(PlayerRegistry.isSupported("com.android.chrome"))
        assertTrue(PlayerRegistry.isSupported("com.mxtech.videoplayer.ad"))
        assertFalse(PlayerRegistry.isSupported("com.unsupported.randomplayer"))
        assertFalse(PlayerRegistry.isSupported(null))
    }
}
