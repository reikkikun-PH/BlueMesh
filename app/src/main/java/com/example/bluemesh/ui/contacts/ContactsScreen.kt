package com.example.bluemesh.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bluemesh.data.DefaultDataRepository
import com.example.bluemesh.data.models.BluetoothPeer
import com.example.bluemesh.theme.LocalBlueMeshColors
import com.example.bluemesh.ui.LocalAccessibility

@Composable
fun ContactsScreen(
    onBackClick: () -> Unit,
    onNavigateToChat: (String, String) -> Unit,
    onNavigateToSecurity: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val accessibility = LocalAccessibility.current
    val repository = remember { DefaultDataRepository.getInstance(context.applicationContext) }
    val isPasscodeEnabled = remember { repository.isPasscodeEnabled() }

    val discoveredPeers by repository.discoveredPeers.collectAsStateWithLifecycle(initialValue = emptyList())
    var contactsList by remember { mutableStateOf(repository.getContacts()) }
    val colors = LocalBlueMeshColors.current

    val refreshContacts = {
        contactsList = repository.getContacts()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
    ) {
        if (!isPasscodeEnabled) {
            // Passcode lock required state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.error.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Security required",
                        modifier = Modifier.size(40.dp),
                        tint = colors.error
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Passcode Lock Required",
                    color = colors.onSurface,
                    fontSize = accessibility.headerFontSize,
                    fontWeight = accessibility.headerFontWeight,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "To protect your contact lists and offline queue history, you must enable a Passcode lock in Security Settings.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = colors.textSecondary,
                    fontSize = accessibility.bodyFontSize,
                    textAlign = TextAlign.Center,
                    lineHeight = accessibility.bodyFontSize * 1.5f
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = onNavigateToSecurity,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onSurface
                    )
                ) {
                    Text(
                        text = "Go to Security Settings",
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(onClick = onBackClick) {
                    Text(
                        text = "Back",
                        color = colors.textTertiary
                    )
                }
            }
        } else {
            // Main Contacts UI
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBackClick,
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = colors.onSurface
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Contacts",
                        color = colors.onSurface,
                        fontSize = accessibility.headerFontSize,
                        fontWeight = accessibility.headerFontWeight
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (contactsList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No saved contacts yet.\nSave nearby peers as contacts from the main screen list.",
                            color = colors.textTertiary,
                            fontSize = accessibility.bodyFontSize,
                            textAlign = TextAlign.Center,
                            lineHeight = accessibility.bodyFontSize * 1.5f
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(contactsList, key = { it.uuid }) { contact ->
                            val isOnline = discoveredPeers.any { com.example.bluemesh.utils.uuidsMatch(it.uuid, contact.uuid) }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = colors.surface
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Status indicator dot
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isOnline) colors.success else colors.textTertiary
                                            )
                                    )

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = contact.name,
                                                color = colors.onSurface,
                                                fontSize = accessibility.bodyFontSize,
                                                fontWeight = accessibility.bodyFontWeight,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (contact.isOfficial) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = "Official Profile",
                                                    tint = colors.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = if (isOnline) "Active / In Range" else "Offline / Out of Range",
                                            color = if (isOnline) colors.success else colors.textTertiary,
                                            fontSize = accessibility.captionFontSize
                                        )
                                    }

                                    Row {
                                        IconButton(
                                            onClick = {
                                                onNavigateToChat(contact.uuid, contact.name)
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Chat,
                                                contentDescription = "Chat",
                                                tint = colors.primary
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                repository.deleteContact(contact.uuid)
                                                refreshContacts()
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete Contact",
                                                tint = colors.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
