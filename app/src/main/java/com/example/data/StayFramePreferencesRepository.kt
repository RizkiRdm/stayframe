package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.DetectionMode
import com.example.domain.model.ScreenTimeoutMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

val Context.stayFrameDataStore: DataStore<Preferences> by preferencesDataStore(name = "stayframe_settings")

data class StayFramePreferences(
    val gracePeriodSeconds: Int = 5,
    val detectionMode: DetectionMode = DetectionMode.RELAXED_FALLBACK,
    val screenTimeoutMode: ScreenTimeoutMode = ScreenTimeoutMode.MIN_15
)

// Repository for persisting grace period, detection mode, and screen timeout settings via DataStore
class StayFramePreferencesRepository(private val context: Context) {

    companion object {
        val KEY_ABSENCE_GRACE_PERIOD = intPreferencesKey("absence_grace_period_seconds")
        val KEY_DETECTION_MODE = stringPreferencesKey("detection_mode")
        val KEY_SCREEN_TIMEOUT_MODE = stringPreferencesKey("screen_timeout_mode")
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
                } catch (_: Exception) {
                    DetectionMode.RELAXED_FALLBACK
                }
            } else {
                DetectionMode.RELAXED_FALLBACK
            }

            val screenTimeoutStr = preferences[KEY_SCREEN_TIMEOUT_MODE]
            val screenTimeoutMode = if (screenTimeoutStr != null) {
                try {
                    ScreenTimeoutMode.valueOf(screenTimeoutStr)
                } catch (_: Exception) {
                    ScreenTimeoutMode.MIN_15
                }
            } else {
                ScreenTimeoutMode.MIN_15
            }

            StayFramePreferences(
                gracePeriodSeconds = gracePeriod,
                detectionMode = detectionMode,
                screenTimeoutMode = screenTimeoutMode
            )
        }

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
}
