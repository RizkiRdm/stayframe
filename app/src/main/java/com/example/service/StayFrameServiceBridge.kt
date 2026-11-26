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
import com.example.domain.model.SyncSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Global singleton bridge coordinating Foreground Service, NotificationListener,
 * Adaptive Timing, and the Presentation Layer ViewModel.
 */
object StayFrameServiceBridge {

    val engine: PresenceStateEngine = PresenceStateEngine(
        absenceGracePeriodSeconds = 5,
        detectionMode = DetectionMode.RELAXED_FALLBACK,
        callback = object : PresenceEngineCallback {
            override fun onStateChanged(newState: PresenceState, previousState: PresenceState) {
                _presenceState.value = newState
                _effectiveGraceSeconds.value = engine.effectiveGracePeriodSeconds
                _activeSyncSource.value = engine.activeSyncSource
                foregroundServiceInstance?.onPresenceStateChanged(newState, previousState)
            }

            override fun onSendPauseCommand(targetPackage: String, currentPositionMs: Long): Boolean {
                val listener = listenerServiceInstance
                if (listener == null) {
                    reportNullListenerError("pause")
                    return false
                }
                return listener.sendPauseCommand(targetPackage)
            }

            override fun onSendRewindCommand(targetPackage: String, targetPositionMs: Long, rewindOffsetMs: Long): Boolean {
                val listener = listenerServiceInstance
                if (listener == null) {
                    reportNullListenerError("rewind")
                    return false
                }
                val sent = listener.sendRewindCommand(targetPackage, targetPositionMs)
                if (sent) {
                    _lastRewindOffsetMs.value = rewindOffsetMs
                }
                return sent
            }

            override fun onSendPlayCommand(targetPackage: String): Boolean {
                val listener = listenerServiceInstance
                if (listener == null) {
                    reportNullListenerError("play")
                    return false
                }
                return listener.sendPlayCommand(targetPackage)
            }

            override fun onDiagnosticLog(event: DiagnosticEvent) {
                addDiagnosticEvent(event)
            }
        }
    )

    private fun reportNullListenerError(commandName: String) {
        val msg = "Notification Listener is disconnected; unable to send $commandName command. Re-enable Notification Access in Settings."
        engine.reportError(msg)
        _lastErrorMessage.value = engine.lastErrorMessage
        _presenceState.value = engine.currentState
    }

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

    private val _effectiveGraceSeconds = MutableStateFlow(5)
    val effectiveGraceSeconds: StateFlow<Int> = _effectiveGraceSeconds.asStateFlow()

    private val _activeSyncSource = MutableStateFlow(SyncSource.APP_GLOBAL_FALLBACK)
    val activeSyncSource: StateFlow<SyncSource> = _activeSyncSource.asStateFlow()

    private val _sustainedPresenceProgress = MutableStateFlow(0f)
    val sustainedPresenceProgress: StateFlow<Float> = _sustainedPresenceProgress.asStateFlow()

    private val _screenTimeoutMode = MutableStateFlow(ScreenTimeoutMode.OFF)
    val screenTimeoutMode: StateFlow<ScreenTimeoutMode> = _screenTimeoutMode.asStateFlow()

    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage: StateFlow<String?> = _lastErrorMessage.asStateFlow()

    var listenerServiceInstance: StayFrameNotificationListenerService? = null
        set(value) {
            field = value
            _listenerConnected.value = value != null
        }

    var foregroundServiceInstance: MonitoringForegroundService? = null

    fun notifyTemplateChanged() {
        foregroundServiceInstance?.refreshCachedTemplateAsync()
    }

    fun setMonitoring(active: Boolean) {
        _isMonitoring.value = active
        if (active) {
            engine.startMonitoring()
        } else {
            engine.stopMonitoring()
        }
        _lastErrorMessage.value = engine.lastErrorMessage
        _presenceState.value = engine.currentState
        _effectiveGraceSeconds.value = engine.effectiveGracePeriodSeconds
        _remainingGraceSeconds.value = engine.getRemainingGraceSeconds()
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
        _presenceState.value = engine.currentState
        _effectiveGraceSeconds.value = engine.effectiveGracePeriodSeconds
        _activeSyncSource.value = engine.activeSyncSource
        _remainingGraceSeconds.value = engine.getRemainingGraceSeconds()
    }

    fun onFaceObserved(observation: FaceObservation) {
        _lastObservation.value = observation
        engine.processObservation(observation)
        _lastErrorMessage.value = engine.lastErrorMessage
        _presenceState.value = engine.currentState
        _remainingGraceSeconds.value = engine.getRemainingGraceSeconds()
        _sustainedPresenceProgress.value = engine.getSustainedPresenceProgress()
    }

    fun reportCameraWatchdogTimeout(reason: String = "No camera frames received for >5s") {
        engine.handleCameraWatchdogTimeout(reason = reason)
        _lastErrorMessage.value = engine.lastErrorMessage
        _presenceState.value = engine.currentState
        _remainingGraceSeconds.value = engine.getRemainingGraceSeconds()
    }

    fun reportCameraError(errorMessage: String) {
        engine.reportError(errorMessage)
        _lastErrorMessage.value = engine.lastErrorMessage
        _presenceState.value = engine.currentState
    }

    fun reportCameraRecovered(reason: String = "Camera hardware recovered") {
        engine.recoverFromError(reason)
        _lastErrorMessage.value = engine.lastErrorMessage
        _presenceState.value = engine.currentState
        _remainingGraceSeconds.value = engine.getRemainingGraceSeconds()
    }

    fun reportListenerDisconnectedError(errorMessage: String) {
        engine.reportError(errorMessage)
        _lastErrorMessage.value = engine.lastErrorMessage
        _presenceState.value = engine.currentState
    }

    fun reportListenerReconnected() {
        if (engine.currentState == PresenceState.ERROR &&
            engine.lastErrorMessage?.contains("Notification Listener") == true
        ) {
            engine.recoverFromError("Notification Listener reconnected")
            _lastErrorMessage.value = engine.lastErrorMessage
            _presenceState.value = engine.currentState
            _remainingGraceSeconds.value = engine.getRemainingGraceSeconds()
        }
    }

    fun reportForegroundStartFailure(errorMessage: String) {
        _isMonitoring.value = false
        engine.reportError(errorMessage)
        _lastErrorMessage.value = engine.lastErrorMessage
        _presenceState.value = engine.currentState
    }

    fun recordRecoverableException(eventName: String, contextMessage: String, error: Throwable) {
        val detail = "$contextMessage (${error.javaClass.simpleName}: ${error.message ?: "unknown"})"
        try {
            android.util.Log.w("StayFrame", detail, error)
        } catch (_: Throwable) {
            // Pure JVM unit tests without Android Log mock
        }
        addDiagnosticEvent(
            DiagnosticEvent(
                state = _presenceState.value,
                eventName = eventName,
                details = detail
            )
        )
    }

    fun addDiagnosticEvent(event: DiagnosticEvent) {
        _diagnosticEvents.update { current ->
            val next = ArrayList<DiagnosticEvent>(minOf(current.size + 1, 100))
            next.add(event)
            for (i in 0 until minOf(current.size, 99)) {
                next.add(current[i])
            }
            next
        }
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
