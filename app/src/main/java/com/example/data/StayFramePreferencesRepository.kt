package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.DetectionMode
import com.example.domain.model.ScreenTimeoutMode
import com.example.service.StayFrameServiceBridge
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.stayFrameDataStore: DataStore<Preferences> by preferencesDataStore(name = "stayframe_settings")

data class StayFramePreferences(
    val gracePeriodSeconds: Int = 5,
    val detectionMode: DetectionMode = DetectionMode.RELAXED_FALLBACK,
    val screenTimeoutMode: ScreenTimeoutMode = ScreenTimeoutMode.MIN_15,
    val isMonitoringArmed: Boolean = false
)

// Repository for persisting grace period, detection mode, screen timeout, and armed monitoring state
class StayFramePreferencesRepository(private val context: Context) {

    companion object {
        val KEY_ABSENCE_GRACE_PERIOD = intPreferencesKey("absence_grace_period_seconds")
        val KEY_DETECTION_MODE = stringPreferencesKey("detection_mode")
        val KEY_SCREEN_TIMEOUT_MODE = stringPreferencesKey("screen_timeout_mode")
        val KEY_MONITORING_ARMED = booleanPreferencesKey("is_monitoring_armed")
    }

    val preferencesFlow: Flow<StayFramePreferences> = context.stayFrameDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val gracePeriod = preferences[KEY_ABSENCE_GRACE_PERIOD] ?: 5
            val detectionModeStr = preferences[KEY_DETECTION_MODE]
            val detectionMode = if (detectionModeStr != null) {
                try {
                    DetectionMode.valueOf(detectionModeStr)
                } catch (e: Exception) {
                    StayFrameServiceBridge.recordRecoverableException(
                        "prefs_detection_mode_parse_failed",
                        "Invalid stored DetectionMode '$detectionModeStr'; falling back to RELAXED_FALLBACK",
                        e
                    )
                    DetectionMode.RELAXED_FALLBACK
                }
            } else {
                DetectionMode.RELAXED_FALLBACK
            }

            val screenTimeoutStr = preferences[KEY_SCREEN_TIMEOUT_MODE]
            val screenTimeoutMode = if (screenTimeoutStr != null) {
                try {
                    ScreenTimeoutMode.valueOf(screenTimeoutStr)
                } catch (e: Exception) {
                    StayFrameServiceBridge.recordRecoverableException(
                        "prefs_screen_timeout_parse_failed",
                        "Invalid stored ScreenTimeoutMode '$screenTimeoutStr'; falling back to MIN_15",
                        e
                    )
                    ScreenTimeoutMode.MIN_15
                }
            } else {
                ScreenTimeoutMode.MIN_15
            }

            val isArmed = preferences[KEY_MONITORING_ARMED] ?: false

            StayFramePreferences(
                gracePeriodSeconds = gracePeriod,
                detectionMode = detectionMode,
                screenTimeoutMode = screenTimeoutMode,
                isMonitoringArmed = isArmed
            )
        }

    suspend fun getSnapshot(): StayFramePreferences = preferencesFlow.first()

    suspend fun setAbsenceGracePeriod(seconds: Int) {
        context.stayFrameDataStore.edit { preferences ->
            preferences[KEY_ABSENCE_GRACE_PERIOD] = seconds
        }
    }

    suspend fun setDetectionMode(mode: DetectionMode) {
        context.stayFrameDataStore.edit { preferences ->
            preferences[KEY_DETECTION_MODE] = mode.name
        }
    }

    suspend fun setScreenTimeoutMode(mode: ScreenTimeoutMode) {
        context.stayFrameDataStore.edit { preferences ->
            preferences[KEY_SCREEN_TIMEOUT_MODE] = mode.name
        }
    }

    suspend fun setMonitoringArmed(armed: Boolean) {
        context.stayFrameDataStore.edit { preferences ->
            preferences[KEY_MONITORING_ARMED] = armed
        }
    }
}
