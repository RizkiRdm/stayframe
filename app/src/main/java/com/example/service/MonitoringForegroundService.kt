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
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
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
import com.example.MainActivity
import com.example.data.FaceTemplateStore
import com.example.data.StayFramePreferencesRepository
import com.example.domain.biometrics.DescriptorExtractionResult
import com.example.domain.biometrics.FaceDescriptorExtractor
import com.example.domain.engine.FaceObservation
import com.example.domain.model.DiagnosticEvent
import com.example.domain.model.PresenceState
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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var cameraRetryCount = 0
    private val maxCameraRetries = 3
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile
    private var isCameraBound = false

    private val standbyReleaseRunnable = Runnable {
        releaseCameraForStandby()
    }

    private val faceDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
        FaceDetection.getClient(options)
    }

    companion object {
        const val CHANNEL_ID = "stayframe_monitoring_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.action.START_MONITORING"
        const val ACTION_STOP = "com.example.action.STOP_MONITORING"
        private const val STANDBY_CAMERA_RELEASE_DELAY_MS = 15_000L

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
        cameraExecutor = Executors.newSingleThreadExecutor()
        templateStore = FaceTemplateStore(applicationContext)
        preferencesRepository = StayFramePreferencesRepository(applicationContext)
        StayFrameServiceBridge.foregroundServiceInstance = this
        createNotificationChannel()
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
                return START_REDELIVER_INTENT
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        serviceScope.launch {
            val prefs = preferencesRepository.getSnapshot()
            preferencesRepository.setMonitoringArmed(true)
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
                    if (!isCameraBound && StayFrameServiceBridge.isMonitoring.value) {
                        bindCameraAnalysis()
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

        if (shouldStandby && isCameraBound) {
            try {
                cameraProvider?.unbindAll()
                isCameraBound = false
                StayFrameServiceBridge.addDiagnosticEvent(
                    DiagnosticEvent(
                        state = state,
                        eventName = "camera_standby_released",
                        details = "Camera hardware released in standby to save battery"
                    )
                )
            } catch (_: Exception) {}
        }
    }

    private fun stopMonitoringService() {
        lastNotifiedTitle = null
        lastNotifiedDetails = null
        mainHandler.removeCallbacksAndMessages(null)
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        isCameraBound = false
        StayFrameServiceBridge.setMonitoring(false)
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        cameraExecutor?.shutdown()
        try {
            faceDetector.close()
        } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        lastNotifiedTitle = null
        lastNotifiedDetails = null
        mainHandler.removeCallbacksAndMessages(null)
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        isCameraBound = false
        StayFrameServiceBridge.registerPreviewUseCase(null)
        if (StayFrameServiceBridge.foregroundServiceInstance == this) {
            StayFrameServiceBridge.foregroundServiceInstance = null
        }
        cameraExecutor?.shutdown()
        try {
            faceDetector.close()
        } catch (_: Exception) {}
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
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                val preview = Preview.Builder().build()
                StayFrameServiceBridge.registerPreviewUseCase(preview)

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor ?: return@addListener) { imageProxy ->
                    analyzeFrame(imageProxy)
                }

                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                isCameraBound = true
                cameraRetryCount = 0
            } catch (e: Exception) {
                isCameraBound = false
                handleCameraErrorWithRetry(e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleCameraErrorWithRetry(e: Exception) {
        val errorMsg = e.localizedMessage ?: e.message ?: "Camera binding failed"
        if (cameraRetryCount < maxCameraRetries) {
            cameraRetryCount++
            val backoffMs = (1000L * (1 shl (cameraRetryCount - 1)))
            StayFrameServiceBridge.addDiagnosticEvent(
                DiagnosticEvent(
                    state = StayFrameServiceBridge.presenceState.value,
                    eventName = "camera_retry",
                    details = "Camera binding failed ($errorMsg). Retrying attempt $cameraRetryCount of $maxCameraRetries in ${backoffMs / 1000}s..."
                )
            )
            updateNotification("Camera Retrying...", "Attempt $cameraRetryCount/$maxCameraRetries ($errorMsg)")
            mainHandler.postDelayed({
                if (StayFrameServiceBridge.isMonitoring.value) {
                    bindCameraAnalysis()
                }
            }, backoffMs)
        } else {
            val finalError = "Camera failed after $maxCameraRetries attempts: $errorMsg"
            StayFrameServiceBridge.engine.reportError(finalError)
            updateNotification("Camera Error", finalError)
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastAnalyzedTimestamp < 1000L) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        lastAnalyzedTimestamp = now
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        try {
            val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

            faceDetector.process(inputImage)
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

                    val enrolledTemplate = templateStore.getTemplate()
                    val similarity = if (faceDetected && enrolledTemplate != null) {
                        when (val extraction = FaceDescriptorExtractor.extractFromMlKitFace(faces[0])) {
                            is DescriptorExtractionResult.Success -> {
                                templateStore.computeCosineSimilarity(extraction.vector, enrolledTemplate)
                            }
                            is DescriptorExtractionResult.Rejected -> 0f
                        }
                    } else {
                        0f
                    }

                    val isEnrolledMatch = similarity >= FaceTemplateStore.MATCH_THRESHOLD

                    val observation = FaceObservation(
                        faceDetected = faceDetected,
                        identityMatchConfidence = similarity,
                        isEnrolledMatch = isEnrolledMatch,
                        faceBoundingBox = boundingBox,
                        lightingAdequate = true
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
