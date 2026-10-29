package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.StayFrameViewModel
import com.example.ui.components.SleeveBadge
import com.example.ui.components.SleeveCard
import com.example.ui.components.SleeveEyebrow
import com.example.ui.components.SleevePillButton
import com.example.ui.theme.Graphite
import com.example.ui.theme.OceanDrift
import com.example.ui.theme.PaperWhite
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SilverMist
import com.example.ui.theme.Smoke
import com.example.ui.theme.StatusAmber

@Composable
fun EnrollmentScreen(
    viewModel: StayFrameViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onNavigateBack() }

    val isEnrolled by viewModel.isEnrolled.collectAsState()

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
                    SleeveEyebrow(text = "FACIAL IDENTITY")
                    Text(
                        text = "Face ID Enrollment",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Primary Coming Soon Status Card
        item {
            SleeveCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = PureWhite,
                borderColor = SilverMist,
                padding = 24.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(PaperWhite)
                            .border(1.5.dp, SilverMist, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Face,
                            contentDescription = null,
                            tint = OceanDrift,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    SleeveBadge(
                        text = "COMING SOON",
                        backgroundColor = PaperWhite,
                        textColor = StatusAmber,
                        borderColor = StatusAmber
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Strict Facial Verification",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Hardware-backed biometric embedding extraction is currently in development. StayFrame currently protects your playback via real-time spatial presence detection with a 2-second sustained filter (Relaxed Mode), requiring no biometric data storage.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Graphite
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Feature details bullet box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(PaperWhite)
                        .border(1.dp, SilverMist, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = OceanDrift,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Current Mode: Relaxed presence detection with 2-second stability window.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Graphite
                            )
                        }
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = OceanDrift,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Privacy: Camera frames never leave local volatile memory. No facial embeddings or templates are recorded.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Graphite
                            )
                        }
                    }
                }

                if (isEnrolled) {
                    Spacer(modifier = Modifier.height(16.dp))
                    SleevePillButton(
                        text = "Wipe Legacy Template Data",
                        onClick = { viewModel.deleteEnrollment() },
                        isPrimary = false,
                        icon = Icons.Default.Delete,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "delete_legacy_enrollment_button"
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                SleevePillButton(
                    text = "Return to Dashboard",
                    onClick = onNavigateBack,
                    isPrimary = true,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "return_dashboard_button"
                )
            }
        }
    }
}
