package com.example

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.domain.controller.ScreenTimeoutController
import com.example.domain.model.ScreenTimeoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
