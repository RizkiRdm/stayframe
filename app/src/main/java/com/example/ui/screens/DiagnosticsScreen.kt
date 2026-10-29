package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.domain.model.DetectionMode
import com.example.domain.model.PresenceState
import com.example.service.StayFrameServiceBridge
import com.example.ui.StayFrameViewModel
import com.example.ui.components.SleeveAnnouncementPill
import com.example.ui.components.SleeveBadge
import com.example.ui.components.SleeveCard
import com.example.ui.components.SleeveEyebrow
import com.example.ui.components.SleevePillButton
import com.example.ui.theme.CyanSplash
import com.example.ui.theme.Graphite
import com.example.ui.theme.MidnightInk
import com.example.ui.theme.OceanDrift
import com.example.ui.theme.OceanDriftBrush
import com.example.ui.theme.PaperWhite
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SilverMist
import com.example.ui.theme.Smoke
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed

@Composable
fun DiagnosticsScreen(
    viewModel: StayFrameViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onNavigateBack() }

    val presenceState by viewModel.presenceState.collectAsState()
    val isPreviewVisible by viewModel.isPreviewVisible.collectAsState()

    DisposableEffect(isPreviewVisible) {
        onDispose {
            StayFrameServiceBridge.setSurfaceProvider(null)
        }
    }
    val isMonitoring by viewModel.isMonitoring.collectAsState()
    val activeSession by viewModel.activeSession.collectAsState()
    val lastObservation by viewModel.lastObservation.collectAsState()
    val diagnosticEvents by viewModel.diagnosticEvents.collectAsState()
    val detectionMode by viewModel.detectionMode.collectAsState()
    val sustainedProgress by viewModel.sustainedPresenceProgress.collectAsState()
    val remainingGraceSeconds by viewModel.remainingGraceSeconds.collectAsState()

    val faceDetected = lastObservation?.faceDetected == true
    val matchConfidence = lastObservation?.identityMatchConfidence ?: 0f
    val isEnrolledMatch = lastObservation?.isEnrolledMatch == true

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
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
                    modifier = Modifier.testTag("diagnostics_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    SleeveEyebrow(text = "LIVE TELEMETRY")
                    Text(
                        text = "Diagnostics & Preview",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Live Diagnostic Camera Surface & Bounding Frame
        item {
            SleeveCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = PureWhite,
                borderColor = SilverMist,
                padding = 20.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Camera Preview",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isPreviewVisible) "Displaying real-time front frame" else "Hidden (background monitoring)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Graphite
                        )
                    }
                    Switch(
                        checked = isPreviewVisible,
                        onCheckedChange = { viewModel.togglePreview(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PureWhite,
                            checkedTrackColor = OceanDrift,
                            uncheckedThumbColor = SilverMist,
                            uncheckedTrackColor = PaperWhite
                        ),
                        modifier = Modifier.testTag("preview_toggle_switch")
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Viewfinder Screen
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isPreviewVisible) Color(0xFF1E293B) else PaperWhite)
                        .border(1.dp, SilverMist, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isPreviewVisible) {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).apply {
                                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                    StayFrameServiceBridge.setSurfaceProvider(this.surfaceProvider)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Real ML Kit Face Bounding Box Overlay
                        val bbox = lastObservation?.faceBoundingBox
                        if (faceDetected && bbox != null && bbox.size == 4) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val leftNorm = (1f - bbox[2]).coerceIn(0f, 1f)
                                val rightNorm = (1f - bbox[0]).coerceIn(0f, 1f)
                                val topNorm = bbox[1].coerceIn(0f, 1f)
                                val bottomNorm = bbox[3].coerceIn(0f, 1f)

                                val leftPx = leftNorm * size.width
                                val topPx = topNorm * size.height
                                val boxWidth = (rightNorm - leftNorm) * size.width
                                val boxHeight = (bottomNorm - topNorm) * size.height

                                drawRoundRect(
                                    color = if (isEnrolledMatch) StatusGreen else StatusAmber,
                                    topLeft = Offset(leftPx, topPx),
                                    size = Size(boxWidth, boxHeight),
                                    cornerRadius = CornerRadius(12f, 12f),
                                    style = Stroke(width = 3.dp.toPx())
                                )
                            }
                        }

                        // Status Telemetry Bar Overlay
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Color(0x99000000))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (faceDetected) "FACE DETECTED (RELAXED MODE)"
                                    else "NO FACE DETECTED",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (faceDetected) StatusGreen else PureWhite,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (isMonitoring) "CAMERA ACTIVE" else "MONITORING OFF",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isMonitoring) CyanSplash else SilverMist
                                )
                            }
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.VisibilityOff,
                                contentDescription = null,
                                tint = Smoke,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Preview is hidden to save power",
                                style = MaterialTheme.typography.bodySmall,
                                color = Graphite
                            )
                            Text(
                                text = "Presence detection continues in background",
                                style = MaterialTheme.typography.bodySmall,
                                color = Smoke
                            )
                        }
                    }
                }
            }
        }

        // Dual State Indicators (PRD Section 7.5: Distinguish face detected vs identity verified)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Card 1: Face Detection
                SleeveCard(
                    modifier = Modifier.weight(1f),
                    padding = 16.dp
                ) {
                    SleeveEyebrow(text = "DETECTOR")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (faceDetected) "Detected" else "Searching",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (faceDetected) StatusGreen else Graphite
                    )
                    Text(
                        text = "Spatial presence in camera frame",
                        style = MaterialTheme.typography.bodySmall,
                        color = Smoke
                    )
                }

                // Card 2: Operating Mode
                SleeveCard(
                    modifier = Modifier.weight(1f),
                    padding = 16.dp
                ) {
                    SleeveEyebrow(text = "MODE")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Relaxed",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = StatusGreen
                    )
                    Text(
                        text = "2s sustained window • Strict: Soon",
                        style = MaterialTheme.typography.bodySmall,
                        color = Smoke
                    )
                }
            }
        }

        // Local Diagnostic Event Log (PRD Section 15)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SleeveEyebrow(text = "LOCAL EVENT LOG (${diagnosticEvents.size})")
                if (diagnosticEvents.isNotEmpty()) {
                    Text(
                        text = "Clear",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusRed,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .testTag("clear_logs_button")
                            .clickable { viewModel.clearDiagnostics() }
                    )
                }
            }
        }

        if (diagnosticEvents.isEmpty()) {
            item {
                SleeveCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = PaperWhite,
                    borderColor = SilverMist
                ) {
                    Text(
                        text = "No events logged yet. Active monitoring events will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Smoke
                    )
                }
            }
        } else {
            items(diagnosticEvents.take(25)) { event ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(PaperWhite)
                        .border(1.dp, SilverMist, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    color = PaperWhite
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(event.state.indicatorColor)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = event.eventName,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = event.formattedTime,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Smoke
                                )
                            }
                            Text(
                                text = event.details,
                                style = MaterialTheme.typography.bodySmall,
                                color = Graphite
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
