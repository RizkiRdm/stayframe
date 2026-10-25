package com.example.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.example.data.FaceTemplateStore
import com.example.domain.controller.ScreenTimeoutController
import com.example.domain.engine.FaceObservation
import com.example.domain.model.ActiveMediaSessionInfo
import com.example.domain.model.DetectionMode
import com.example.domain.model.DiagnosticEvent
import com.example.domain.model.PresenceState
import com.example.domain.model.ScreenTimeoutMode
import com.example.service.MonitoringForegroundService
import com.example.service.StayFrameNotificationListenerService
import com.example.service.StayFrameServiceBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class StayFrameViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication<Application>().applicationContext
    val templateStore = FaceTemplateStore(context)
    val screenTimeoutController = ScreenTimeoutController(context)

    // Bridge States
    val presenceState: StateFlow<PresenceState> = StayFrameServiceBridge.presenceState
    val activeSession: StateFlow<ActiveMediaSessionInfo> = StayFrameServiceBridge.activeSession
    val isMonitoring: StateFlow<Boolean> = StayFrameServiceBridge.isMonitoring
    val isPreviewVisible: StateFlow<Boolean> = StayFrameServiceBridge.isPreviewVisible
    val lastObservation: StateFlow<FaceObservation?> = StayFrameServiceBridge.lastObservation
    val lastRewindOffsetMs: StateFlow<Long?> = StayFrameServiceBridge.lastRewindOffsetMs
    val diagnosticEvents: StateFlow<List<DiagnosticEvent>> = StayFrameServiceBridge.diagnosticEvents
    val remainingGraceSeconds: StateFlow<Int> = StayFrameServiceBridge.remainingGraceSeconds
    val sustainedPresenceProgress: StateFlow<Float> = StayFrameServiceBridge.sustainedPresenceProgress

    // User Settings & Biometrics
    private val _isEnrolled = MutableStateFlow(templateStore.isEnrolled())
    val isEnrolled: StateFlow<Boolean> = _isEnrolled.asStateFlow()

    private val _enrolledUserName = MutableStateFlow(templateStore.getEnrolledUserName())
    val enrolledUserName: StateFlow<String> = _enrolledUserName.asStateFlow()

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

        // MON-08: If camera permission was revoked while monitoring is active, halt safely
        if (isMonitoring.value && !hasCamera) {
            stopMonitoring()
            StayFrameServiceBridge.engine.reportError("Camera permission was revoked. Monitoring halted.")
        }
    }

    fun toggleMonitoring() {
        if (isMonitoring.value) {
            stopMonitoring()
        } else {
            startMonitoring()
        }
    }

    fun startMonitoring() {
        MonitoringForegroundService.start(context)
        StayFrameServiceBridge.setMonitoring(true)
        screenTimeoutController.applyTimeout(screenTimeoutMode.value)
    }

    fun stopMonitoring() {
        MonitoringForegroundService.stop(context)
        StayFrameServiceBridge.setMonitoring(false)
        screenTimeoutController.restoreTimeout()
    }

    fun setAbsenceGracePeriod(seconds: Int) {
        _absenceGracePeriod.value = seconds
        StayFrameServiceBridge.engine.setAbsenceGracePeriod(seconds)
    }

    fun setDetectionMode(mode: DetectionMode) {
        _detectionMode.value = mode
        StayFrameServiceBridge.engine.setDetectionMode(mode)
    }

    fun setScreenTimeoutMode(mode: ScreenTimeoutMode) {
        StayFrameServiceBridge.setScreenTimeoutMode(mode)
        if (isMonitoring.value) {
            screenTimeoutController.applyTimeout(mode)
        }
    }

    fun togglePreview(visible: Boolean) {
        StayFrameServiceBridge.setPreviewVisible(visible)
    }

    fun enrollFace(name: String, embedding: FloatArray): Boolean {
        val success = templateStore.saveTemplate(embedding, name)
        if (success) {
            _isEnrolled.value = true
            _enrolledUserName.value = name
            StayFrameServiceBridge.addDiagnosticEvent(
                DiagnosticEvent(
                    state = presenceState.value,
                    eventName = "face_enrolled",
                    details = "Biometric template registered securely for $name"
                )
            )
        }
        return success
    }

    fun deleteEnrollment() {
        templateStore.deleteTemplate()
        _isEnrolled.value = false
        _enrolledUserName.value = "Primary Viewer"
        StayFrameServiceBridge.addDiagnosticEvent(
            DiagnosticEvent(
                state = presenceState.value,
                eventName = "face_deleted",
                details = "Biometric template and Keystore key wiped completely"
            )
        )
    }

    /**
     * One-action full reset of biometric template, diagnostic logs, and settings (PRD PRIV-05)
     */
    fun resetAllAppData() {
        deleteEnrollment()
        clearDiagnostics()
        setAbsenceGracePeriod(5)
        setDetectionMode(DetectionMode.STRICT)
        setScreenTimeoutMode(ScreenTimeoutMode.OFF)
        screenTimeoutController.restoreTimeout()
    }

    fun manualResumePlayback() {
        StayFrameServiceBridge.engine.userRequestResume()
    }

    fun clearDiagnostics() {
        StayFrameServiceBridge.clearDiagnosticEvents()
    }

    /**
     * Diagnostic testing helper to test presence scenarios in diagnostics tab
     */
    fun simulateObservation(observation: FaceObservation) {
        StayFrameServiceBridge.onFaceObserved(observation)
    }

    fun simulateActiveSession(session: ActiveMediaSessionInfo) {
        StayFrameServiceBridge.updateActiveSession(session)
    }
}
