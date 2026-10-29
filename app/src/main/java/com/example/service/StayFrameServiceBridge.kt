package com.example.service

import androidx.camera.core.Preview
import com.example.domain.engine.FaceObservation
import com.example.domain.engine.PresenceEngineCallback
import com.example.domain.engine.PresenceStateEngine
import com.example.domain.model.ActiveMediaSessionInfo
import com.example.domain.model.DetectionMode
import com.example.domain.model.DiagnosticEvent
import com.example.domain.model.PresenceState
import com.example.domain.model.ScreenTimeoutMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global singleton bridge coordinating Foreground Service, NotificationListener,
 * and the Presentation Layer ViewModel.
 */
object StayFrameServiceBridge {

    val engine = PresenceStateEngine(
        absenceGracePeriodSeconds = 5,
        detectionMode = DetectionMode.RELAXED_FALLBACK,
        callback = object : PresenceEngineCallback {
            override fun onStateChanged(newState: PresenceState, previousState: PresenceState) {
                _presenceState.value = newState
            }

            override fun onSendPauseCommand(targetPackage: String, currentPositionMs: Long) {
                listenerServiceInstance?.sendPauseCommand(targetPackage)
            }

            override fun onSendRewindCommand(targetPackage: String, targetPositionMs: Long, rewindOffsetMs: Long) {
                listenerServiceInstance?.sendRewindCommand(targetPackage, targetPositionMs)
                _lastRewindOffsetMs.value = rewindOffsetMs
            }

            override fun onSendPlayCommand(targetPackage: String) {
                listenerServiceInstance?.sendPlayCommand(targetPackage)
            }

            override fun onDiagnosticLog(event: DiagnosticEvent) {
                addDiagnosticEvent(event)
            }
        }
    )

    private val _presenceState = MutableStateFlow(PresenceState.INACTIVE)
    val presenceState: StateFlow<PresenceState> = _presenceState.asStateFlow()

    private val _activeSession = MutableStateFlow(ActiveMediaSessionInfo())
    val activeSession: StateFlow<ActiveMediaSessionInfo> = _activeSession.asStateFlow()

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private val _isPreviewVisible = MutableStateFlow(false)
    val isPreviewVisible: StateFlow<Boolean> = _isPreviewVisible.asStateFlow()

    private val _lastObservation = MutableStateFlow<FaceObservation?>(null)
    val lastObservation: StateFlow<FaceObservation?> = _lastObservation.asStateFlow()

    private val _lastRewindOffsetMs = MutableStateFlow<Long?>(null)
    val lastRewindOffsetMs: StateFlow<Long?> = _lastRewindOffsetMs.asStateFlow()

    private val _diagnosticEvents = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val diagnosticEvents: StateFlow<List<DiagnosticEvent>> = _diagnosticEvents.asStateFlow()

    private val _remainingGraceSeconds = MutableStateFlow(5)
    val remainingGraceSeconds: StateFlow<Int> = _remainingGraceSeconds.asStateFlow()

    private val _sustainedPresenceProgress = MutableStateFlow(0f)
    val sustainedPresenceProgress: StateFlow<Float> = _sustainedPresenceProgress.asStateFlow()

    private val _screenTimeoutMode = MutableStateFlow(ScreenTimeoutMode.OFF)
    val screenTimeoutMode: StateFlow<ScreenTimeoutMode> = _screenTimeoutMode.asStateFlow()

    var listenerServiceInstance: StayFrameNotificationListenerService? = null
    var foregroundServiceInstance: MonitoringForegroundService? = null

    fun setMonitoring(active: Boolean) {
        _isMonitoring.value = active
        if (active) {
            engine.startMonitoring()
        } else {
            engine.stopMonitoring()
        }
        _presenceState.value = engine.currentState
    }

    fun setPreviewVisible(visible: Boolean) {
        _isPreviewVisible.value = visible
    }

    fun setScreenTimeoutMode(mode: ScreenTimeoutMode) {
        _screenTimeoutMode.value = mode
    }

    fun updateActiveSession(info: ActiveMediaSessionInfo) {
        _activeSession.value = info
        engine.updateActiveMediaSession(info)
    }

    fun onFaceObserved(observation: FaceObservation) {
        _lastObservation.value = observation
        engine.processObservation(observation)
        _presenceState.value = engine.currentState
        _remainingGraceSeconds.value = engine.getRemainingGraceSeconds()
        _sustainedPresenceProgress.value = engine.getSustainedPresenceProgress()
    }

    fun addDiagnosticEvent(event: DiagnosticEvent) {
        val current = _diagnosticEvents.value.toMutableList()
        current.add(0, event)
        // Keep latest 100 entries (PRD Section 17)
        if (current.size > 100) {
            current.removeAt(current.lastIndex)
        }
        _diagnosticEvents.value = current
    }

    fun clearDiagnosticEvents() {
        _diagnosticEvents.value = emptyList()
    }

    private var activePreviewUseCase: Preview? = null
    private var currentSurfaceProvider: Preview.SurfaceProvider? = null

    fun registerPreviewUseCase(preview: Preview?) {
        activePreviewUseCase = preview
        currentSurfaceProvider?.let { preview?.setSurfaceProvider(it) }
    }

    fun setSurfaceProvider(provider: Preview.SurfaceProvider?) {
        currentSurfaceProvider = provider
        activePreviewUseCase?.setSurfaceProvider(provider)
    }
}
