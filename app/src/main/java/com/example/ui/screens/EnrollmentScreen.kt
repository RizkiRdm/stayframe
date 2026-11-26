package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.domain.biometrics.DescriptorExtractionResult
import com.example.domain.biometrics.FaceDescriptorExtractor
import com.example.ui.StayFrameViewModel
import com.example.ui.components.SleeveAnnouncementPill
import com.example.ui.components.SleeveBadge
import com.example.ui.components.SleeveCard
import com.example.ui.components.SleeveEyebrow
import com.example.ui.components.SleevePillButton
import com.example.ui.theme.OceanDrift
import com.example.ui.theme.PureWhite
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private val enrollmentStepPrompts = listOf(
    "Step 1 of 3: Look straight at the camera",
    "Step 2 of 3: Turn head slightly left",
    "Step 3 of 3: Turn head slightly right"
)

@Composable
fun EnrollmentScreen(
    viewModel: StayFrameViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onNavigateBack() }

    val context = LocalContext.current
    val isEnrolled by viewModel.isEnrolled.collectAsState()
    val enrolledUserName by viewModel.enrolledUserName.collectAsState()
    val enrolledTimestamp by viewModel.enrolledTimestamp.collectAsState()
    val cameraGranted by viewModel.cameraPermissionGranted.collectAsState()
    val isMonitoring by viewModel.isMonitoring.collectAsState()

    var isCapturingNewProfile by remember(isEnrolled) { mutableStateOf(!isEnrolled) }
    val capturedSamples = remember { mutableStateListOf<FloatArray>() }
    var latestValidDescriptor by remember { mutableStateOf<FloatArray?>(null) }
    var liveFeedback by remember { mutableStateOf("Position your face inside the frame") }
    var isFrameReady by remember { mutableStateOf(false) }
    var enrollmentError by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("enrollment_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        SleeveEyebrow(text = "STRICT MODE IDENTITY")
                        Text(
                            text = "Face Profile Enrollment",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (isEnrolled && !isCapturingNewProfile) {
                item {
                    EnrolledSummaryCard(
                        userName = enrolledUserName,
                        enrolledTimestamp = enrolledTimestamp,
                        onReEnroll = {
                            capturedSamples.clear()
                            latestValidDescriptor = null
                            enrollmentError = null
                            isCapturingNewProfile = true
                        },
                        onDelete = {
                            viewModel.deleteEnrollment()
                            capturedSamples.clear()
                            isCapturingNewProfile = true
                        },
                        onBack = onNavigateBack
                    )
                }
            } else {
                item {
                    SleeveCard(modifier = Modifier.fillMaxWidth(), padding = 20.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SleeveEyebrow(text = "ON-DEVICE GEOMETRY CAPTURE")
                            SleeveBadge(
                                text = "${capturedSamples.size} / 3 POSES",
                                backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                                textColor = OceanDrift
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        val stepTitle = enrollmentStepPrompts.getOrElse(capturedSamples.size) {
                            "Finalizing profile..."
                        }
                        Text(
                            text = stepTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = { capturedSamples.size / 3f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(9999.dp)),
                            color = OceanDrift,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        if (!cameraGranted) {
                            SleeveAnnouncementPill(
                                text = "Camera permission is required to capture your local facial geometry profile.",
                                icon = Icons.Default.Warning
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            SleevePillButton(
                                text = "Open App Permissions",
                                onClick = {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                },
                                isPrimary = true,
                                modifier = Modifier.fillMaxWidth(),
                                testTag = "enrollment_open_settings"
                            )
                        } else if (isMonitoring) {
                            SleeveAnnouncementPill(
                                text = "Pause background monitoring briefly so enrollment can access the front camera.",
                                icon = Icons.Default.CameraAlt
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            SleevePillButton(
                                text = "Pause Monitoring for Capture",
                                onClick = { viewModel.stopMonitoring() },
                                isPrimary = true,
                                modifier = Modifier.fillMaxWidth(),
                                testTag = "enrollment_pause_monitoring"
                            )
                        } else {
                            EnrollmentCameraPreview(
                                onDescriptorEvaluated = { result ->
                                    when (result) {
                                        is DescriptorExtractionResult.Success -> {
                                            latestValidDescriptor = result.vector
                                            isFrameReady = true
                                            liveFeedback = "${result.qualityHint} — tap Capture Pose"
                                        }
                                        is DescriptorExtractionResult.Rejected -> {
                                            latestValidDescriptor = null
                                            isFrameReady = false
                                            liveFeedback = result.reason
                                        }
                                    }
                                },
                                onCameraFailure = { err ->
                                    enrollmentError = err
                                }
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = enrollmentError ?: liveFeedback,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when {
                                        enrollmentError != null -> StatusRed
                                        isFrameReady -> StatusGreen
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = if (isFrameReady) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            SleevePillButton(
                                text = if (capturedSamples.size < 2) "Capture Pose (${capturedSamples.size + 1}/3)" else "Complete & Enable Strict Mode",
                                onClick = {
                                    val sample = latestValidDescriptor ?: return@SleevePillButton
                                    capturedSamples.add(sample)
                                    if (capturedSamples.size >= 3) {
                                        val averaged = FaceDescriptorExtractor.averageAndNormalize(capturedSamples.toList())
                                        if (averaged != null && viewModel.enrollFace("Primary Viewer", averaged, activateStrictMode = true)) {
                                            isCapturingNewProfile = false
                                            enrollmentError = null
                                        } else {
                                            enrollmentError = "Failed to encrypt facial profile in Android Keystore."
                                            capturedSamples.clear()
                                        }
                                    }
                                },
                                isPrimary = true,
                                enabled = isFrameReady,
                                icon = Icons.Default.CameraAlt,
                                modifier = Modifier.fillMaxWidth(),
                                testTag = "enrollment_capture_button"
                            )
                        }

                        if (isEnrolled && isCapturingNewProfile) {
                            Spacer(modifier = Modifier.height(8.dp))
                            SleevePillButton(
                                text = "Cancel Re-enrollment",
                                onClick = {
                                    capturedSamples.clear()
                                    isCapturingNewProfile = false
                                },
                                isPrimary = false,
                                modifier = Modifier.fillMaxWidth(),
                                testTag = "enrollment_cancel_button"
                            )
                        }
                    }
                }
            }

            // Honest Local Privacy & Scope Disclosure
            item {
                SleeveCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                    padding = 16.dp
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = OceanDrift,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Stored Encrypted Locally: Only a 24-point normalized facial geometry descriptor is encrypted with Android Keystore AES-256-GCM. Camera frames never leave volatile memory.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = StatusAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Scope Notice: 2D camera landmark matching is a playback viewer filter and does not use hardware depth sensors for anti-spoofing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun EnrolledSummaryCard(
    userName: String,
    enrolledTimestamp: Long,
    onReEnroll: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    val dateText = if (enrolledTimestamp > 0L) {
        SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault()).format(Date(enrolledTimestamp))
    } else {
        "Active"
    }

    SleeveCard(modifier = Modifier.fillMaxWidth(), padding = 24.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.5.dp, StatusGreen, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Face,
                    contentDescription = null,
                    tint = StatusGreen,
                    modifier = Modifier.size(30.dp)
                )
            }
            SleeveBadge(
                text = "STRICT READY",
                backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                textColor = StatusGreen,
                borderColor = StatusGreen,
                icon = Icons.Default.CheckCircle
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Enrolled Viewer: $userName",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Registered locally ($dateText)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        SleevePillButton(
            text = "Re-enroll Facial Geometry",
            onClick = onReEnroll,
            isPrimary = false,
            icon = Icons.Default.Refresh,
            modifier = Modifier.fillMaxWidth(),
            testTag = "enrollment_reenroll_button"
        )
        Spacer(modifier = Modifier.height(10.dp))
        SleevePillButton(
            text = "Delete Enrolled Profile",
            onClick = onDelete,
            isPrimary = false,
            icon = Icons.Default.Delete,
            modifier = Modifier.fillMaxWidth(),
            testTag = "enrollment_delete_button"
        )
        Spacer(modifier = Modifier.height(10.dp))
        SleevePillButton(
            text = "Return to Guard",
            onClick = onBack,
            isPrimary = true,
            modifier = Modifier.fillMaxWidth(),
            testTag = "return_dashboard_button"
        )
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun EnrollmentCameraPreview(
    onDescriptorEvaluated: (DescriptorExtractionResult) -> Unit,
    onCameraFailure: (String) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val faceDetector = remember {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setMinFaceSize(0.20f)
                .build()
        )
    }
    var boundProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                boundProvider?.unbindAll()
            } catch (e: Exception) {
                com.example.service.StayFrameServiceBridge.recordRecoverableException(
                    "enrollment_camera_unbind_failed",
                    "Failed to unbind enrollment camera preview",
                    e
                )
            }
            try {
                faceDetector.close()
            } catch (e: Exception) {
                com.example.service.StayFrameServiceBridge.recordRecoverableException(
                    "enrollment_detector_close_failed",
                    "Failed to close enrollment FaceDetector",
                    e
                )
            }
            cameraExecutor.shutdown()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .heightIn(min = 200.dp, max = 320.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0F172A))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    try {
                        val provider = cameraProviderFuture.get()
                        boundProvider = provider

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        var lastAnalyzedMs = 0L
                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            val now = System.currentTimeMillis()
                            if (now - lastAnalyzedMs < 350L) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            val mediaImage = imageProxy.image
                            if (mediaImage == null) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            lastAnalyzedMs = now
                            try {
                                val inputImage = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees
                                )
                                faceDetector.process(inputImage)
                                    .addOnSuccessListener { faces ->
                                        if (faces.isEmpty()) {
                                            onDescriptorEvaluated(
                                                DescriptorExtractionResult.Rejected("No face detected in frame")
                                            )
                                        } else {
                                            onDescriptorEvaluated(
                                                FaceDescriptorExtractor.extractFromMlKitFace(faces[0])
                                            )
                                        }
                                    }
                                    .addOnFailureListener { e ->
                                        onDescriptorEvaluated(
                                            DescriptorExtractionResult.Rejected(e.localizedMessage ?: "Detector error")
                                        )
                                    }
                                    .addOnCompleteListener {
                                        imageProxy.close()
                                    }
                            } catch (e: Exception) {
                                com.example.service.StayFrameServiceBridge.recordRecoverableException(
                                    "enrollment_frame_error",
                                    "Failed to process enrollment camera frame",
                                    e
                                )
                                imageProxy.close()
                            }
                        }

                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview,
                            analysis
                        )
                    } catch (e: Exception) {
                        onCameraFailure(e.localizedMessage ?: "Unable to start front camera")
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Subtle alignment guide ring overlay
        Box(
            modifier = Modifier
                .size(160.dp)
                .border(2.dp, PureWhite.copy(alpha = 0.6f), CircleShape)
        )
    }
}
