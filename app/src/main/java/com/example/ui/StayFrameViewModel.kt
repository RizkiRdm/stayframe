package com.example.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.FaceTemplateStore
import com.example.data.StayFramePreferencesRepository
import com.example.data.registry.DiscoveredPlayerEntity
import com.example.data.registry.PlayerRegistryRepository
import com.example.domain.controller.ScreenTimeoutController
import com.example.domain.engine.FaceObservation
import com.example.domain.model.ActiveMediaSessionInfo
import com.example.domain.model.DetectionMode
import com.example.domain.model.DiagnosticEvent
import com.example.domain.model.PresenceState
import com.example.domain.model.ScreenTimeoutMode
import com.example.domain.model.SyncSource
import com.example.service.MonitoringForegroundService
import com.example.service.PlayerRescanWorker
import com.example.service.StayFrameNotificationListenerService
import com.example.service.StayFrameServiceBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StayFrameViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication<Application>().applicationContext
    val templateStore = FaceTemplateStore(context)
    val screenTimeoutController = ScreenTimeoutController(context)
    val preferencesRepository = StayFramePreferencesRepository(context)
    val registryRepository = PlayerRegistryRepository(context)

    // Bridge States
    val presenceState: StateFlow<PresenceState> = StayFrameServiceBridge.presenceState
    val activeSession: StateFlow<ActiveMediaSessionInfo> = StayFrameServiceBridge.activeSession
    val isMonitoring: StateFlow<Boolean> = StayFrameServiceBridge.isMonitoring
    val isPreviewVisible: StateFlow<Boolean> = StayFrameServiceBridge.isPreviewVisible
    val lastObservation: StateFlow<FaceObservation?> = StayFrameServiceBridge.lastObservation
    val lastRewindOffsetMs: StateFlow<Long?> = StayFrameServiceBridge.lastRewindOffsetMs
    val diagnosticEvents: StateFlow<List<DiagnosticEvent>> = StayFrameServiceBridge.diagnosticEvents
    val remainingGraceSeconds: StateFlow<Int> = StayFrameServiceBridge.remainingGraceSeconds
    val effectiveGraceSeconds: StateFlow<Int> = StayFrameServiceBridge.effectiveGraceSeconds
    val activeSyncSource: StateFlow<SyncSource> = StayFrameServiceBridge.activeSyncSource
    val sustainedPresenceProgress: StateFlow<Float> = StayFrameServiceBridge.sustainedPresenceProgress
    val listenerConnected: StateFlow<Boolean> = StayFrameServiceBridge.listenerConnected
    val lastErrorMessage: StateFlow<String?> = StayFrameServiceBridge.lastErrorMessage
    val presenceEngineLastErrorMessage: String? get() = StayFrameServiceBridge.engine.lastErrorMessage

    // Dynamic Player Registry
    val discoveredPlayers: StateFlow<List<DiscoveredPlayerEntity>> = registryRepository.playersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isRescanningPlayers = MutableStateFlow(false)
    val isRescanningPlayers: StateFlow<Boolean> = _isRescanningPlayers.asStateFlow()

    // User Settings & Strict Facial Identity
    private val _isEnrolled = MutableStateFlow(templateStore.isEnrolled())
    val isEnrolled: StateFlow<Boolean> = _isEnrolled.asStateFlow()

    private val _enrolledUserName = MutableStateFlow(templateStore.getEnrolledUserName())
    val enrolledUserName: StateFlow<String> = _enrolledUserName.asStateFlow()

    private val _enrolledTimestamp = MutableStateFlow(templateStore.getEnrolledTimestamp())
    val enrolledTimestamp: StateFlow<Long> = _enrolledTimestamp.asStateFlow()

    private val _absenceGracePeriod = MutableStateFlow(StayFrameServiceBridge.engine.getAbsenceGracePeriodSeconds())
    val absenceGracePeriod: StateFlow<Int> = _absenceGracePeriod.asStateFlow()

    private val _detectionMode = MutableStateFlow(StayFrameServiceBridge.engine.getDetectionMode())
    val detectionMode: StateFlow<DetectionMode> = _detectionMode.asStateFlow()

    val screenTimeoutMode: StateFlow<ScreenTimeoutMode> = StayFrameServiceBridge.screenTimeoutMode

    // System Permissions
    private val _cameraPermissionGranted = MutableStateFlow(false)
    val cameraPermissionGranted: StateFlow<Boolean> = _cameraPermissionGranted.asStateFlow()

    private val _notificationListenerGranted = MutableStateFlow(false)
    val notificationListenerGranted: StateFlow<Boolean> = _notificationListenerGranted.asStateFlow()

    private val _writeSettingsGranted = MutableStateFlow(false)
    val writeSettingsGranted: StateFlow<Boolean> = _writeSettingsGranted.asStateFlow()

    init {
        refreshPermissions()
        screenTimeoutController.checkAndRecoverStaleOverride()
        StayFrameServiceBridge.listenerServiceInstance?.refreshActiveSessions()
        PlayerRescanWorker.schedulePeriodicRescan(context)

        viewModelScope.launch {
            registryRepository.rescanAndValidateRegistry(force = false)
        }

        // Persisted settings collection
        viewModelScope.launch {
            preferencesRepository.preferencesFlow.collect { prefs ->
                _absenceGracePeriod.value = prefs.gracePeriodSeconds
                StayFrameServiceBridge.engine.setAbsenceGracePeriod(prefs.gracePeriodSeconds)

                // Guard against invalid Strict selection if template was deleted
                val resolvedMode = if (prefs.detectionMode == DetectionMode.STRICT && !templateStore.isEnrolled()) {
                    DetectionMode.RELAXED_FALLBACK
                } else {
                    prefs.detectionMode
                }
                _detectionMode.value = resolvedMode
                StayFrameServiceBridge.engine.setDetectionMode(resolvedMode)

                StayFrameServiceBridge.setScreenTimeoutMode(prefs.screenTimeoutMode)
                if (isMonitoring.value) {
                    screenTimeoutController.applyTimeout(prefs.screenTimeoutMode)
                }
            }
        }
    }

    fun refreshPermissions() {
        val hasCamera = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        _cameraPermissionGranted.value = hasCamera

        val hasNotification =
            StayFrameNotificationListenerService.isNotificationListenerEnabled(context)
        _notificationListenerGranted.value = hasNotification

        _writeSettingsGranted.value = screenTimeoutController.hasWriteSettingsPermission()
        _isEnrolled.value = templateStore.isEnrolled()
        _enrolledTimestamp.value = templateStore.getEnrolledTimestamp()
        _enrolledUserName.value = templateStore.getEnrolledUserName()

        if (isMonitoring.value && !hasCamera) {
            stopMonitoring()
            StayFrameServiceBridge.engine.reportError("Camera permission was revoked. Monitoring halted.")
        }
    }

    fun canStartMonitoring(): Boolean {
        refreshPermissions()
        return _cameraPermissionGranted.value && _notificationListenerGranted.value
    }

    fun toggleMonitoring(onRequiresPermissions: (() -> Unit)? = null) {
        if (isMonitoring.value) {
            stopMonitoring()
        } else {
            if (canStartMonitoring()) {
                startMonitoring()
            } else {
                onRequiresPermissions?.invoke()
            }
        }
    }

    fun startMonitoring(): Boolean {
        if (!canStartMonitoring()) {
            StayFrameServiceBridge.engine.reportError("Camera and Notification Listener permissions are required.")
            return false
        }
        viewModelScope.launch {
            preferencesRepository.setMonitoringArmed(true)
        }
        MonitoringForegroundService.start(context)
        StayFrameServiceBridge.setMonitoring(true)
        screenTimeoutController.applyTimeout(screenTimeoutMode.value)
        return true
    }

    fun resumeMonitoringFromNotification(): Boolean {
        if (isMonitoring.value) {
            return true
        }
        val started = startMonitoring()
        if (started) {
            StayFrameServiceBridge.addDiagnosticEvent(
                DiagnosticEvent(
                    state = presenceState.value,
                    eventName = "recovery_resumed_by_tap",
                    details = "Monitoring restarted from foreground tap-to-resume notification"
                )
            )
        }
        return started
    }

    fun stopMonitoring() {
        viewModelScope.launch {
            preferencesRepository.setMonitoringArmed(false)
        }
        MonitoringForegroundService.stop(context)
        StayFrameServiceBridge.setMonitoring(false)
        screenTimeoutController.restoreTimeout()
    }

    fun setAbsenceGracePeriod(seconds: Int) {
        _absenceGracePeriod.value = seconds
        StayFrameServiceBridge.engine.setAbsenceGracePeriod(seconds)
        StayFrameServiceBridge.listenerServiceInstance?.refreshActiveSessions()
        viewModelScope.launch {
            preferencesRepository.setAbsenceGracePeriod(seconds)
        }
    }

    fun setDetectionMode(mode: DetectionMode, onRequiresEnrollment: (() -> Unit)? = null) {
        if (mode == DetectionMode.STRICT && !templateStore.isEnrolled()) {
            onRequiresEnrollment?.invoke()
            return
        }
        _detectionMode.value = mode
        StayFrameServiceBridge.engine.setDetectionMode(mode)
        viewModelScope.launch {
            preferencesRepository.setDetectionMode(mode)
        }
    }

    fun setScreenTimeoutMode(mode: ScreenTimeoutMode) {
        StayFrameServiceBridge.setScreenTimeoutMode(mode)
        if (isMonitoring.value) {
            screenTimeoutController.applyTimeout(mode)
        }
        viewModelScope.launch {
            preferencesRepository.setScreenTimeoutMode(mode)
        }
    }

    fun togglePreview(visible: Boolean) {
        StayFrameServiceBridge.setPreviewVisible(visible)
    }

    fun rescanPlayers() {
        if (_isRescanningPlayers.value) return
        viewModelScope.launch {
            _isRescanningPlayers.value = true
            try {
                registryRepository.rescanAndValidateRegistry(force = true)
                StayFrameServiceBridge.listenerServiceInstance?.refreshActiveSessions()
            } finally {
                _isRescanningPlayers.value = false
            }
        }
    }

    fun setPlayerSyncedInterval(packageName: String, intervalSec: Int?) {
        viewModelScope.launch {
            registryRepository.setUserSyncedInterval(packageName, intervalSec)
            StayFrameServiceBridge.listenerServiceInstance?.refreshActiveSessions()
            StayFrameServiceBridge.addDiagnosticEvent(
                DiagnosticEvent(
                    state = presenceState.value,
                    eventName = "player_interval_updated",
                    details = if (intervalSec != null) {
                        "Synced $packageName interval to ${intervalSec}s"
                    } else {
                        "Reset $packageName interval to auto profile/fallback"
                    }
                )
            )
        }
    }

    fun setPlayerMonitoringEnabled(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            registryRepository.setPlayerMonitoringEnabled(packageName, enabled)
            StayFrameServiceBridge.listenerServiceInstance?.refreshActiveSessions()
        }
    }

    fun enrollFace(name: String, descriptor: FloatArray, activateStrictMode: Boolean = true): Boolean {
        val success = templateStore.saveTemplate(descriptor, name)
        if (success) {
            StayFrameServiceBridge.notifyTemplateChanged()
            _isEnrolled.value = true
            _enrolledUserName.value = name
            _enrolledTimestamp.value = templateStore.getEnrolledTimestamp()
            if (activateStrictMode) {
                _detectionMode.value = DetectionMode.STRICT
                StayFrameServiceBridge.engine.setDetectionMode(DetectionMode.STRICT)
                viewModelScope.launch {
                    preferencesRepository.setDetectionMode(DetectionMode.STRICT)
                }
            }
            StayFrameServiceBridge.addDiagnosticEvent(
                DiagnosticEvent(
                    state = presenceState.value,
                    eventName = "face_enrolled",
                    details = "Facial geometry profile registered locally for $name"
                )
            )
        }
        return success
    }

    fun deleteEnrollment() {
        templateStore.deleteTemplate()
        StayFrameServiceBridge.notifyTemplateChanged()
        _isEnrolled.value = false
        _enrolledUserName.value = "Primary Viewer"
        _enrolledTimestamp.value = 0L
        if (_detectionMode.value == DetectionMode.STRICT) {
            setDetectionMode(DetectionMode.RELAXED_FALLBACK)
        }
        StayFrameServiceBridge.addDiagnosticEvent(
            DiagnosticEvent(
                state = presenceState.value,
                eventName = "data_deleted",
                details = "Facial profile data and Keystore key wiped completely"
            )
        )
    }

    fun resetAllAppData() {
        deleteEnrollment()
        clearDiagnostics()
        setAbsenceGracePeriod(5)
        setDetectionMode(DetectionMode.RELAXED_FALLBACK)
        setScreenTimeoutMode(ScreenTimeoutMode.OFF)
        screenTimeoutController.restoreTimeout()
    }

    fun manualResumePlayback() {
        StayFrameServiceBridge.engine.userRequestResume()
    }

    fun clearDiagnostics() {
        StayFrameServiceBridge.clearDiagnosticEvents()
    }
}
