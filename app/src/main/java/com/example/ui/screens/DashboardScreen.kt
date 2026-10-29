package com.example.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.DetectionMode
import com.example.domain.model.PresenceState
import com.example.domain.model.ScreenTimeoutMode
import com.example.service.StayFrameNotificationListenerService
import com.example.ui.StayFrameViewModel
import com.example.ui.components.SleeveAnnouncementPill
import com.example.ui.components.SleeveBadge
import com.example.ui.components.SleeveCard
import com.example.ui.components.SleeveEyebrow
import com.example.ui.components.SleevePillButton
import com.example.ui.components.SleeveToggleGroup
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
fun DashboardScreen(
    viewModel: StayFrameViewModel,
    onNavigateToEnrollment: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val presenceState by viewModel.presenceState.collectAsState()
    val activeSession by viewModel.activeSession.collectAsState()
    val isMonitoring by viewModel.isMonitoring.collectAsState()
    val isEnrolled by viewModel.isEnrolled.collectAsState()
    val enrolledUserName by viewModel.enrolledUserName.collectAsState()
    val gracePeriod by viewModel.absenceGracePeriod.collectAsState()
    val detectionMode by viewModel.detectionMode.collectAsState()
    val screenTimeoutMode by viewModel.screenTimeoutMode.collectAsState()
    val remainingGraceSeconds by viewModel.remainingGraceSeconds.collectAsState()
    val sustainedProgress by viewModel.sustainedPresenceProgress.collectAsState()
    val lastRewindOffsetMs by viewModel.lastRewindOffsetMs.collectAsState()

    val cameraPermissionGranted by viewModel.cameraPermissionGranted.collectAsState()
    val notificationListenerGranted by viewModel.notificationListenerGranted.collectAsState()
    val lastObservation by viewModel.lastObservation.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    SleeveEyebrow(text = "PRESENCE GUARD")
                    Text(
                        text = "StayFrame",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Black
                    )
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .testTag("dashboard_settings_button")
                        .clickable { onNavigateToSettings() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Permission Readiness Alert
        if (!cameraPermissionGranted || !notificationListenerGranted) {
            item {
                SleeveCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = PaperWhite,
                    borderColor = StatusAmber
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = StatusAmber,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Permissions Required",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = when {
                                    !cameraPermissionGranted -> "Camera permission required for on-device presence detection."
                                    !notificationListenerGranted -> "Notification listener access required to control video playback."
                                    else -> "Complete permission setup to enable playback guard."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Graphite
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    SleevePillButton(
                        text = "Complete Permissions",
                        onClick = onNavigateToSettings,
                        isPrimary = false,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "setup_action_button"
                    )
                }
            }
        }

        // Main Status Hero Card
        item {
            SleeveCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = PureWhite,
                borderColor = SilverMist,
                padding = 24.dp
            ) {
                // Top row with state badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(presenceState.indicatorColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = presenceState.title.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = presenceState.indicatorColor,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (isMonitoring) {
                        SleeveBadge(
                            text = "MONITORING ACTIVE",
                            brush = OceanDriftBrush,
                            textColor = PureWhite,
                            borderColor = CyanSplash
                        )
                    } else {
                        SleeveBadge(
                            text = "STANDBY",
                            backgroundColor = PaperWhite,
                            textColor = Graphite,
                            borderColor = SilverMist
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Hero Headline
                Text(
                    text = when (presenceState) {
                        PresenceState.INACTIVE -> "Monitoring is off"
                        PresenceState.WAITING_FOR_PLAYER -> "Waiting for video player..."
                        PresenceState.PRESENT -> "Viewer Present"
                        PresenceState.ABSENCE_GRACE -> "Absent ($remainingGraceSeconds s left)"
                        PresenceState.PAUSED_BY_GUARD -> "Paused & Rewound"
                        PresenceState.OTHER_FACE -> "Unrecognized face"
                        PresenceState.UNKNOWN -> "View obstructed"
                        PresenceState.ERROR -> "Service Error"
                    },
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = if (presenceState == PresenceState.ERROR) {
                        viewModel.presenceEngineLastErrorMessage ?: presenceState.description
                    } else {
                        presenceState.description
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (presenceState == PresenceState.ERROR) StatusRed else Graphite,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                // Rewind notification notice
                if (presenceState == PresenceState.PAUSED_BY_GUARD && lastRewindOffsetMs != null) {
                    SleeveAnnouncementPill(
                        text = "Rewound ${lastRewindOffsetMs!! / 1000}s back to the exact second you left.",
                        icon = Icons.Default.Replay
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Sustained presence progress bar (Relaxed Mode)
                if (detectionMode == DetectionMode.RELAXED_FALLBACK && sustainedProgress > 0f) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Validating sustained presence (> 2s)...",
                                style = MaterialTheme.typography.labelSmall,
                                color = OceanDrift
                            )
                            Text(
                                text = "${(sustainedProgress * 2.0f).toInt()}/2s",
                                style = MaterialTheme.typography.labelSmall,
                                color = OceanDrift
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { sustainedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(9999.dp)),
                            color = OceanDrift,
                            trackColor = PaperWhite
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // Active Player Tile
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(PaperWhite)
                        .border(1.dp, SilverMist, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    color = PaperWhite
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (activeSession.isPlaying) OceanDrift else Graphite),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (activeSession.isPlaying) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = null,
                                tint = PureWhite,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeSession.playerDisplayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (activeSession.isSupported) {
                                    if (activeSession.isPlaying) "Playing • Media controls connected" else "Paused"
                                } else {
                                    "No compatible video player active"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (activeSession.isPlaying) StatusGreen else Graphite
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Permission warning if not granted yet
                if (!isMonitoring && (!cameraPermissionGranted || !notificationListenerGranted)) {
                    SleeveAnnouncementPill(
                        text = "Camera & Notification Listener permissions required. Tap to setup in Settings.",
                        icon = Icons.Default.Warning
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Primary Start/Stop Pill Button
                SleevePillButton(
                    text = if (isMonitoring) "Stop Monitoring" else "Start Monitoring",
                    onClick = {
                        viewModel.toggleMonitoring(onRequiresPermissions = onNavigateToSettings)
                    },
                    isPrimary = !isMonitoring,
                    icon = if (isMonitoring) Icons.Default.Pause else Icons.Default.PlayArrow,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "toggle_monitoring_button"
                )

                // Optional Manual Resume Button (if paused by guard)
                if (presenceState == PresenceState.PAUSED_BY_GUARD) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SleevePillButton(
                        text = "Resume Playback Now",
                        onClick = { viewModel.manualResumePlayback() },
                        isPrimary = false,
                        icon = Icons.Default.PlayArrow,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "manual_resume_button"
                    )
                }
            }
        }

        // Quick Controls: Grace Period
        item {
            SleeveCard(modifier = Modifier.fillMaxWidth()) {
                SleeveEyebrow(text = "ABSENCE GRACE PERIOD")
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Rewinds & pauses video after you leave view",
                    style = MaterialTheme.typography.bodySmall,
                    color = Graphite
                )
                Spacer(modifier = Modifier.height(12.dp))
                SleeveToggleGroup(
                    items = listOf(3, 5, 10),
                    selectedItem = gracePeriod,
                    onItemSelected = { viewModel.setAbsenceGracePeriod(it) },
                    itemLabel = { "${it}s" },
                    testTagPrefix = "grace_period"
                )
            }
        }

        // Quick Controls: Detection Mode
        item {
            SleeveCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SleeveEyebrow(text = "DETECTION MODE")
                    SleeveBadge(
                        text = "STRICT COMING SOON",
                        backgroundColor = PaperWhite,
                        textColor = Smoke,
                        borderColor = SilverMist
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Relaxed: Resumes when face presence is sustained > 2 seconds (ignores brief passersby). Strict biometric matching is in development.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Graphite
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9999.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9999.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9999.dp))
                            .background(OceanDrift)
                            .padding(vertical = 10.dp)
                            .testTag("detection_mode_relaxed"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Relaxed (2s)",
                            style = MaterialTheme.typography.labelMedium,
                            color = PureWhite,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9999.dp))
                            .background(Color.Transparent)
                            .padding(vertical = 10.dp)
                            .testTag("detection_mode_strict_disabled"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Strict (Coming soon)",
                            style = MaterialTheme.typography.labelMedium,
                            color = Smoke,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
            }
        }

        // Quick Controls: Screen Timeout Keep-Awake
        item {
            SleeveCard(modifier = Modifier.fillMaxWidth()) {
                SleeveEyebrow(text = "KEEP SCREEN AWAKE")
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Temporarily overrides system screen timeout while monitoring",
                    style = MaterialTheme.typography.bodySmall,
                    color = Graphite
                )
                Spacer(modifier = Modifier.height(12.dp))
                SleeveToggleGroup(
                    items = listOf(
                        ScreenTimeoutMode.OFF,
                        ScreenTimeoutMode.MIN_15,
                        ScreenTimeoutMode.MIN_30,
                        ScreenTimeoutMode.UNTIL_STOPPED
                    ),
                    selectedItem = screenTimeoutMode,
                    onItemSelected = { viewModel.setScreenTimeoutMode(it) },
                    itemLabel = { it.title },
                    testTagPrefix = "screen_timeout"
                )
            }
        }

        // Quick Navigation to Diagnostics & Live Preview
        item {
            SleeveCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToDiagnostics() }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        SleeveEyebrow(text = "DIAGNOSTICS & PREVIEW")
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Test camera feed & state inspector",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "View live bounding box and confidence score",
                            style = MaterialTheme.typography.bodySmall,
                            color = Graphite
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(PaperWhite)
                            .border(1.dp, SilverMist, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = "Diagnostics",
                            tint = OceanDrift
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
