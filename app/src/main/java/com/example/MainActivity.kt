package com.example

import android.Manifest
import android.content.Intent
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.StayFrameViewModel
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.EnrollmentScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.OceanDrift
import com.example.ui.theme.StayFrameTheme

enum class AppDestination(val label: String, val icon: ImageVector) {
    DASHBOARD("Guard", Icons.Default.Home),
    DIAGNOSTICS("Monitor", Icons.Default.Visibility),
    ENROLLMENT("Face ID", Icons.Default.Face),
    SETTINGS("Settings", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    private val viewModel: StayFrameViewModel by viewModels()

    companion object {
        const val EXTRA_RESUME_MONITORING = "com.stayframe.extra.RESUME_MONITORING"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            StayFrameTheme {
                StayFrameApp(viewModel = viewModel)
            }
        }

        handleResumeIntentIfRequested(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleResumeIntentIfRequested(intent)
    }

    private fun handleResumeIntentIfRequested(launchIntent: Intent?) {
        if (launchIntent?.getBooleanExtra(EXTRA_RESUME_MONITORING, false) == true) {
            launchIntent.removeExtra(EXTRA_RESUME_MONITORING)
            viewModel.resumeMonitoringFromNotification()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }
}

@Composable
fun StayFrameApp(viewModel: StayFrameViewModel) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestination.DASHBOARD) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshPermissions()
    }

    val requestPermissions: () -> Unit = {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 600.dp
        val isExpanded = maxWidth >= 840.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (isCompact) {
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
                    .displayCutoutPadding()
            ) {
                when {
                    isExpanded -> {
                        // Expanded Tablet / Desktop Canonical Dual-Pane Layout (never duplicates DiagnosticsScreen!)
                        val railDestinations = listOf(
                            AppDestination.DASHBOARD,
                            AppDestination.ENROLLMENT,
                            AppDestination.SETTINGS
                        )
                        val primaryDest = if (currentDestination == AppDestination.DIAGNOSTICS) {
                            AppDestination.DASHBOARD
                        } else {
                            currentDestination
                        }

                        Row(modifier = Modifier.fillMaxSize()) {
                            AdaptiveNavRail(
                                destinations = railDestinations,
                                currentDestination = primaryDest,
                                onDestinationSelected = { currentDestination = it }
                            )
                            VerticalDivider(color = MaterialTheme.colorScheme.outline)

                            Box(
                                modifier = Modifier
                                    .weight(1.1f)
                                    .fillMaxHeight()
                            ) {
                                when (primaryDest) {
                                    AppDestination.DASHBOARD,
                                    AppDestination.DIAGNOSTICS -> DashboardScreen(
                                        viewModel = viewModel,
                                        onNavigateToEnrollment = { currentDestination = AppDestination.ENROLLMENT },
                                        onNavigateToDiagnostics = { currentDestination = AppDestination.DASHBOARD },
                                        onNavigateToSettings = { currentDestination = AppDestination.SETTINGS },
                                        showQuickDiagnosticsCard = false
                                    )
                                    AppDestination.ENROLLMENT -> EnrollmentScreen(
                                        viewModel = viewModel,
                                        onNavigateBack = { currentDestination = AppDestination.DASHBOARD }
                                    )
                                    AppDestination.SETTINGS -> SettingsScreen(
                                        viewModel = viewModel,
                                        onNavigateBack = { currentDestination = AppDestination.DASHBOARD },
                                        onRequestCameraPermission = requestPermissions
                                    )
                                }
                            }

                            VerticalDivider(color = MaterialTheme.colorScheme.outline)

                            // Supporting Pane: Live Diagnostics & Telemetry (rendered once)
                            Box(
                                modifier = Modifier
                                    .weight(0.9f)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                DiagnosticsScreen(
                                    viewModel = viewModel,
                                    onNavigateBack = { currentDestination = AppDestination.DASHBOARD },
                                    showBackButton = false
                                )
                            }
                        }
                    }

                    !isCompact -> {
                        // Medium Layout (Foldables / Small Tablets 600–839dp): Side NavigationRail + centered single pane
                        Row(modifier = Modifier.fillMaxSize()) {
                            AdaptiveNavRail(
                                destinations = AppDestination.entries,
                                currentDestination = currentDestination,
                                onDestinationSelected = { currentDestination = it }
                            )
                            VerticalDivider(color = MaterialTheme.colorScheme.outline)

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                ScreenContentHost(
                                    destination = currentDestination,
                                    viewModel = viewModel,
                                    onNavigate = { currentDestination = it },
                                    onRequestCameraPermission = requestPermissions
                                )
                            }
                        }
                    }

                    else -> {
                        // Compact Phone Layout (< 600dp)
                        ScreenContentHost(
                            destination = currentDestination,
                            viewModel = viewModel,
                            onNavigate = { currentDestination = it },
                            onRequestCameraPermission = requestPermissions
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenContentHost(
    destination: AppDestination,
    viewModel: StayFrameViewModel,
    onNavigate: (AppDestination) -> Unit,
    onRequestCameraPermission: () -> Unit
) {
    AnimatedContent(
        targetState = destination,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen_transition"
    ) { target ->
        when (target) {
            AppDestination.DASHBOARD -> DashboardScreen(
                viewModel = viewModel,
                onNavigateToEnrollment = { onNavigate(AppDestination.ENROLLMENT) },
                onNavigateToDiagnostics = { onNavigate(AppDestination.DIAGNOSTICS) },
                onNavigateToSettings = { onNavigate(AppDestination.SETTINGS) }
            )
            AppDestination.ENROLLMENT -> EnrollmentScreen(
                viewModel = viewModel,
                onNavigateBack = { onNavigate(AppDestination.DASHBOARD) }
            )
            AppDestination.DIAGNOSTICS -> DiagnosticsScreen(
                viewModel = viewModel,
                onNavigateBack = { onNavigate(AppDestination.DASHBOARD) }
            )
            AppDestination.SETTINGS -> SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { onNavigate(AppDestination.DASHBOARD) },
                onRequestCameraPermission = onRequestCameraPermission
            )
        }
    }
}

@Composable
private fun AdaptiveNavRail(
    destinations: List<AppDestination>,
    currentDestination: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxHeight()
            .navigationBarsPadding()
            .padding(vertical = 12.dp)
    ) {
        Spacer(modifier = Modifier.weight(0.15f))
        destinations.forEach { destination ->
            val selected = destination == currentDestination
            NavigationRailItem(
                selected = selected,
                onClick = { onDestinationSelected(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = destination.label
                    )
                },
                label = {
                    Text(
                        text = destination.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = OceanDrift,
                    selectedTextColor = OceanDrift,
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.testTag("nav_rail_${destination.name.lowercase()}")
            )
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

/**
 * Responsive floating pill navigation dock with horizontal scroll safety,
 * semantic colorScheme tokens, and 48dp minimum touch targets on narrow phones.
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .clip(RoundedCornerShape(9999.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9999.dp)),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(9999.dp),
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppDestination.entries.forEach { destination ->
                    val isSelected = destination == currentDestination

                    Box(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(RoundedCornerShape(9999.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.outline else Color.Transparent,
                                shape = RoundedCornerShape(9999.dp)
                            )
                            .testTag("nav_item_${destination.name.lowercase()}")
                            .clickable(
                                role = Role.Tab,
                                onClick = { onDestinationSelected(destination) }
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = null,
                                tint = if (isSelected) OceanDrift else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = destination.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
