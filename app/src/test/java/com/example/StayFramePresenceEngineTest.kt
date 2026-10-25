package com.example

import com.example.domain.engine.FaceObservation
import com.example.domain.engine.PresenceEngineCallback
import com.example.domain.engine.PresenceStateEngine
import com.example.domain.model.ActiveMediaSessionInfo
import com.example.domain.model.DetectionMode
import com.example.domain.model.DiagnosticEvent
import com.example.domain.model.PlayerRegistry
import com.example.domain.model.PresenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val loggedEvents = mutableListOf<DiagnosticEvent>()

    private val callback = object : PresenceEngineCallback {
        override fun onStateChanged(newState: PresenceState, previousState: PresenceState) {}

        override fun onSendPauseCommand(targetPackage: String, currentPositionMs: Long) {
            lastPausePackage = targetPackage
            lastPausePosition = currentPositionMs
        }

        override fun onSendRewindCommand(targetPackage: String, targetPositionMs: Long, rewindOffsetMs: Long) {
            lastRewindPackage = targetPackage
            lastRewindTargetMs = targetPositionMs
            lastRewindOffsetMs = rewindOffsetMs
        }

        override fun onSendPlayCommand(targetPackage: String) {
            lastPlayPackage = targetPackage
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
        loggedEvents.clear()

        engine = PresenceStateEngine(
            absenceGracePeriodSeconds = 5,
            detectionMode = DetectionMode.STRICT,
            callback = callback
        )
    }

    @Test
    fun testAbsenceGrace_TriggersPauseAndImmediateRewind() {
        // Arrange
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

        // Act 1: User becomes absent -> Enters ABSENCE_GRACE
        engine.processObservation(
            FaceObservation(faceDetected = false, identityMatchConfidence = 0f, isEnrolledMatch = false),
            currentTimeMs = t0
        )
        assertEquals(PresenceState.ABSENCE_GRACE, engine.currentState)

        // Act 2: Grace period running at 3 seconds -> Still ABSENCE_GRACE, no pause yet
        engine.processObservation(
            FaceObservation(faceDetected = false, identityMatchConfidence = 0f, isEnrolledMatch = false),
            currentTimeMs = t0 + 3000L
        )
        assertEquals(PresenceState.ABSENCE_GRACE, engine.currentState)
        assertEquals(null, lastPausePackage)

        // Act 3: Grace period expires at 5 seconds (5000ms) -> Triggers Pause and Rewind!
        engine.processObservation(
            FaceObservation(faceDetected = false, identityMatchConfidence = 0f, isEnrolledMatch = false),
            currentTimeMs = t0 + 5000L
        )
        assertEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)
        assertTrue(engine.pauseOwnership)
        assertEquals("com.google.android.youtube", lastPausePackage)
        assertEquals(30000L, lastPausePosition)

        // Verify immediate rewind sought back by 5s (5000ms): 30000 - 5000 = 25000
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
        // Trigger pause by guard
        engine.processObservation(FaceObservation(false, 0f, false), t0)
        engine.processObservation(FaceObservation(false, 0f, false), t0 + 5100L)
        assertEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)

        // User returns at t0 + 10000L
        val tReturn = t0 + 10000L
        engine.processObservation(
            FaceObservation(faceDetected = true, identityMatchConfidence = 0.88f, isEnrolledMatch = true),
            currentTimeMs = tReturn
        )
        // Under 1.0 second: should NOT resume yet
        assertEquals(null, lastPlayPackage)

        // At tReturn + 1050ms (>= 1.0s continuous): Resumes play!
        engine.processObservation(
            FaceObservation(faceDetected = true, identityMatchConfidence = 0.88f, isEnrolledMatch = true),
            currentTimeMs = tReturn + 1050L
        )
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
        // Transition to paused by guard
        engine.processObservation(FaceObservation(false, 0f, false), t0)
        engine.processObservation(FaceObservation(false, 0f, false), t0 + 5100L)
        assertEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)

        // Test passerby: someone walks by for 1.2 seconds, then leaves
        val tPasserby = t0 + 8000L
        engine.processObservation(FaceObservation(true, 0.35f, false), tPasserby)
        engine.processObservation(FaceObservation(true, 0.35f, false), tPasserby + 1200L)
        // Face gone
        engine.processObservation(FaceObservation(false, 0f, false), tPasserby + 1500L)

        // Must NOT resume play because passerby was < 2.0s!
        assertEquals(null, lastPlayPackage)
        assertEquals(PresenceState.PAUSED_BY_GUARD, engine.currentState)

        // Test sustained face: stays in front of screen for 2.2 seconds (> 2.0s)
        val tSustained = t0 + 12000L
        engine.processObservation(FaceObservation(true, 0.40f, false), tSustained)
        engine.processObservation(FaceObservation(true, 0.40f, false), tSustained + 1000L)
        // Still not 2s
        assertEquals(null, lastPlayPackage)

        // Now reaches 2200ms (> 2.0s) -> Play triggered!
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

        // User manually pauses video while watching
        val sessionPausedManually = sessionPlaying.copy(isPlaying = false)
        engine.updateActiveMediaSession(sessionPausedManually)

        assertTrue(engine.manualPauseDetected)
        assertFalse(engine.pauseOwnership)

        // User walks away and comes back
        val t0 = 80000L
        engine.processObservation(FaceObservation(false, 0f, false), t0)
        engine.processObservation(FaceObservation(false, 0f, false), t0 + 6000L)

        // User returns and looks at camera stably
        engine.processObservation(FaceObservation(true, 0.95f, true), t0 + 8000L)
        engine.processObservation(FaceObservation(true, 0.95f, true), t0 + 10000L)

        // Verify Play was NEVER called because manual pause was respected!
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

        // User switched active player to YouTube
        engine.updateActiveMediaSession(
            ActiveMediaSessionInfo(
                packageName = "com.google.android.youtube",
                isPlaying = false,
                playbackPositionMs = 5000L,
                isSupported = true
            )
        )
        // Prior pause ownership must be revoked
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
