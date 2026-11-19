package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.registry.DiscoveredPlayerEntity
import com.example.domain.model.PlayerVerificationStatus
import com.example.service.StayFrameNotificationListenerService
import com.example.ui.StayFrameViewModel
import com.example.ui.components.SleeveBadge
import com.example.ui.components.SleeveCard
import com.example.ui.components.SleeveEyebrow
import com.example.ui.components.SleevePillButton
import com.example.ui.theme.OceanDrift
import com.example.ui.theme.PureWhite
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: StayFrameViewModel,
    onNavigateBack: () -> Unit,
    onRequestCameraPermission: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    BackHandler { onNavigateBack() }

    val context = LocalContext.current
    val cameraGranted by viewModel.cameraPermissionGranted.collectAsState()
    val notificationGranted by viewModel.notificationListenerGranted.collectAsState()
    val writeSettingsGranted by viewModel.writeSettingsGranted.collectAsState()
    val discoveredPlayers by viewModel.discoveredPlayers.collectAsState()
    val isRescanning by viewModel.isRescanningPlayers.collectAsState()

    var showDeleteConfirm by remember { mutableStateOf(false) }

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
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        SleeveEyebrow(text = "CONFIGURATION")
                        Text(
                            text = "Settings & Registry",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            item {
                SleeveEyebrow(text = "REQUIRED PERMISSIONS")
            }

            item {
                PermissionCard(
                    title = "Front Camera Access",
                    subtitle = "Required for on-device presence detection. Camera frames are processed in volatile memory and discarded.",
                    isGranted = cameraGranted,
                    actionLabel = "Grant Permission",
                    onAction = {
                        if (onRequestCameraPermission != null) {
                            onRequestCameraPermission()
                        } else {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    },
                    testTag = "perm_camera_button"
                )
            }

            item {
                PermissionCard(
                    title = "Notification Listener Access",
                    subtitle = "Allows observing active video player sessions and dispatching play/pause/seek transport controls.",
                    isGranted = notificationGranted,
                    actionLabel = "Open System Access",
                    onAction = {
                        val intent = StayFrameNotificationListenerService.createNotificationListenerSettingsIntent()
                        context.startActivity(intent)
                    },
                    testTag = "perm_notification_button"
                )
            }

            item {
                PermissionCard(
                    title = "Modify System Settings (Optional)",
                    subtitle = "Required only if using Keep-Screen-Awake to temporarily override system screen off timeout.",
                    isGranted = writeSettingsGranted,
                    actionLabel = "Manage Setting",
                    onAction = {
                        val intent = viewModel.screenTimeoutController.createWriteSettingsIntent()
                        context.startActivity(intent)
                    },
                    testTag = "perm_write_settings_button"
                )
            }

            // Dynamic Video Players Discovery & Adaptive Sync Overrides
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        SleeveEyebrow(text = "DYNAMIC VIDEO PLAYER REGISTRY")
                        Text(
                            text = "Auto-discovered apps & per-player seek sync",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(RoundedCornerShape(9999.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9999.dp))
                            .testTag("settings_rescan_players")
                            .clickable(enabled = !isRescanning) { viewModel.rescanPlayers() },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(9999.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isRescanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = OceanDrift
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Rescan players",
                                    tint = OceanDrift,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isRescanning) "Scanning" else "Rescan",
                                style = MaterialTheme.typography.labelMedium,
                                color = OceanDrift,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            if (discoveredPlayers.isEmpty()) {
                item {
                    SleeveCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "No compatible video players detected yet. Tap Rescan or start playing video in any Android media player.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(discoveredPlayers, key = { it.packageName }) { player ->
                    DiscoveredPlayerCard(
                        player = player,
                        onToggleEnabled = { enabled ->
                            viewModel.setPlayerMonitoringEnabled(player.packageName, enabled)
                        },
                        onSelectInterval = { sec ->
                            viewModel.setPlayerSyncedInterval(player.packageName, sec)
                        }
                    )
                }
            }

            // Privacy & Data Management
            item {
                SleeveEyebrow(text = "DATA & PRIVACY MANAGEMENT")
            }

            item {
                SleeveCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Local Storage & Data Wiping",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Clear local diagnostic logs, encrypted Strict Mode facial geometry profiles, and Keystore keys. All data stays 100% on-device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        maxItemsInEachRow = 2
                    ) {
                        SleevePillButton(
                            text = "Clear Logs",
                            onClick = { viewModel.clearDiagnostics() },
                            isPrimary = false,
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(min = 130.dp),
                            testTag = "settings_clear_logs"
                        )
                        SleevePillButton(
                            text = "Delete Profile",
                            onClick = { showDeleteConfirm = true },
                            isPrimary = false,
                            icon = Icons.Default.Delete,
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(min = 130.dp),
                            testTag = "settings_delete_template"
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    SleevePillButton(
                        text = "Reset All App Data",
                        onClick = { viewModel.resetAllAppData() },
                        isPrimary = false,
                        icon = Icons.Default.Delete,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "settings_reset_all"
                    )
                }
            }

            if (showDeleteConfirm) {
                item {
                    SleeveCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                        borderColor = StatusRed
                    ) {
                        Text(
                            text = "Confirm Profile Wipe",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = StatusRed
                        )
                        Text(
                            text = "This wipes enrolled facial geometry data, deletes the Android Keystore key, and switches Detection Mode back to Relaxed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SleevePillButton(
                                text = "Cancel",
                                onClick = { showDeleteConfirm = false },
                                isPrimary = false,
                                modifier = Modifier.weight(1f)
                            )
                            SleevePillButton(
                                text = "Erase Now",
                                onClick = {
                                    viewModel.deleteEnrollment()
                                    showDeleteConfirm = false
                                },
                                isPrimary = true,
                                modifier = Modifier.weight(1f),
                                testTag = "settings_confirm_erase"
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoveredPlayerCard(
    player: DiscoveredPlayerEntity,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectInterval: (Int?) -> Unit
) {
    val status = player.statusEnum()
    val badgeColor = when (status) {
        PlayerVerificationStatus.VERIFIED, PlayerVerificationStatus.ACTIVE_NOW -> StatusGreen
        PlayerVerificationStatus.CANDIDATE -> OceanDrift
        PlayerVerificationStatus.UNAVAILABLE -> StatusRed
        PlayerVerificationStatus.USER_DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    SleeveCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = player.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    SleeveBadge(
                        text = status.label.uppercase(),
                        textColor = badgeColor,
                        borderColor = badgeColor
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${player.sourceEnum().label} • ${player.confidenceScore}% match • ${player.packageName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (status != PlayerVerificationStatus.UNAVAILABLE) {
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = player.isEnabledForMonitoring,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PureWhite,
                        checkedTrackColor = OceanDrift
                    )
                )
            }
        }

        if (status != PlayerVerificationStatus.UNAVAILABLE && player.isEnabledForMonitoring) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Seek / Absence Sync:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            val intervalOptions: List<Pair<Int?, String>> = listOf(
                null to if (player.profileDefaultIntervalSec != null) "Auto (${player.profileDefaultIntervalSec}s)" else "Auto",
                5 to "5s",
                10 to "10s",
                15 to "15s"
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                intervalOptions.forEach { (sec, label) ->
                    val selected = player.userSyncedIntervalSec == sec
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(RoundedCornerShape(9999.dp))
                            .background(if (selected) OceanDrift else MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                1.dp,
                                if (selected) OceanDrift else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(9999.dp)
                            )
                            .clickable { onSelectInterval(sec) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) PureWhite else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    testTag: String
) {
    SleeveCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = if (isGranted) MaterialTheme.colorScheme.outline else StatusAmber
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isGranted) StatusGreen else StatusAmber),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = PureWhite,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isGranted) "Granted & Active" else subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isGranted) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!isGranted) {
            Spacer(modifier = Modifier.height(12.dp))
            SleevePillButton(
                text = actionLabel,
                onClick = onAction,
                isPrimary = false,
                modifier = Modifier.fillMaxWidth(),
                testTag = testTag
            )
        }
    }
}
