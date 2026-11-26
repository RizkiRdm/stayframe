package com.example.domain.controller

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.example.domain.model.ScreenTimeoutMode
import com.example.service.ScreenTimeoutExpiryReceiver
import com.example.service.StayFrameServiceBridge

/**
 * Manages the optional screen-timeout override (PRD Section 7.6)
 * Safely backs up original system timeout and restores it upon session stop or AlarmManager expiry.
 */
class ScreenTimeoutController(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "stayframe_screen_timeout"
        private const val KEY_ORIGINAL_TIMEOUT = "original_timeout_ms"
        private const val KEY_OVERRIDE_ACTIVE = "is_override_active"
        private const val KEY_SELECTED_MODE = "selected_timeout_mode"
        private const val DEFAULT_TIMEOUT_MS = 30000 // 30 seconds default
        const val ACTION_RESTORE_SCREEN_TIMEOUT = "com.example.action.RESTORE_SCREEN_TIMEOUT"
        private const val ALARM_REQUEST_CODE = 2001
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
        } catch (e: Exception) {
            StayFrameServiceBridge.recordRecoverableException(
                "screen_timeout_read_failed",
                "Failed to read system SCREEN_OFF_TIMEOUT",
                e
            )
            DEFAULT_TIMEOUT_MS
        }
    }

    /**
     * Temporarily apply screen timeout override and schedule timed restoration via AlarmManager (PRD DISP-04)
     */
    fun applyTimeout(mode: ScreenTimeoutMode): Boolean {
        cancelScheduledRestoreAlarm()

        if (mode == ScreenTimeoutMode.OFF) {
            restoreTimeout()
            return true
        }

        if (!hasWriteSettingsPermission()) {
            return false
        }

        return try {
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

            // Schedule Play-safe inexact AlarmManager restoration for timed modes
            if (mode.durationMinutes != null && mode.durationMinutes > 0) {
                val durationMs = mode.durationMinutes * 60 * 1000L
                val triggerAtElapsed = SystemClock.elapsedRealtime() + durationMs
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                alarmManager?.set(
                    AlarmManager.ELAPSED_REALTIME,
                    triggerAtElapsed,
                    createExpiryPendingIntent()
                )
            }

            true
        } catch (e: Exception) {
            StayFrameServiceBridge.recordRecoverableException(
                "screen_timeout_apply_failed",
                "Failed to apply screen timeout override ($mode)",
                e
            )
            false
        }
    }

    /**
     * Restore original system screen off timeout and cancel pending expiry alarm
     */
    fun restoreTimeout(): Boolean {
        cancelScheduledRestoreAlarm()

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
        } catch (e: Exception) {
            StayFrameServiceBridge.recordRecoverableException(
                "screen_timeout_restore_failed",
                "Failed to restore original screen timeout ($originalTimeout ms)",
                e
            )
            false
        }
    }

    private fun cancelScheduledRestoreAlarm() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        alarmManager?.cancel(createExpiryPendingIntent())
    }

    internal fun createExpiryPendingIntent(): PendingIntent {
        val intent = Intent(context, ScreenTimeoutExpiryReceiver::class.java).apply {
            action = ACTION_RESTORE_SCREEN_TIMEOUT
        }
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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

