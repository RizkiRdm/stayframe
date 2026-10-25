package com.example.domain.controller

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.example.domain.model.ScreenTimeoutMode
import com.example.service.StayFrameServiceBridge

/**
 * Manages the optional screen-timeout override (PRD Section 7.6)
 * Safely backs up original system timeout and restores it upon session stop or expiry.
 */
class ScreenTimeoutController(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    private val timedExpiryRunnable = Runnable {
        restoreTimeout()
        StayFrameServiceBridge.setScreenTimeoutMode(ScreenTimeoutMode.OFF)
    }

    companion object {
        private const val PREFS_NAME = "stayframe_screen_timeout"
        private const val KEY_ORIGINAL_TIMEOUT = "original_timeout_ms"
        private const val KEY_OVERRIDE_ACTIVE = "is_override_active"
        private const val KEY_SELECTED_MODE = "selected_timeout_mode"
        private const val DEFAULT_TIMEOUT_MS = 30000 // 30 seconds default
    }

    fun hasWriteSettingsPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else {
            true
        }
    }

    fun createWriteSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * Get current system screen off timeout in milliseconds
     */
    fun getCurrentSystemTimeout(): Int {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT
            )
        } catch (_: Exception) {
            DEFAULT_TIMEOUT_MS
        }
    }

    /**
     * Temporarily apply screen timeout override and schedule timed restoration (PRD DISP-04)
     */
    fun applyTimeout(mode: ScreenTimeoutMode): Boolean {
        handler.removeCallbacks(timedExpiryRunnable)

        if (mode == ScreenTimeoutMode.OFF) {
            restoreTimeout()
            return true
        }

        if (!hasWriteSettingsPermission()) {
            return false
        }

        try {
            // Backup original system timeout if not already backed up
            if (!prefs.getBoolean(KEY_OVERRIDE_ACTIVE, false)) {
                val currentTimeout = getCurrentSystemTimeout()
                prefs.edit()
                    .putInt(KEY_ORIGINAL_TIMEOUT, currentTimeout)
                    .putBoolean(KEY_OVERRIDE_ACTIVE, true)
                    .putString(KEY_SELECTED_MODE, mode.name)
                    .apply()
            }

            val targetTimeoutMs = when (mode) {
                ScreenTimeoutMode.MIN_15 -> 15 * 60 * 1000
                ScreenTimeoutMode.MIN_30 -> 30 * 60 * 1000
                ScreenTimeoutMode.MIN_60 -> 60 * 60 * 1000
                ScreenTimeoutMode.UNTIL_STOPPED -> Integer.MAX_VALUE
                ScreenTimeoutMode.OFF -> return true
            }

            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                targetTimeoutMs
            )

            // Schedule automatic restoration for timed modes (PRD DISP-04)
            if (mode.durationMinutes != null && mode.durationMinutes > 0) {
                val durationMs = mode.durationMinutes * 60 * 1000L
                handler.postDelayed(timedExpiryRunnable, durationMs)
            }

            return true
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Restore original system screen off timeout and cancel pending expiry timers
     */
    fun restoreTimeout(): Boolean {
        handler.removeCallbacks(timedExpiryRunnable)

        if (!prefs.getBoolean(KEY_OVERRIDE_ACTIVE, false)) {
            return true
        }

        val originalTimeout = prefs.getInt(KEY_ORIGINAL_TIMEOUT, DEFAULT_TIMEOUT_MS)

        return try {
            if (hasWriteSettingsPermission()) {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    originalTimeout
                )
            }
            prefs.edit()
                .putBoolean(KEY_OVERRIDE_ACTIVE, false)
                .remove(KEY_ORIGINAL_TIMEOUT)
                .apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check if a previous session was interrupted and restore if needed (PRD DISP-07)
     */
    fun checkAndRecoverStaleOverride(): Boolean {
        if (prefs.getBoolean(KEY_OVERRIDE_ACTIVE, false)) {
            return restoreTimeout()
        }
        return false
    }

    fun isOverrideActive(): Boolean {
        return prefs.getBoolean(KEY_OVERRIDE_ACTIVE, false)
    }

    fun getOriginalTimeout(): Int {
        return prefs.getInt(KEY_ORIGINAL_TIMEOUT, DEFAULT_TIMEOUT_MS)
    }
}
