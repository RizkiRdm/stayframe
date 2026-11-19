package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.domain.model.DetectionMode
import com.example.service.StayFrameServiceBridge
import com.example.ui.StayFrameViewModel
import com.example.ui.components.SleeveBadge
import com.example.ui.components.SleeveCard
import com.example.ui.components.SleeveEyebrow
import com.example.ui.theme.CyanSplash
import com.example.ui.theme.OceanDrift
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SilverMist
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    viewModel: StayFrameViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true
) {
    if (showBackButton) {
        BackHandler { onNavigateBack() }
    }

    val isPreviewVisible by viewModel.isPreviewVisible.collectAsState()

    DisposableEffect(isPreviewVisible) {
        onDispose {
            StayFrameServiceBridge.setSurfaceProvider(null)
        }
    }

    val isMonitoring by viewModel.isMonitoring.collectAsState()
    val lastObservation by viewModel.lastObservation.collectAsState()
    val diagnosticEvents by viewModel.diagnosticEvents.collectAsState()
    val detectionMode by viewModel.detectionMode.collectAsState()
    val effectiveGraceSeconds by viewModel.effectiveGraceSeconds.collectAsState()
    val activeSyncSource by viewModel.activeSyncSource.collectAsState()

    val faceDetected = lastObservation?.faceDetected == true
    val matchConfidence = lastObservation?.identityMatchConfidence ?: 0f
    val isEnrolledMatch = lastObservation?.isEnrolledMatch == true

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 720.dp)
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
                    if (showBackButton) {
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
                    }
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
                    padding = 20.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Camera Preview",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isPreviewVisible) "Displaying real-time front frame" else "Hidden (background monitoring)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = isPreviewVisible,
                            onCheckedChange = { viewModel.togglePreview(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PureWhite,
                                checkedTrackColor = OceanDrift,
                                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.testTag("preview_toggle_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 10f)
                            .heightIn(min = 180.dp, max = 300.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isPreviewVisible) Color(0xFF1E293B) else MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
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

                                    val boxColor = if (detectionMode == DetectionMode.STRICT) {
                                        if (isEnrolledMatch) StatusGreen else StatusAmber
                                    } else {
                                        OceanDrift
                                    }

                                    drawRoundRect(
                                        color = boxColor,
                                        topLeft = Offset(leftPx, topPx),
                                        size = Size(boxWidth, boxHeight),
                                        cornerRadius = CornerRadius(12f, 12f),
                                        style = Stroke(width = 3.dp.toPx())
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(Color(0x99000000))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val statusLabel = when {
                                        !faceDetected -> "NO FACE DETECTED"
                                        detectionMode == DetectionMode.STRICT && isEnrolledMatch ->
                                            "VERIFIED (${(matchConfidence * 100).toInt()}%)"
                                        detectionMode == DetectionMode.STRICT ->
                                            "UNRECOGNIZED (${(matchConfidence * 100).toInt()}%)"
                                        else -> "FACE DETECTED (RELAXED)"
                                    }
                                    val statusColor = when {
                                        !faceDetected -> PureWhite
                                        detectionMode == DetectionMode.STRICT && isEnrolledMatch -> StatusGreen
                                        detectionMode == DetectionMode.STRICT -> StatusAmber
                                        else -> StatusGreen
                                    }
                                    Text(
                                        text = statusLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = statusColor,
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
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Preview is hidden to save power",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Presence detection continues in background",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Dual State Indicators + Adaptive Sync Chip
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    maxItemsInEachRow = 2
                ) {
                    SleeveCard(
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(min = 140.dp),
                        padding = 16.dp
                    ) {
                        SleeveEyebrow(text = "DETECTOR")
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (faceDetected) "Detected" else "Searching",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (faceDetected) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Spatial presence in frame",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    SleeveCard(
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(min = 140.dp),
                        padding = 16.dp
                    ) {
                        SleeveEyebrow(
                            text = if (detectionMode == DetectionMode.STRICT) "IDENTITY" else "MODE"
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val modeValue = when {
                            detectionMode == DetectionMode.RELAXED_FALLBACK -> "Relaxed"
                            !faceDetected -> "Standby"
                            isEnrolledMatch -> "Verified"
                            else -> "Mismatch"
                        }
                        val modeColor = when {
                            detectionMode == DetectionMode.RELAXED_FALLBACK -> OceanDrift
                            !faceDetected -> MaterialTheme.colorScheme.onSurfaceVariant
                            isEnrolledMatch -> StatusGreen
                            else -> StatusAmber
                        }
                        Text(
                            text = modeValue,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = modeColor
                        )
                        Text(
                            text = if (detectionMode == DetectionMode.STRICT) {
                                "Match score: ${(matchConfidence * 100).toInt()}%"
                            } else {
                                "2s sustained • ${effectiveGraceSeconds}s (${activeSyncSource.label})"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Local Diagnostic Event Log
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
                                .padding(8.dp)
                        )
                    }
                }
            }

            if (diagnosticEvents.isEmpty()) {
                item {
                    SleeveCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "No events logged yet. Active monitoring events will appear here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(diagnosticEvents.take(25)) { event ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = event.formattedTime,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = event.details,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
