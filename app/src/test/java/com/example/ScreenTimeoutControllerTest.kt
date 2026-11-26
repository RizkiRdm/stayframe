package com.example

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.domain.controller.ScreenTimeoutController
import com.example.domain.model.ScreenTimeoutMode
import com.example.service.MonitoringForegroundService
import com.example.service.ScreenTimeoutExpiryReceiver
import com.example.service.StayFrameServiceBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class ScreenTimeoutControllerTest {

    private lateinit var context: Context
    private lateinit var controller: ScreenTimeoutController

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("stayframe_screen_timeout", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 30000)
        controller = ScreenTimeoutController(context)
    }

    @Test
    fun testScenario8_ScreenTimeout_AppliesAndRestoresOriginalTimeout() {
        assertEquals(30000, controller.getCurrentSystemTimeout())
        assertFalse(controller.isOverrideActive())

        val applied = controller.applyTimeout(ScreenTimeoutMode.MIN_15)
        assertTrue(applied)
        assertTrue(controller.isOverrideActive())
        assertEquals(30000, controller.getOriginalTimeout())
        assertEquals(15 * 60 * 1000, controller.getCurrentSystemTimeout())

        val restored = controller.restoreTimeout()
        assertTrue(restored)
        assertFalse(controller.isOverrideActive())
        assertEquals(30000, controller.getCurrentSystemTimeout())
    }

    @Test
    fun testScenario8_StaleOverrideRecovery() {
        val prefs = context.getSharedPreferences("stayframe_screen_timeout", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("is_override_active", true)
            .putInt("original_timeout_ms", 15000)
            .commit()
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 900000)

        assertTrue(controller.isOverrideActive())

        val recovered = controller.checkAndRecoverStaleOverride()
        assertTrue(recovered)
        assertFalse(controller.isOverrideActive())
        assertEquals(15000, controller.getCurrentSystemTimeout())
    }

    @Test
    fun testScenario8_ApplyTimeoutOff_RestoresSystemTimeout() {
        controller.applyTimeout(ScreenTimeoutMode.MIN_30)
        assertTrue(controller.isOverrideActive())

        controller.applyTimeout(ScreenTimeoutMode.OFF)
        assertFalse(controller.isOverrideActive())
        assertEquals(30000, controller.getCurrentSystemTimeout())
    }

    @Test
    fun testT5_TimedMode_SchedulesAlarmManagerAndRestoreCancelsAlarm() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarm = Shadows.shadowOf(alarmManager)

        assertTrue(controller.applyTimeout(ScreenTimeoutMode.MIN_15))
        assertTrue(controller.isOverrideActive())
        assertNotNull(shadowAlarm.peekNextScheduledAlarm())

        assertTrue(controller.restoreTimeout())
        assertFalse(controller.isOverrideActive())
        assertTrue(shadowAlarm.scheduledAlarms.isEmpty())
        assertEquals(30000, controller.getCurrentSystemTimeout())
    }

    @Test
    fun testT5_ExpiryReceiverBroadcast_RestoresTimeoutAndResetsBridgeMode() {
        StayFrameServiceBridge.setScreenTimeoutMode(ScreenTimeoutMode.MIN_30)
        assertTrue(controller.applyTimeout(ScreenTimeoutMode.MIN_30))
        assertEquals(30 * 60 * 1000, controller.getCurrentSystemTimeout())

        ScreenTimeoutExpiryReceiver().onReceive(
            context,
            Intent(ScreenTimeoutController.ACTION_RESTORE_SCREEN_TIMEOUT)
        )

        assertFalse(controller.isOverrideActive())
        assertEquals(30000, controller.getCurrentSystemTimeout())
        assertEquals(ScreenTimeoutMode.OFF, StayFrameServiceBridge.screenTimeoutMode.value)
    }

    @Test
    fun testT5_MonitoringServiceStopAndDestroy_RestoresTimeout() {
        StayFrameServiceBridge.setScreenTimeoutMode(ScreenTimeoutMode.UNTIL_STOPPED)
        assertTrue(controller.applyTimeout(ScreenTimeoutMode.UNTIL_STOPPED))
        assertTrue(controller.isOverrideActive())

        val stopIntent = Intent(context, MonitoringForegroundService::class.java)
            .setAction(MonitoringForegroundService.ACTION_STOP)
        val serviceStop = Robolectric.buildService(MonitoringForegroundService::class.java, stopIntent).create()
        serviceStop.startCommand(0, 1)
        assertFalse(controller.isOverrideActive())
        assertEquals(30000, controller.getCurrentSystemTimeout())
        assertEquals(ScreenTimeoutMode.OFF, StayFrameServiceBridge.screenTimeoutMode.value)

        StayFrameServiceBridge.setScreenTimeoutMode(ScreenTimeoutMode.MIN_60)
        assertTrue(controller.applyTimeout(ScreenTimeoutMode.MIN_60))
        assertTrue(controller.isOverrideActive())

        val serviceDestroy = Robolectric.buildService(MonitoringForegroundService::class.java).create()
        serviceDestroy.destroy()
        assertFalse(controller.isOverrideActive())
        assertEquals(30000, controller.getCurrentSystemTimeout())
        assertEquals(ScreenTimeoutMode.OFF, StayFrameServiceBridge.screenTimeoutMode.value)
    }
}
