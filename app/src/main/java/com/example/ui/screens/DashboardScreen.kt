package com.example.ui.screens

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.DetectionMode
import com.example.domain.model.PresenceState
import com.example.domain.model.ScreenTimeoutMode
import com.example.domain.model.SyncSource
import com.example.ui.StayFrameViewModel
import com.example.ui.components.SleeveAnnouncementPill
import com.example.ui.components.SleeveBadge
import com.example.ui.components.SleeveCard
import com.example.ui.components.SleeveEyebrow
import com.example.ui.components.SleevePillButton
import com.example.ui.components.SleeveToggleGroup
import com.example.ui.theme.CyanSplash
import com.example.ui.theme.OceanDrift
import com.example.ui.theme.OceanDriftBrush
import com.example.ui.theme.PureWhite
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    viewModel: StayFrameViewModel,
    onNavigateToEnrollment: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    showQuickDiagnosticsCard: Boolean = true
) {
    val presenceState by viewModel.presenceState.collectAsState()
    val activeSession by viewModel.activeSession.collectAsState()
    val isMonitoring by viewModel.isMonitoring.collectAsState()
    val isEnrolled by viewModel.isEnrolled.collectAsState()
    val enrolledUserName by viewModel.enrolledUserName.collectAsState()
    val gracePeriod by viewModel.absenceGracePeriod.collectAsState()
    val effectiveGraceSeconds by viewModel.effectiveGraceSeconds.collectAsState()
    val activeSyncSource by viewModel.activeSyncSource.collectAsState()
    val detectionMode by viewModel.detectionMode.collectAsState()
    val screenTimeoutMode by viewModel.screenTimeoutMode.collectAsState()
    val remainingGraceSeconds by viewModel.remainingGraceSeconds.collectAsState()
    val sustainedProgress by viewModel.sustainedPresenceProgress.collectAsState()
    val lastRewindOffsetMs by viewModel.lastRewindOffsetMs.collectAsState()

    val cameraPermissionGranted by viewModel.cameraPermissionGranted.collectAsState()
    val notificationListenerGranted by viewModel.notificationListenerGranted.collectAsState()

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
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        SleeveEyebrow(text = "PRESENCE GUARD")
                        Text(
                            text = "StayFrame",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .minimumInteractiveComponentSize()
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

            if (!cameraPermissionGranted || !notificationListenerGranted) {
                item {
                    SleeveCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
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
                                        !notificationListenerGranted -> "Notification listener access required to observe & control video playback."
                                        else -> "Complete permission setup to enable playback guard."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    padding = 20.dp
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                text = "GUARD ARMED",
                                brush = OceanDriftBrush,
                                textColor = PureWhite,
                                borderColor = CyanSplash
                            )
                        } else {
                            SleeveBadge(text = "STANDBY")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = when (presenceState) {
                            PresenceState.INACTIVE -> "Monitoring is off"
                            PresenceState.WAITING_FOR_PLAYER -> "Waiting for video player"
                            PresenceState.PLAYER_DETECTED -> "Player paused (Ready)"
                            PresenceState.PRESENT -> "Viewer Present"
                            PresenceState.ABSENCE_GRACE -> "Absent ($remainingGraceSeconds s left)"
                            PresenceState.PAUSED_BY_GUARD -> "Paused & Rewound"
                            PresenceState.OTHER_FACE -> "Unrecognized face ($remainingGraceSeconds s)"
                            PresenceState.UNKNOWN -> "View obstructed"
                            PresenceState.PLAYER_IN_BACKGROUND -> "Standby in background"
                            PresenceState.PLAYER_UNAVAILABLE -> "Player unavailable"
                            PresenceState.RECOVERING -> "Recovering session..."
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
                        color = if (presenceState == PresenceState.ERROR) StatusRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                    )

                    if (presenceState == PresenceState.PAUSED_BY_GUARD && lastRewindOffsetMs != null) {
                        val sec = lastRewindOffsetMs!! / 1000L
                        SleeveAnnouncementPill(
                            text = if (sec > 0L) {
                                "Rewound ${sec}s back to the moment you left."
                            } else {
                                "Paused stream (seek unsupported on live/ad session)."
                            },
                            icon = Icons.Default.Replay
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

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
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }

                    // Active Player Tile + Adaptive Timing Pill
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (activeSession.isPlaying) OceanDrift else MaterialTheme.colorScheme.secondary),
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
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (activeSession.isSupported) {
                                            if (activeSession.isPlaying) {
                                                if (activeSession.canSeek) "Playing • Adaptive rewind ready"
                                                else "Playing • Pause-only (live/unseekable)"
                                            } else {
                                                "Paused • Guard observing passively"
                                            }
                                        } else {
                                            "No compatible video player active"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (activeSession.isPlaying) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (activeSession.isSupported) {
                                Spacer(modifier = Modifier.height(10.dp))
                                SleeveBadge(
                                    text = "Effective: ${effectiveGraceSeconds}s (${activeSyncSource.label})",
                                    icon = Icons.Default.Sync,
                                    textColor = OceanDrift
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

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

            // Quick Controls: Fallback Absence Grace Period
            item {
                SleeveCard(modifier = Modifier.fillMaxWidth()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SleeveEyebrow(text = "ABSENCE GRACE PERIOD")
                        if (activeSession.isSupported && activeSyncSource != SyncSource.APP_GLOBAL_FALLBACK) {
                            SleeveBadge(text = "PLAYER SYNC: ${effectiveGraceSeconds}s")
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Default fallback grace when a player has no synced interval. Per-player sync takes precedence.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

            // Quick Controls: Detection Mode (Relaxed vs Strict Face ID)
            item {
                SleeveCard(modifier = Modifier.fillMaxWidth()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SleeveEyebrow(text = "DETECTION MODE")
                        SleeveBadge(
                            text = if (isEnrolled) "FACE PROFILE READY" else "ENROLL FOR STRICT",
                            textColor = if (isEnrolled) StatusGreen else StatusAmber,
                            borderColor = if (isEnrolled) StatusGreen else StatusAmber,
                            icon = Icons.Default.Face
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (detectionMode == DetectionMode.STRICT) {
                            "Strict Mode: Resumes only when $enrolledUserName matches enrolled facial geometry; unrecognized faces trigger absence countdown."
                        } else {
                            "Relaxed Mode: Resumes when any face stays in view > 2s (ignores brief passersby). Tap Strict to require your enrolled face profile."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SleeveToggleGroup(
                        items = listOf(DetectionMode.RELAXED_FALLBACK, DetectionMode.STRICT),
                        selectedItem = detectionMode,
                        onItemSelected = { selected ->
                            viewModel.setDetectionMode(
                                mode = selected,
                                onRequiresEnrollment = onNavigateToEnrollment
                            )
                        },
                        itemLabel = { mode ->
                            when (mode) {
                                DetectionMode.RELAXED_FALLBACK -> "Relaxed (2s)"
                                DetectionMode.STRICT -> if (isEnrolled) "Strict (Face ID)" else "Strict (Setup)"
                            }
                        },
                        testTagPrefix = "detection_mode"
                    )
                    if (!isEnrolled || detectionMode == DetectionMode.STRICT) {
                        Spacer(modifier = Modifier.height(10.dp))
                        SleevePillButton(
                            text = if (isEnrolled) "Manage Enrolled Face Profile" else "Enroll Face Profile",
                            onClick = onNavigateToEnrollment,
                            isPrimary = false,
                            icon = Icons.Default.Face,
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "manage_enrollment_button"
                        )
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

            // Quick Navigation to Diagnostics (hidden in Expanded dual-pane where Diagnostics is already visible!)
            if (showQuickDiagnosticsCard) {
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
                                    text = "View live bounding box and identity score",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
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
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
