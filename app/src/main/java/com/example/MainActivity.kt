package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.StayFrameViewModel
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.EnrollmentScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MidnightInk
import com.example.ui.theme.OceanDrift
import com.example.ui.theme.PaperWhite
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SilverMist
import com.example.ui.theme.Smoke
import com.example.ui.theme.StayFrameTheme

enum class AppDestination(val label: String, val icon: ImageVector) {
    DASHBOARD("Guard", Icons.Default.Home),
    DIAGNOSTICS("Diagnostics", Icons.Default.Visibility),
    ENROLLMENT("Face ID", Icons.Default.Face),
    SETTINGS("Settings", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    private val viewModel: StayFrameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            StayFrameTheme {
                StayFrameApp(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }
}

@Composable
fun StayFrameApp(viewModel: StayFrameViewModel) {
    var currentDestination by remember { mutableStateOf(AppDestination.DASHBOARD) }

    // Runtime Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshPermissions()
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedLayout = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (!isExpandedLayout) {
                    SleeveFloatingNavDock(
                        currentDestination = currentDestination,
                        onDestinationSelected = { currentDestination = it }
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .statusBarsPadding()
            ) {
                if (isExpandedLayout) {
                    // Responsive Tablet / Desktop dual-pane canonical layout
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Left Pane: Navigation Rail + Master View
                        Column(
                            modifier = Modifier
                                .weight(1.1f)
                                .fillMaxHeight()
                        ) {
                            SleeveFloatingNavDock(
                                currentDestination = currentDestination,
                                onDestinationSelected = { currentDestination = it },
                                modifier = Modifier.padding(16.dp)
                            )
                            when (currentDestination) {
                                AppDestination.DASHBOARD -> DashboardScreen(
                                    viewModel = viewModel,
                                    onNavigateToEnrollment = { currentDestination = AppDestination.ENROLLMENT },
                                    onNavigateToDiagnostics = { currentDestination = AppDestination.DIAGNOSTICS },
                                    onNavigateToSettings = { currentDestination = AppDestination.SETTINGS }
                                )
                                AppDestination.ENROLLMENT -> EnrollmentScreen(
                                    viewModel = viewModel,
                                    onNavigateBack = { currentDestination = AppDestination.DASHBOARD }
                                )
                                AppDestination.SETTINGS -> SettingsScreen(
                                    viewModel = viewModel,
                                    onNavigateBack = { currentDestination = AppDestination.DASHBOARD }
                                )
                                AppDestination.DIAGNOSTICS -> DiagnosticsScreen(
                                    viewModel = viewModel,
                                    onNavigateBack = { currentDestination = AppDestination.DASHBOARD }
                                )
                            }
                        }

                        // Right Pane: Always-visible Live Diagnostics & Telemetry
                        Box(
                            modifier = Modifier
                                .weight(0.9f)
                                .fillMaxHeight()
                                .border(1.dp, SilverMist)
                                .background(PaperWhite)
                        ) {
                            DiagnosticsScreen(
                                viewModel = viewModel,
                                onNavigateBack = { currentDestination = AppDestination.DASHBOARD }
                            )
                        }
                    }
                } else {
                    // Compact Phone Layout
                    AnimatedContent(
                        targetState = currentDestination,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "screen_transition"
                    ) { destination ->
                        when (destination) {
                            AppDestination.DASHBOARD -> DashboardScreen(
                                viewModel = viewModel,
                                onNavigateToEnrollment = { currentDestination = AppDestination.ENROLLMENT },
                                onNavigateToDiagnostics = { currentDestination = AppDestination.DIAGNOSTICS },
                                onNavigateToSettings = { currentDestination = AppDestination.SETTINGS }
                            )
                            AppDestination.ENROLLMENT -> EnrollmentScreen(
                                viewModel = viewModel,
                                onNavigateBack = { currentDestination = AppDestination.DASHBOARD }
                            )
                            AppDestination.DIAGNOSTICS -> DiagnosticsScreen(
                                viewModel = viewModel,
                                onNavigateBack = { currentDestination = AppDestination.DASHBOARD }
                            )
                            AppDestination.SETTINGS -> SettingsScreen(
                                viewModel = viewModel,
                                onNavigateBack = { currentDestination = AppDestination.DASHBOARD }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sleeve floating pill navigation dock matching DESIGN_82d5e326.md
 */
@Composable
fun SleeveFloatingNavDock(
    currentDestination: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .clip(RoundedCornerShape(9999.dp))
                .border(1.dp, SilverMist, RoundedCornerShape(9999.dp)),
            color = PureWhite,
            shape = RoundedCornerShape(9999.dp),
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppDestination.entries.forEach { destination ->
                    val isSelected = destination == currentDestination

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9999.dp))
                            .background(if (isSelected) PaperWhite else Color.Transparent)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) SilverMist else Color.Transparent,
                                shape = RoundedCornerShape(9999.dp)
                            )
                            .testTag("nav_item_${destination.name.lowercase()}")
                            .clickable(
                                role = Role.Tab,
                                onClick = { onDestinationSelected(destination) }
                            )
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Active status dot
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(OceanDrift)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = destination.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MidnightInk else Smoke
                            )
                        }
                    }
                }
            }
        }
    }
}
