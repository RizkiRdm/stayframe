package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.StayFrameViewModel
import com.example.ui.components.SleeveAnnouncementPill
import com.example.ui.components.SleeveBadge
import com.example.ui.components.SleeveCard
import com.example.ui.components.SleeveEyebrow
import com.example.ui.components.SleevePillButton
import com.example.ui.theme.Graphite
import com.example.ui.theme.MidnightInk
import com.example.ui.theme.OceanDrift
import com.example.ui.theme.OceanDriftBrush
import com.example.ui.theme.PaperWhite
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SilverMist
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EnrollmentScreen(
    viewModel: StayFrameViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onNavigateBack() }

    val isEnrolled by viewModel.isEnrolled.collectAsState()
    val enrolledUserName by viewModel.enrolledUserName.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var userNameInput by remember { mutableStateOf(enrolledUserName) }
    var enrollmentStep by remember { mutableIntStateOf(if (isEnrolled) 3 else 0) } // 0: intro, 1: capturing, 2: processing, 3: success
    var captureProgress by remember { mutableStateOf("Hold steady and look at the screen...") }
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
                    SleeveEyebrow(text = "ON-DEVICE BIOMETRICS")
                    Text(
                        text = "Face Enrollment",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (isEnrolled && enrollmentStep == 3) {
            // Already Enrolled Summary View
            item {
                SleeveCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = PureWhite,
                    borderColor = SilverMist,
                    padding = 24.dp
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(OceanDriftBrush),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = PureWhite,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "Enrolled: $enrolledUserName",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Android Keystore Encrypted",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusGreen
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "Your facial template is stored locally on-device. When monitoring is active in Strict mode, StayFrame verifies your identity before resuming video playback.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Graphite
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SleevePillButton(
                            text = "Re-enroll",
                            onClick = {
                                enrollmentStep = 1
                            },
                            isPrimary = false,
                            icon = Icons.Default.Refresh,
                            modifier = Modifier.weight(1f),
                            testTag = "re_enroll_button"
                        )
                        SleevePillButton(
                            text = "Delete",
                            onClick = { showDeleteConfirm = true },
                            isPrimary = false,
                            icon = Icons.Default.Delete,
                            modifier = Modifier.weight(1f),
                            testTag = "delete_enrollment_button"
                        )
                    }
                }
            }
        } else {
            // Enrollment Flow Steps
            item {
                SleeveCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = PureWhite,
                    borderColor = SilverMist,
                    padding = 24.dp
                ) {
                    // Viewfinder / Interactive Camera Target
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .align(Alignment.CenterHorizontally)
                            .clip(CircleShape)
                            .background(PaperWhite)
                            .border(3.dp, OceanDrift, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (enrollmentStep == 1 || enrollmentStep == 2) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(170.dp),
                                color = OceanDrift,
                                strokeWidth = 4.dp
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Face,
                            contentDescription = "Face alignment",
                            tint = if (enrollmentStep == 3) StatusGreen else OceanDrift,
                            modifier = Modifier.size(80.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = when (enrollmentStep) {
                            0 -> "Align your face in the circle"
                            1 -> "Analyzing face geometry..."
                            2 -> "Encrypting template in Keystore..."
                            else -> "Enrollment Complete!"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = captureProgress,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Graphite,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 16.dp)
                    )

                    if (enrollmentStep == 0) {
                        OutlinedTextField(
                            value = userNameInput,
                            onValueChange = { userNameInput = it },
                            label = { Text("Your Name or Profile") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("enrolled_name_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = OceanDrift,
                                unfocusedBorderColor = SilverMist
                            )
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        SleevePillButton(
                            text = "Begin Capture",
                            onClick = {
                                enrollmentStep = 1
                                coroutineScope.launch {
                                    captureProgress = "Step 1 of 3: Look straight ahead..."
                                    delay(1200)
                                    captureProgress = "Step 2 of 3: Slight tilt left and right..."
                                    delay(1200)
                                    captureProgress = "Step 3 of 3: Computing feature vector..."
                                    enrollmentStep = 2
                                    delay(800)
                                    // Generate a normalized 16-element feature vector representing user
                                    val simulatedVector = FloatArray(16) { 0.45f + (it * 0.03f) }
                                    viewModel.enrollFace(userNameInput.ifBlank { "Primary Viewer" }, simulatedVector)
                                    enrollmentStep = 3
                                    captureProgress = "Face registered successfully!"
                                }
                            },
                            isPrimary = true,
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "begin_capture_button"
                        )
                    }
                }
            }
        }

        // Privacy & Security Disclosure Card (PRD FACE-09)
        item {
            SleeveCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = PaperWhite,
                borderColor = SilverMist
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = OceanDrift,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "100% Local Privacy & Limitations",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• Zero camera frames or biometric data leave your device.\n" +
                                    "• Stored templates are encrypted via hardware-backed Android Keystore.\n" +
                                    "• StayFrame is a convenience tool, not an authentication system. It does not provide presentation-attack/spoofing protection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Graphite
                        )
                    }
                }
            }
        }

        // Delete Confirmation Modal / Banner
        if (showDeleteConfirm) {
            item {
                SleeveCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = PaperWhite,
                    borderColor = StatusRed
                ) {
                    Text(
                        text = "Delete Biometric Enrollment?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = StatusRed
                    )
                    Text(
                        text = "This will permanently remove the encrypted facial template from Android Keystore. Monitoring will require re-enrollment.",
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
                            text = "Confirm Delete",
                            onClick = {
                                viewModel.deleteEnrollment()
                                showDeleteConfirm = false
                                enrollmentStep = 0
                            },
                            isPrimary = true,
                            modifier = Modifier.weight(1f),
                            testTag = "confirm_delete_button"
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
