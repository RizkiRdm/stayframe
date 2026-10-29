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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.PlayerRegistry
import com.example.service.StayFrameNotificationListenerService
import com.example.ui.StayFrameViewModel
import com.example.ui.components.SleeveBadge
import com.example.ui.components.SleeveCard
import com.example.ui.components.SleeveEyebrow
import com.example.ui.components.SleevePillButton
import com.example.ui.theme.Graphite
import com.example.ui.theme.MidnightInk
import com.example.ui.theme.OceanDrift
import com.example.ui.theme.PaperWhite
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SilverMist
import com.example.ui.theme.Smoke
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed

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
    val isEnrolled by viewModel.isEnrolled.collectAsState()

    var showDeleteConfirm by remember { mutableStateOf(false) }

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

        // System Permissions Management
        item {
            SleeveEyebrow(text = "REQUIRED PERMISSIONS")
        }

        // Camera Permission
        item {
            PermissionCard(
                title = "Front Camera Access",
                subtitle = "Required for on-device presence detection. Frames are processed in volatile memory and immediately discarded.",
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

        // Notification Listener Access
        item {
            PermissionCard(
                title = "Notification Listener Access",
                subtitle = "Allows detecting active video player sessions and dispatching play/pause transport commands.",
                isGranted = notificationGranted,
                actionLabel = "Open System Access",
                onAction = {
                    val intent = StayFrameNotificationListenerService.createNotificationListenerSettingsIntent()
                    context.startActivity(intent)
                },
                testTag = "perm_notification_button"
            )
        }

        // Modify System Settings (Optional)
        item {
            PermissionCard(
                title = "Modify System Settings (Optional)",
                subtitle = "Required only if using Keep-Screen-Awake to temporarily override screen off timeout.",
                isGranted = writeSettingsGranted,
                actionLabel = "Manage Setting",
                onAction = {
                    val intent = viewModel.screenTimeoutController.createWriteSettingsIntent()
                    context.startActivity(intent)
                },
                testTag = "perm_write_settings_button"
            )
        }

        // Supported Players Compatibility Registry (PRD Section 7.2)
        item {
            SleeveEyebrow(text = "SUPPORTED VIDEO PLAYERS REGISTRY")
            Text(
                text = "Tested for Android MediaSession and TransportControls compatibility",
                style = MaterialTheme.typography.bodySmall,
                color = Smoke,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        items(PlayerRegistry.SUPPORTED_PLAYERS) { player ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(PureWhite)
                    .border(1.dp, SilverMist, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                color = PureWhite
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(PaperWhite)
                            .border(1.dp, SilverMist, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = StatusGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = player.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${player.category} • ${player.packageName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Smoke
                        )
                    }
                    SleeveBadge(
                        text = "SUPPORTED",
                        backgroundColor = PaperWhite,
                        textColor = OceanDrift,
                        borderColor = SilverMist
                    )
                }
            }
        }

        // Privacy & Data Management (PRD PRIV-05)
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
                    text = "Clear local diagnostic logs and any stored application data. StayFrame operates 100% on-device and records zero biometric templates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Graphite,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SleevePillButton(
                        text = "Clear Logs",
                        onClick = { viewModel.clearDiagnostics() },
                        isPrimary = false,
                        modifier = Modifier.weight(1f),
                        testTag = "settings_clear_logs"
                    )
                    SleevePillButton(
                        text = "Delete Data",
                        onClick = { showDeleteConfirm = true },
                        isPrimary = false,
                        icon = Icons.Default.Delete,
                        modifier = Modifier.weight(1f),
                        testTag = "settings_delete_template"
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                SleevePillButton(
                    text = "Reset All App Data (One-Tap)",
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
                    backgroundColor = PaperWhite,
                    borderColor = StatusRed
                ) {
                    Text(
                        text = "Confirm Data Wipe",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = StatusRed
                    )
                    Text(
                        text = "This will clear all local diagnostic events, cached configurations, and reset Keystore keys.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Graphite,
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

        item {
            Spacer(modifier = Modifier.height(24.dp))
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
        backgroundColor = PureWhite,
        borderColor = if (isGranted) SilverMist else StatusAmber
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
                    color = if (isGranted) StatusGreen else Graphite
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
