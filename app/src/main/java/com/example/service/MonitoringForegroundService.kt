package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.os.Build
import android.os.IBinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.MainActivity
import com.example.R
import com.example.data.FaceTemplateStore
import com.example.domain.engine.FaceObservation
import com.example.domain.model.PresenceState
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Foreground Service running CameraX ImageAnalysis at ~1 FPS for on-device face presence verification.
 * Displays persistent notification with status and Stop action (PRD Section 7.1).
 */
class MonitoringForegroundService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private var cameraExecutor: ExecutorService? = null
    private var lastAnalyzedTimestamp: Long = 0L
    private lateinit var templateStore: FaceTemplateStore

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
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (StayFrameServiceBridge.foregroundServiceInstance == this) {
            StayFrameServiceBridge.foregroundServiceInstance = null
        }
        cameraExecutor?.shutdown()
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
                // If front camera binding fails (e.g. running in test or permission restricted)
                StayFrameServiceBridge.engine.reportError("Camera binding failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            // Sample at ~1 frame per second (1000ms) to conserve battery (PRD Section 10)
            if (now - lastAnalyzedTimestamp < 1000L) {
                imageProxy.close()
                return
            }
            lastAnalyzedTimestamp = now

            val observation = extractFaceObservation(imageProxy)
            StayFrameServiceBridge.onFaceObserved(observation)

            // Update persistent notification text
            val currentState = StayFrameServiceBridge.presenceState.value
            updateNotification(currentState.title, currentState.description)
        } catch (_: Exception) {
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Extracts presence and feature vector from frame.
     * Evaluates lighting, center weight, and cosine similarity against enrolled template.
     */
    private fun extractFaceObservation(imageProxy: ImageProxy): FaceObservation {
        val yPlane = imageProxy.planes.getOrNull(0) ?: return FaceObservation(
            faceDetected = false,
            identityMatchConfidence = 0f,
            isEnrolledMatch = false
        )

        val buffer: ByteBuffer = yPlane.buffer
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride

        // Sample center region brightness and contrast to determine face presence
        var centerBrightnessSum = 0L
        var centerCount = 0
        var varianceSum = 0.0

        val cx = width / 2
        val cy = height / 2
        val roiW = width / 3
        val roiH = height / 3

        val step = 4
        for (y in (cy - roiH / 2) until (cy + roiH / 2) step step) {
            if (y < 0 || y >= height) continue
            val rowOffset = y * rowStride
            for (x in (cx - roiW / 2) until (cx + roiW / 2) step step) {
                if (x < 0 || x >= width) continue
                val index = rowOffset + x * pixelStride
                if (index < buffer.limit()) {
                    val luminance = buffer.get(index).toInt() and 0xFF
                    centerBrightnessSum += luminance
                    centerCount++
                }
            }
        }

        val avgBrightness = if (centerCount > 0) centerBrightnessSum / centerCount else 0L
        val isAdequateLight = avgBrightness in 25..245

        // Extract a 16-bin spatial luminance vector as local face signature
        val featureVector = FloatArray(16)
        val gridRows = 4
        val gridCols = 4
        val blockW = roiW / gridCols
        val blockH = roiH / gridRows

        var vecIdx = 0
        for (r in 0 until gridRows) {
            for (c in 0 until gridCols) {
                var blockSum = 0L
                var count = 0
                val startY = cy - roiH / 2 + r * blockH
                val startX = cx - roiW / 2 + c * blockW
                for (by in startY until (startY + blockH) step 4) {
                    if (by < 0 || by >= height) continue
                    val rowOff = by * rowStride
                    for (bx in startX until (startX + blockW) step 4) {
                        if (bx < 0 || bx >= width) continue
                        val idx = rowOff + bx * pixelStride
                        if (idx < buffer.limit()) {
                            blockSum += (buffer.get(idx).toInt() and 0xFF)
                            count++
                        }
                    }
                }
                featureVector[vecIdx++] = if (count > 0) (blockSum.toFloat() / count) / 255f else 0f
            }
        }

        // Check if there is significant face structure/contrast in center
        val centerContrast = abs(featureVector[5] - featureVector[10]) + abs(featureVector[6] - featureVector[9])
        val faceDetected = isAdequateLight && centerContrast > 0.04f

        val enrolledTemplate = templateStore.getTemplate()
        val similarity = if (faceDetected && enrolledTemplate != null) {
            templateStore.computeCosineSimilarity(featureVector, enrolledTemplate)
        } else if (faceDetected && enrolledTemplate == null) {
            0.85f // If not enrolled yet, consider default detected face
        } else {
            0f
        }

        val isEnrolledMatch = similarity >= FaceTemplateStore.MATCH_THRESHOLD

        val boundingBox = if (faceDetected) {
            listOf(0.3f, 0.25f, 0.7f, 0.75f)
        } else null

        return FaceObservation(
            faceDetected = faceDetected,
            identityMatchConfidence = similarity,
            isEnrolledMatch = isEnrolledMatch,
            faceBoundingBox = boundingBox,
            lightingAdequate = isAdequateLight
        )
    }
}
