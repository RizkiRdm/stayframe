package com.example

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import com.example.data.FaceTemplateStore
import com.example.data.StayFramePreferencesRepository
import com.example.domain.biometrics.FaceDescriptorExtractor
import com.example.domain.engine.PresenceStateEngine
import com.example.domain.model.ActiveMediaSessionInfo
import com.example.domain.model.PresenceState
import com.example.service.MonitoringForegroundService
import com.example.service.StayFrameNotificationListenerService
import com.example.service.StayFrameServiceBridge
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StayFrameServiceResilienceTest {

    @Before
    fun setup() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        try {
            androidx.work.WorkManager.initialize(
                context,
                androidx.work.Configuration.Builder().build()
            )
        } catch (_: IllegalStateException) {
            // Already initialized in another test
        }
        try {
            com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(context)
        } catch (_: Exception) {
            // Already initialized
        }
        StayFrameServiceBridge.listenerServiceInstance = null
        StayFrameServiceBridge.setMonitoring(false)
        StayFrameServiceBridge.updateActiveSession(ActiveMediaSessionInfo())
    }

    @After
    fun tearDown() {
        StayFrameServiceBridge.listenerServiceInstance = null
        StayFrameServiceBridge.setMonitoring(false)
        StayFrameServiceBridge.updateActiveSession(ActiveMediaSessionInfo())
    }

    @Test
    fun testT2_BridgeNullListener_ReportsErrorAndReturnsFalseOnPauseDispatch() {
        StayFrameServiceBridge.setMonitoring(true)
        StayFrameServiceBridge.updateActiveSession(
            ActiveMediaSessionInfo(
                packageName = "com.google.android.youtube",
                playerDisplayName = "YouTube",
                isPlaying = true,
                playbackPositionMs = 25000L,
                isSupported = true
            )
        )
        assertFalse(StayFrameServiceBridge.listenerConnected.value)

        // When listener is null and absence grace expires, bridge pause dispatch fails and reports ERROR for UI
        val now = System.currentTimeMillis()
        StayFrameServiceBridge.engine.processObservation(
            com.example.domain.engine.FaceObservation(false, 0f, false),
            now
        )
        StayFrameServiceBridge.engine.processObservation(
            com.example.domain.engine.FaceObservation(false, 0f, false),
            now + 6000L
        )
        assertEquals(PresenceState.ERROR, StayFrameServiceBridge.presenceState.value)
        assertFalse(StayFrameServiceBridge.engine.pauseOwnership)
        assertTrue(StayFrameServiceBridge.lastErrorMessage.value?.contains("Notification Listener is disconnected") == true)

        val controller = Robolectric.buildService(StayFrameNotificationListenerService::class.java).create()
        val service = controller.get()
        service.onListenerConnected()
        assertTrue(StayFrameServiceBridge.listenerConnected.value)
        assertEquals(PresenceState.PRESENT, StayFrameServiceBridge.presenceState.value)
        assertNull(StayFrameServiceBridge.lastErrorMessage.value)

        // Simulate OS unbinding listener
        service.onListenerDisconnected()
        assertNull(StayFrameServiceBridge.listenerServiceInstance)
        assertFalse(StayFrameServiceBridge.listenerConnected.value)
        assertEquals(PresenceState.ERROR, StayFrameServiceBridge.presenceState.value)
        assertTrue(StayFrameServiceBridge.lastErrorMessage.value?.contains("Notification Listener") == true)

        // Reconnect clears listener error cleanly
        service.onListenerConnected()
        assertEquals(service, StayFrameServiceBridge.listenerServiceInstance)
        assertTrue(StayFrameServiceBridge.listenerConnected.value)
        assertEquals(PresenceState.PRESENT, StayFrameServiceBridge.presenceState.value)
        assertNull(StayFrameServiceBridge.lastErrorMessage.value)

        controller.destroy()
    }

    @Test
    fun testT2_ListenerServiceCommandDispatch_ReturnsFalseWhenNoControllerFound() {
        val controller = Robolectric.buildService(StayFrameNotificationListenerService::class.java).create()
        val service = controller.get()
        assertFalse(service.sendPauseCommand("com.google.android.youtube"))
        assertFalse(service.sendRewindCommand("com.google.android.youtube", 10000L))
        assertFalse(service.sendPlayCommand("com.google.android.youtube"))
        controller.destroy()
    }

    @Test
    fun testT3_ComputeMeanLuminanceFromYPlane_DetectsDarkVsLitFrames() {
        val width = 16
        val height = 16
        val darkBytes = ByteArray(width * height) { 5.toByte() }
        val darkLuma = MonitoringForegroundService.computeMeanLuminanceFromYPlane(
            buffer = ByteBuffer.wrap(darkBytes),
            rowStride = width,
            pixelStride = 1,
            width = width,
            height = height
        )
        assertTrue("Expected dark luma < 18, got $darkLuma", darkLuma < MonitoringForegroundService.MIN_ADEQUATE_LUMA)

        val brightBytes = ByteArray(width * height) { 120.toByte() }
        val brightLuma = MonitoringForegroundService.computeMeanLuminanceFromYPlane(
            buffer = ByteBuffer.wrap(brightBytes),
            rowStride = width,
            pixelStride = 1,
            width = width,
            height = height
        )
        assertTrue("Expected bright luma >= 18, got $brightLuma", brightLuma >= MonitoringForegroundService.MIN_ADEQUATE_LUMA)
    }

    @Test
    fun testT7_OnStartCommand_ReturnsStartNotStickyAndRecreatesResourcesAcrossStopStart() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val startIntent = Intent(context, MonitoringForegroundService::class.java)
            .setAction(MonitoringForegroundService.ACTION_START)
        val stopIntent = Intent(context, MonitoringForegroundService::class.java)
            .setAction(MonitoringForegroundService.ACTION_STOP)

        val controller = Robolectric.buildService(MonitoringForegroundService::class.java, startIntent).create()
        val service = controller.get()

        // Wait: before startMonitoringService(), resources are not yet allocated
        assertFalse(service.hasActiveProcessingResourcesForTest())

        val startStickyResult = service.onStartCommand(startIntent, 0, 1)
        assertEquals(Service.START_NOT_STICKY, startStickyResult)
        assertTrue(service.hasActiveProcessingResourcesForTest())

        val stopStickyResult = service.onStartCommand(stopIntent, 0, 2)
        assertEquals(Service.START_NOT_STICKY, stopStickyResult)
        assertFalse(service.hasActiveProcessingResourcesForTest())

        // Second start on same Service instance recreates executor and detector cleanly
        val restartResult = service.onStartCommand(startIntent, 0, 3)
        assertEquals(Service.START_NOT_STICKY, restartResult)
        assertTrue(service.hasActiveProcessingResourcesForTest())

        controller.destroy()
        assertFalse(service.hasActiveProcessingResourcesForTest())
    }

    @Test
    fun testT7_StartForegroundException_HandledSafelyWithoutCrashAndLogsDiagnostic() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        StayFrameServiceBridge.clearDiagnosticEvents()
        MonitoringForegroundService.foregroundStarterOverride = { _, _, _ ->
            throw IllegalStateException("ForegroundServiceStartNotAllowedException simulation")
        }
        try {
            val startIntent = Intent(context, MonitoringForegroundService::class.java)
                .setAction(MonitoringForegroundService.ACTION_START)
            val controller = Robolectric.buildService(MonitoringForegroundService::class.java, startIntent).create()
            val service = controller.get()

            val stickyMode = service.onStartCommand(startIntent, 0, 1)
            assertEquals(Service.START_NOT_STICKY, stickyMode)
            assertFalse(StayFrameServiceBridge.isMonitoring.value)
            assertEquals(PresenceState.ERROR, StayFrameServiceBridge.presenceState.value)
            assertTrue(StayFrameServiceBridge.diagnosticEvents.value.any { it.eventName == "fgs_start_failed" })
            assertFalse(service.hasActiveProcessingResourcesForTest())
        } finally {
            MonitoringForegroundService.foregroundStarterOverride = null
        }
    }

    @Test
    fun testT7_ArmedSessionRecovery_PostsTapToResumeNotificationWithoutBackgroundStart() {
        runBlocking {
            val appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
            Shadows.shadowOf(appContext).grantPermissions(Manifest.permission.CAMERA)
            StayFramePreferencesRepository(appContext).setMonitoringArmed(true)
            StayFrameServiceBridge.clearDiagnosticEvents()
            StayFrameServiceBridge.setMonitoring(false)

            val controller = Robolectric.buildService(StayFrameNotificationListenerService::class.java).create()
            val listenerService = controller.get()
            listenerService.attemptArmedSessionRecovery()

            assertFalse(StayFrameServiceBridge.isMonitoring.value)
            assertTrue(
                StayFrameServiceBridge.diagnosticEvents.value.any {
                    it.eventName == "recovery_tap_to_resume_posted"
                }
            )

            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val posted = Shadows.shadowOf(nm).getNotification(1002)
            assertNotNull(posted)
            val pendingShadow = Shadows.shadowOf(posted.contentIntent)
            val savedIntent = pendingShadow.savedIntent
            assertEquals(MainActivity::class.java.name, savedIntent.component?.className)
            assertTrue(savedIntent.getBooleanExtra(MainActivity.EXTRA_RESUME_MONITORING, false))

            controller.destroy()
        }
    }

    @Test
    fun testT8_UnifiedMatchThresholdAndDiagnosticExceptionRecording() {
        assertEquals(FaceDescriptorExtractor.RECOMMENDED_MATCH_THRESHOLD, FaceTemplateStore.MATCH_THRESHOLD, 0.0001f)
        assertEquals(FaceDescriptorExtractor.RECOMMENDED_MATCH_THRESHOLD, PresenceStateEngine().matchThreshold, 0.0001f)

        StayFrameServiceBridge.clearDiagnosticEvents()
        StayFrameServiceBridge.recordRecoverableException(
            eventName = "test_recoverable_exception",
            contextMessage = "Simulated failure",
            error = IllegalStateException("Test fault")
        )
        val recorded = StayFrameServiceBridge.diagnosticEvents.value.firstOrNull()
        assertNotNull(recorded)
        assertEquals("test_recoverable_exception", recorded?.eventName)
        assertTrue(recorded?.details?.contains("IllegalStateException") == true)
    }
}
