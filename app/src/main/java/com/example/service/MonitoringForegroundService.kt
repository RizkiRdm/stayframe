package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import com.example.MainActivity
import com.example.data.FaceTemplateStore
import com.example.data.StayFramePreferencesRepository
import com.example.domain.biometrics.DescriptorExtractionResult
import com.example.domain.biometrics.FaceDescriptorExtractor
import com.example.domain.controller.ScreenTimeoutController
import com.example.domain.engine.FaceObservation
import com.example.domain.model.DiagnosticEvent
import com.example.domain.model.PresenceState
import com.example.domain.model.ScreenTimeoutMode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground Service running CameraX ImageAnalysis at ~1 FPS using bundled ML Kit
 * Face Detection (landmarks + contours) for Relaxed and Strict Mode presence guard.
 * Supports standby camera hysteresis when user switches away from video players.
 */
class MonitoringForegroundService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private var cameraExecutor: ExecutorService? = null
    private var lastAnalyzedTimestamp: Long = 0L
    private lateinit var templateStore: FaceTemplateStore
    private lateinit var preferencesRepository: StayFramePreferencesRepository
    private lateinit var screenTimeoutController: ScreenTimeoutController
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedEnrolledTemplate: FloatArray? = null

    private var cameraRetryCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private var observedCamera: Camera? = null

    @Volatile
    private var isCameraBound = false

    @Volatile
    private var isInStandbyRelease = false

    @Volatile
    private var isIntentionalUnbind = false

    @Volatile
    private var isRetryScheduled = false

    @Volatile
    private var lastFrameReceivedElapsedMs: Long = 0L

    private val standbyReleaseRunnable = Runnable {
        releaseCameraForStandby()
    }

    private val cameraRetryRunnable = Runnable {
        isRetryScheduled = false
        if (StayFrameServiceBridge.isMonitoring.value && !isCameraBound && !isInStandbyRelease) {
            bindCameraAnalysis()
        }
    }

    private val frameWatchdogRunnable = object : Runnable {
        override fun run() {
            if (!isCameraBound || !StayFrameServiceBridge.isMonitoring.value || isInStandbyRelease) {
                return
            }
            val elapsedSinceLastFrameMs = SystemClock.elapsedRealtime() - lastFrameReceivedElapsedMs
            if (elapsedSinceLastFrameMs > FRAME_WATCHDOG_TIMEOUT_MS) {
                StayFrameServiceBridge.addDiagnosticEvent(
                    DiagnosticEvent(
                        state = StayFrameServiceBridge.presenceState.value,
                        eventName = "camera_frame_watchdog_timeout",
                        details = "No camera frames for ${elapsedSinceLastFrameMs}ms (> ${FRAME_WATCHDOG_TIMEOUT_MS}ms); entering UNKNOWN and rebinding"
                    )
                )
                StayFrameServiceBridge.reportCameraWatchdogTimeout(
                    "Camera frame stream stalled (${elapsedSinceLastFrameMs / 1000}s without frames)"
                )
                unbindCameraSafely()
                scheduleCameraRebindWithBackoff("No camera frames for >5s")
            } else {
                mainHandler.postDelayed(this, WATCHDOG_CHECK_INTERVAL_MS)
            }
        }
    }

    private val cameraStateObserver = Observer<CameraState> { state ->
        if (isIntentionalUnbind || !StayFrameServiceBridge.isMonitoring.value || isInStandbyRelease) {
            return@Observer
        }

        val stateError = state.error
        if (stateError != null) {
            val errorDesc = "CameraState ${state.type} error code=${stateError.code}"
            StayFrameServiceBridge.addDiagnosticEvent(
                DiagnosticEvent(
                    state = StayFrameServiceBridge.presenceState.value,
                    eventName = "camera_state_error",
                    details = errorDesc
                )
            )
            unbindCameraSafely()
            scheduleCameraRebindWithBackoff(errorDesc)
            return@Observer
        }

        when (state.type) {
            CameraState.Type.OPEN -> {
                cameraRetryCount = 0
                cancelScheduledRetry()
                lastFrameReceivedElapsedMs = SystemClock.elapsedRealtime()
                startFrameWatchdog()
                if (StayFrameServiceBridge.presenceState.value == PresenceState.ERROR) {
                    StayFrameServiceBridge.reportCameraRecovered("CameraX hardware state OPEN")
                }
                val current = StayFrameServiceBridge.presenceState.value
                updateNotification(current.title, current.description)
            }
            CameraState.Type.CLOSED -> {
                if (isCameraBound) {
                    StayFrameServiceBridge.addDiagnosticEvent(
                        DiagnosticEvent(
                            state = StayFrameServiceBridge.presenceState.value,
                            eventName = "camera_state_closed",
                            details = "Camera closed unexpectedly while monitoring; scheduling rebind"
                        )
                    )
                    unbindCameraSafely()
                    scheduleCameraRebindWithBackoff("Camera closed unexpectedly")
                }
            }
            else -> {
                // PENDING_OPEN / OPENING / CLOSING monitored by watchdog & state errors
            }
        }
    }

    private var faceDetector: FaceDetector? = null

    private fun createFaceDetector(): FaceDetector {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
        return FaceDetection.getClient(options)
    }

    private fun ensureProcessingResources() {
        val executor = cameraExecutor
        if (executor == null || executor.isShutdown || executor.isTerminated) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }
        if (faceDetector == null) {
            faceDetector = createFaceDetector()
        }
    }

    private fun releaseProcessingResources() {
        cameraExecutor?.shutdown()
        cameraExecutor = null
        try {
            faceDetector?.close()
        } catch (e: Exception) {
            StayFrameServiceBridge.recordRecoverableException(
                "face_detector_close_failed",
                "Failed to close ML Kit FaceDetector",
                e
            )
        } finally {
            faceDetector = null
        }
    }

    internal fun hasActiveProcessingResourcesForTest(): Boolean {
        val execActive = cameraExecutor?.let { !it.isShutdown && !it.isTerminated } == true
        return execActive && faceDetector != null
    }

    companion object {
        const val CHANNEL_ID = "stayframe_monitoring_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.action.START_MONITORING"
        const val ACTION_STOP = "com.example.action.STOP_MONITORING"
        private const val STANDBY_CAMERA_RELEASE_DELAY_MS = 15_000L
        internal const val FRAME_WATCHDOG_TIMEOUT_MS = 5_000L
        private const val WATCHDOG_CHECK_INTERVAL_MS = 1_000L
        internal const val BASE_RETRY_BACKOFF_MS = 1_000L
        internal const val MAX_RETRY_BACKOFF_MS = 30_000L
        internal const val ERROR_ESCALATION_RETRY_THRESHOLD = 3
        internal const val MIN_ADEQUATE_LUMA = 18f

        @Volatile
        internal var foregroundStarterOverride: ((Service, Int, Notification) -> Unit)? = null

        internal fun computeRebindBackoffMs(retryAttempt: Int): Long {
            val exponent = (retryAttempt - 1).coerceIn(0, 5)
            return (BASE_RETRY_BACKOFF_MS shl exponent).coerceAtMost(MAX_RETRY_BACKOFF_MS)
        }

        internal fun computeMeanLuminance(image: android.media.Image): Float {
            val planes = image.planes
            if (planes.isEmpty()) return 255f
            val yPlane = planes[0]
            return computeMeanLuminanceFromYPlane(
                buffer = yPlane.buffer,
                rowStride = yPlane.rowStride,
                pixelStride = yPlane.pixelStride,
                width = image.width,
                height = image.height
            )
        }

        internal fun computeMeanLuminanceFromYPlane(
            buffer: java.nio.ByteBuffer,
            rowStride: Int,
            pixelStride: Int,
            width: Int,
            height: Int
        ): Float {
            if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) return 255f
            val dup = buffer.duplicate().apply { rewind() }
            val step = 4
            var sum = 0L
            var count = 0L
            val capacity = dup.limit()
            var y = 0
            while (y < height) {
                val rowOffset = y * rowStride
                var x = 0
                while (x < width) {
                    val idx = rowOffset + x * pixelStride
                    if (idx < capacity) {
                        sum += (dup.get(idx).toInt() and 0xFF)
                        count++
                    }
                    x += step
                }
                y += step
            }
            return if (count > 0L) sum.toFloat() / count.toFloat() else 255f
        }

        fun start(context: Context) {
            val intent = Intent(context, MonitoringForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MonitoringForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        templateStore = FaceTemplateStore(applicationContext)
        preferencesRepository = StayFramePreferencesRepository(applicationContext)
        screenTimeoutController = ScreenTimeoutController(applicationContext)
        StayFrameServiceBridge.foregroundServiceInstance = this
        createNotificationChannel()
    }

    fun refreshCachedTemplateAsync() {
        serviceScope.launch {
            cachedEnrolledTemplate = templateStore.getTemplate()
        }
    }

    private var lastNotifiedTitle: String? = null
    private var lastNotifiedDetails: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.launch { preferencesRepository.setMonitoringArmed(false) }
                stopMonitoringService()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startMonitoringService()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startMonitoringService() {
        val initialTitle = PresenceState.WAITING_FOR_PLAYER.title
        val initialDetails = "Guard armed and observing active media"
        lastNotifiedTitle = initialTitle
        lastNotifiedDetails = initialDetails
        val notification = buildNotification(initialTitle, initialDetails)

        val foregroundStarted = try {
            val overrideStarter = foregroundStarterOverride
            if (overrideStarter != null) {
                overrideStarter.invoke(this, NOTIFICATION_ID, notification)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            StayFrameServiceBridge.recordRecoverableException(
                "fgs_start_failed",
                "Failed to start camera foreground service",
                e
            )
            StayFrameServiceBridge.reportForegroundStartFailure(
                "Unable to start camera guard in background (${e.javaClass.simpleName}). Tap notification or Start Monitoring in app."
            )
            postTapToResumeFallbackNotification()
            releaseProcessingResources()
            stopSelf()
            false
        }

        if (!foregroundStarted) {
            return
        }

        ensureProcessingResources()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        isInStandbyRelease = false
        cameraRetryCount = 0
        cancelScheduledRetry()

        serviceScope.launch {
            val prefs = preferencesRepository.getSnapshot()
            preferencesRepository.setMonitoringArmed(true)
            cachedEnrolledTemplate = templateStore.getTemplate()
            StayFrameServiceBridge.engine.setAbsenceGracePeriod(prefs.gracePeriodSeconds)
            StayFrameServiceBridge.engine.setDetectionMode(prefs.detectionMode)
            mainHandler.post {
                StayFrameServiceBridge.setMonitoring(true)
                StayFrameServiceBridge.listenerServiceInstance?.refreshActiveSessions()
                val state = StayFrameServiceBridge.presenceState.value
                updateNotification(state.title, state.description)
                onPresenceStateChanged(state, PresenceState.INACTIVE)
            }
        }

        bindCameraAnalysis()
    }

    private fun postTapToResumeFallbackNotification() {
        try {
            val resumeIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_RESUME_MONITORING, true)
            }
            val pending = PendingIntent.getActivity(
                this,
                3,
                resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val fallback = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("StayFrame Guard Paused")
                .setContentText("Tap to open StayFrame and resume camera monitoring.")
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID, fallback)
        } catch (e: Exception) {
            StayFrameServiceBridge.recordRecoverableException(
                "tap_to_resume_notification_failed",
                "Failed to post tap-to-resume fallback notification",
                e
            )
        }
    }

    fun onPresenceStateChanged(newState: PresenceState, previousState: PresenceState) {
        mainHandler.post {
            updateNotification(newState.title, newState.description)

            when (newState) {
                PresenceState.PLAYER_IN_BACKGROUND,
                PresenceState.WAITING_FOR_PLAYER,
                PresenceState.PLAYER_DETECTED,
                PresenceState.PLAYER_UNAVAILABLE -> {
                    // Keep monitoring service alive in standby and release camera after hysteresis
                    mainHandler.removeCallbacks(standbyReleaseRunnable)
                    mainHandler.postDelayed(standbyReleaseRunnable, STANDBY_CAMERA_RELEASE_DELAY_MS)
                }
                PresenceState.PRESENT,
                PresenceState.ABSENCE_GRACE,
                PresenceState.PAUSED_BY_GUARD,
                PresenceState.OTHER_FACE,
                PresenceState.UNKNOWN -> {
                    mainHandler.removeCallbacks(standbyReleaseRunnable)
                    val wakingFromStandby = isInStandbyRelease
                    isInStandbyRelease = false
                    if (!isCameraBound && StayFrameServiceBridge.isMonitoring.value) {
                        if (wakingFromStandby || !isRetryScheduled) {
                            cancelScheduledRetry()
                            bindCameraAnalysis()
                        }
                    }
                }
                else -> {}
            }
        }
    }

    private fun releaseCameraForStandby() {
        val state = StayFrameServiceBridge.presenceState.value
        val shouldStandby = state == PresenceState.PLAYER_IN_BACKGROUND ||
            state == PresenceState.WAITING_FOR_PLAYER ||
            state == PresenceState.PLAYER_DETECTED ||
            state == PresenceState.PLAYER_UNAVAILABLE

        if (shouldStandby) {
            isInStandbyRelease = true
            cameraRetryCount = 0
            cancelScheduledRetry()
            stopFrameWatchdog()
            if (isCameraBound || observedCamera != null) {
                unbindCameraSafely()
                StayFrameServiceBridge.addDiagnosticEvent(
                    DiagnosticEvent(
                        state = state,
                        eventName = "camera_standby_released",
                        details = "Camera hardware released in standby to save battery"
                    )
                )
            }
        }
    }

    private fun stopMonitoringService() {
        lastNotifiedTitle = null
        lastNotifiedDetails = null
        isInStandbyRelease = false
        cancelScheduledRetry()
        stopFrameWatchdog()
        mainHandler.removeCallbacksAndMessages(null)
        unbindCameraSafely()
        if (::screenTimeoutController.isInitialized) {
            screenTimeoutController.restoreTimeout()
            StayFrameServiceBridge.setScreenTimeoutMode(ScreenTimeoutMode.OFF)
        }
        StayFrameServiceBridge.setMonitoring(false)
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        releaseProcessingResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        lastNotifiedTitle = null
        lastNotifiedDetails = null
        isInStandbyRelease = false
        cancelScheduledRetry()
        stopFrameWatchdog()
        mainHandler.removeCallbacksAndMessages(null)
        unbindCameraSafely()
        if (::screenTimeoutController.isInitialized) {
            screenTimeoutController.restoreTimeout()
            StayFrameServiceBridge.setScreenTimeoutMode(ScreenTimeoutMode.OFF)
        }
        StayFrameServiceBridge.registerPreviewUseCase(null)
        if (StayFrameServiceBridge.foregroundServiceInstance == this) {
            StayFrameServiceBridge.foregroundServiceInstance = null
        }
        releaseProcessingResources()
        serviceScope.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "StayFrame Presence Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows ongoing video playback presence guard status"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    fun updateNotification(stateTitle: String, details: String) {
        if (stateTitle == lastNotifiedTitle && details == lastNotifiedDetails) {
            return
        }
        lastNotifiedTitle = stateTitle
        lastNotifiedDetails = details
        val notification = buildNotification(stateTitle, details)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(statusTitle: String, details: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MonitoringForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("StayFrame: $statusTitle")
            .setContentText(details)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Monitoring", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun bindCameraAnalysis() {
        if (isInStandbyRelease || lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
            return
        }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            if (isInStandbyRelease ||
                lifecycleRegistry.currentState == Lifecycle.State.DESTROYED
            ) {
                return@addListener
            }
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                val preview = Preview.Builder().build()
                StayFrameServiceBridge.registerPreviewUseCase(preview)

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val executor = cameraExecutor ?: return@addListener
                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    analyzeFrame(imageProxy)
                }

                detachCameraStateObserver()
                isIntentionalUnbind = true
                try {
                    provider.unbindAll()
                } finally {
                    isIntentionalUnbind = false
                }

                val boundCamera = provider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                observedCamera = boundCamera
                isCameraBound = true
                lastFrameReceivedElapsedMs = SystemClock.elapsedRealtime()
                boundCamera.cameraInfo.cameraState.observeForever(cameraStateObserver)
                startFrameWatchdog()
            } catch (e: Exception) {
                isCameraBound = false
                stopFrameWatchdog()
                scheduleCameraRebindWithBackoff(e.localizedMessage ?: e.message ?: "Camera binding failed")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun detachCameraStateObserver() {
        observedCamera?.cameraInfo?.cameraState?.removeObserver(cameraStateObserver)
        observedCamera = null
    }

    private fun unbindCameraSafely() {
        stopFrameWatchdog()
        isCameraBound = false
        isIntentionalUnbind = true
        try {
            detachCameraStateObserver()
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            StayFrameServiceBridge.recordRecoverableException(
                "camera_unbind_failed",
                "Failed to unbind camera use cases cleanly",
                e
            )
        } finally {
            isIntentionalUnbind = false
        }
    }

    private fun startFrameWatchdog() {
        mainHandler.removeCallbacks(frameWatchdogRunnable)
        if (isCameraBound && StayFrameServiceBridge.isMonitoring.value && !isInStandbyRelease) {
            mainHandler.postDelayed(frameWatchdogRunnable, WATCHDOG_CHECK_INTERVAL_MS)
        }
    }

    private fun stopFrameWatchdog() {
        mainHandler.removeCallbacks(frameWatchdogRunnable)
    }

    private fun cancelScheduledRetry() {
        isRetryScheduled = false
        mainHandler.removeCallbacks(cameraRetryRunnable)
    }

    private fun scheduleCameraRebindWithBackoff(reason: String) {
        if (!StayFrameServiceBridge.isMonitoring.value ||
            isInStandbyRelease ||
            lifecycleRegistry.currentState == Lifecycle.State.DESTROYED
        ) {
            return
        }
        if (isRetryScheduled) {
            return
        }

        cameraRetryCount++
        val backoffMs = computeRebindBackoffMs(cameraRetryCount)

        if (cameraRetryCount >= ERROR_ESCALATION_RETRY_THRESHOLD) {
            val errorMsg = "Camera unavailable (attempt $cameraRetryCount): $reason"
            StayFrameServiceBridge.reportCameraError(errorMsg)
            updateNotification(
                "Camera Error (Retrying)",
                "Attempt $cameraRetryCount • retrying in ${backoffMs / 1000}s"
            )
        } else {
            StayFrameServiceBridge.addDiagnosticEvent(
                DiagnosticEvent(
                    state = StayFrameServiceBridge.presenceState.value,
                    eventName = "camera_retry",
                    details = "$reason. Retrying attempt $cameraRetryCount in ${backoffMs / 1000}s..."
                )
            )
            updateNotification(
                "Camera Retrying...",
                "Attempt $cameraRetryCount • retry in ${backoffMs / 1000}s"
            )
        }

        isRetryScheduled = true
        mainHandler.postDelayed(cameraRetryRunnable, backoffMs)
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        // Touch watchdog timestamp on every delivered frame before ML Kit 1 FPS throttle
        lastFrameReceivedElapsedMs = SystemClock.elapsedRealtime()
        if (cameraRetryCount > 0 || isRetryScheduled) {
            mainHandler.post {
                cameraRetryCount = 0
                cancelScheduledRetry()
            }
        }

        val now = System.currentTimeMillis()
        if (now - lastAnalyzedTimestamp < 1000L) {
            imageProxy.close()
            return
        }

        lastAnalyzedTimestamp = now
        val meanLuma = computeMeanLuminance(mediaImage)
        val lightingAdequate = meanLuma >= MIN_ADEQUATE_LUMA
        if (!lightingAdequate) {
            StayFrameServiceBridge.onFaceObserved(
                FaceObservation(
                    faceDetected = false,
                    identityMatchConfidence = 0f,
                    isEnrolledMatch = false,
                    faceBoundingBox = null,
                    lightingAdequate = false,
                    identityUncertain = true
                )
            )
            val currentState = StayFrameServiceBridge.presenceState.value
            updateNotification(currentState.title, currentState.description)
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val detector = faceDetector ?: run {
            imageProxy.close()
            return
        }

        try {
            val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

            detector.process(inputImage)
                .addOnSuccessListener { faces ->
                    val faceDetected = faces.isNotEmpty()

                    val boundingBox = if (faceDetected) {
                        val bounds = faces[0].boundingBox
                        val imgW = inputImage.width.toFloat().coerceAtLeast(1f)
                        val imgH = inputImage.height.toFloat().coerceAtLeast(1f)
                        listOf(
                            (bounds.left.toFloat() / imgW).coerceIn(0f, 1f),
                            (bounds.top.toFloat() / imgH).coerceIn(0f, 1f),
                            (bounds.right.toFloat() / imgW).coerceIn(0f, 1f),
                            (bounds.bottom.toFloat() / imgH).coerceIn(0f, 1f)
                        )
                    } else {
                        null
                    }

                    val enrolledTemplate = cachedEnrolledTemplate
                    var similarity = 0f
                    var identityUncertain = false
                    if (faceDetected) {
                        if (enrolledTemplate == null) {
                            identityUncertain = true
                        } else {
                            when (val extraction = FaceDescriptorExtractor.extractFromMlKitFace(faces[0])) {
                                is DescriptorExtractionResult.Success -> {
                                    similarity = templateStore.computeCosineSimilarity(extraction.vector, enrolledTemplate)
                                }
                                is DescriptorExtractionResult.Rejected -> {
                                    identityUncertain = true
                                }
                            }
                        }
                    }

                    val isEnrolledMatch = !identityUncertain && similarity >= FaceTemplateStore.MATCH_THRESHOLD

                    val observation = FaceObservation(
                        faceDetected = faceDetected,
                        identityMatchConfidence = similarity,
                        isEnrolledMatch = isEnrolledMatch,
                        faceBoundingBox = boundingBox,
                        lightingAdequate = lightingAdequate,
                        identityUncertain = identityUncertain
                    )

                    StayFrameServiceBridge.onFaceObserved(observation)

                    val currentState = StayFrameServiceBridge.presenceState.value
                    updateNotification(currentState.title, currentState.description)
                }
                .addOnFailureListener { e ->
                    StayFrameServiceBridge.addDiagnosticEvent(
                        DiagnosticEvent(
                            state = StayFrameServiceBridge.presenceState.value,
                            eventName = "face_detection_error",
                            details = "ML Kit Face Detection error: ${e.message ?: "Unknown"}"
                        )
                    )
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } catch (e: Exception) {
            StayFrameServiceBridge.addDiagnosticEvent(
                DiagnosticEvent(
                    state = StayFrameServiceBridge.presenceState.value,
                    eventName = "input_image_error",
                    details = "Failed to process InputImage: ${e.message ?: "Unknown"}"
                )
            )
            imageProxy.close()
        }
    }
}
