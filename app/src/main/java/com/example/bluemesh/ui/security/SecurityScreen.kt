package com.example.bluemesh.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Refresh
import com.example.bluemesh.data.DefaultDataRepository
import com.example.bluemesh.ui.LocalAccessibility

@Composable
fun SecurityScreen(
    onBackClick: () -> Unit,
    onNavigateToLock: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val accessibility = LocalAccessibility.current
    val repository = remember { DefaultDataRepository.getInstance(context.applicationContext) }
    var isShareLocationEnabled by remember { mutableStateOf(repository.isShareLocationEnabled()) }
    var showResetDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E131E))
            .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackClick,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back"
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Security Settings",
                    color = Color.White,
                    fontSize = accessibility.headerFontSize,
                    fontWeight = accessibility.headerFontWeight
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1D263B)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    // Change Passcode row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onNavigateToLock("verify_change")
                            }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(
                                        color = Color(0xFF8B5CF6).copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(8.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = "Change Passcode",
                                    tint = Color(0xFF8B5CF6)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "Change Passcode",
                                    color = Color.White,
                                    fontSize = accessibility.bodyFontSize,
                                    fontWeight = accessibility.bodyFontWeight
                                )
                                Text(
                                    text = "Update your 4-digit PIN",
                                    color = Color(0xFF94A3B8),
                                    fontSize = accessibility.captionFontSize
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Navigate",
                            tint = Color(0xFF64748B)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(
                        color = Color(0xFF334155)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Share Device Location row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(
                                        color = Color(0xFF10B981).copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(8.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GpsFixed,
                                    contentDescription = "Share Location",
                                    tint = Color(0xFF10B981)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "Share Device Location",
                                    color = Color.White,
                                    fontSize = accessibility.bodyFontSize,
                                    fontWeight = accessibility.bodyFontWeight
                                )
                                Text(
                                    text = "Allow to share proximity location",
                                    color = Color(0xFF94A3B8),
                                    fontSize = accessibility.captionFontSize
                                )
                            }
                        }

                        Switch(
                            checked = isShareLocationEnabled,
                            onCheckedChange = { checked ->
                                repository.setShareLocationEnabled(checked)
                                isShareLocationEnabled = checked
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF10B981),
                                uncheckedThumbColor = Color(0xFF94A3B8),
                                uncheckedTrackColor = Color(0xFF334155)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(
                        color = Color(0xFF334155)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Reset User ID row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showResetDialog = true
                            }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(
                                        color = Color(0xFFEF4444).copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(8.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Reset ID",
                                    tint = Color(0xFFEF4444)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "Reset User ID",
                                    color = Color.White,
                                    fontSize = accessibility.bodyFontSize,
                                    fontWeight = accessibility.bodyFontWeight
                                )
                                Text(
                                    text = "Generate a new static identifier",
                                    color = Color(0xFF94A3B8),
                                    fontSize = accessibility.captionFontSize
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Navigate",
                            tint = Color(0xFF64748B)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = {
                    showResetDialog = false
                },
                containerColor = Color(0xFF1D263B),
                title = {
                    Text(
                        text = "Reset User ID",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "Warning: Resetting your User ID will remove your connection to people who added you to their contacts. They will not be able to message you until they add your new ID.",
                        color = Color(0xFFEF4444),
                        fontSize = accessibility.bodyFontSize
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showResetDialog = false
                            onNavigateToLock("verify_reset_id")
                        }
                    ) {
                        Text("Confirm Reset", color = Color(0xFFEF4444))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showResetDialog = false
                        }
                    ) {
                        Text("Cancel", color = Color.White)
                    }
                }
            )
        }
    }
}
