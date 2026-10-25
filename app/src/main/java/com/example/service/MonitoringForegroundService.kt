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
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.MainActivity
import com.example.data.FaceTemplateStore
import com.example.domain.engine.FaceObservation
import com.example.domain.model.DiagnosticEvent
import com.example.domain.model.PresenceState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground Service running CameraX ImageAnalysis at ~1 FPS using ML Kit Face Detection
 * (bundled, offline on-device) for presence verification.
 * Displays persistent notification with status and Stop action (PRD Section 7.1).
 */
class MonitoringForegroundService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private var cameraExecutor: ExecutorService? = null
    private var lastAnalyzedTimestamp: Long = 0L
    private lateinit var templateStore: FaceTemplateStore

    private val faceDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.15f)
            .build()
        FaceDetection.getClient(options)
    }

    companion object {
        const val CHANNEL_ID = "stayframe_monitoring_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.action.START_MONITORING"
        const val ACTION_STOP = "com.example.action.STOP_MONITORING"

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
        StayFrameServiceBridge.foregroundServiceInstance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoringService()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startMonitoringService()
            }
        }
        return START_STICKY
    }

    private fun startMonitoringService() {
        val notification = buildNotification(PresenceState.PRESENT.title, "Presence monitoring active")
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

        StayFrameServiceBridge.setMonitoring(true)
        bindCameraAnalysis()
    }

    private fun stopMonitoringService() {
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
        if (StayFrameServiceBridge.foregroundServiceInstance == this) {
            StayFrameServiceBridge.foregroundServiceInstance = null
        }
        cameraExecutor?.shutdown()
        try {
            faceDetector.close()
        } catch (_: Exception) {}
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
                val cameraProvider = cameraProviderFuture.get()
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor ?: return@addListener) { imageProxy ->
                    analyzeFrame(imageProxy)
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)
            } catch (e: Exception) {
                StayFrameServiceBridge.engine.reportError("Camera binding failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        // Sample at ~1 frame per second (1000ms) to conserve battery (PRD Section 10)
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
                        val imgW = inputImage.width.toFloat()
                        val imgH = inputImage.height.toFloat()
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
                        val face = faces[0]
                        val imgW = inputImage.width.toFloat()
                        val imgH = inputImage.height.toFloat()
                        val normW = face.boundingBox.width().toFloat() / imgW
                        val normH = face.boundingBox.height().toFloat() / imgH
                        val normAspect = if (normH > 0f) normW / normH else 1f
                        val headEulerY = (face.headEulerAngleY + 180f) / 360f
                        val headEulerZ = (face.headEulerAngleZ + 180f) / 360f
                        val vector = FloatArray(16) { idx ->
                            when (idx) {
                                0 -> normW
                                1 -> normH
                                2 -> normAspect
                                3 -> headEulerY
                                4 -> headEulerZ
                                else -> enrolledTemplate.getOrElse(idx) { 0.5f }
                            }
                        }
                        templateStore.computeCosineSimilarity(vector, enrolledTemplate)
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
                    // imageProxy.close() MUST be inside the detector's completion listener
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
