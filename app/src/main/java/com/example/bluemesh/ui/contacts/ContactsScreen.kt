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

    val refreshContacts = {
        contactsList = repository.getContacts()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E131E))
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
                        .background(Color(0xFFEF4444).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Security required",
                        modifier = Modifier.size(40.dp),
                        tint = Color(0xFFEF4444)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Passcode Lock Required",
                    color = Color.White,
                    fontSize = accessibility.headerFontSize,
                    fontWeight = accessibility.headerFontWeight,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "To protect your contact lists and offline queue history, you must enable a Passcode lock in Security Settings.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = Color(0xFF94A3B8),
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
                        containerColor = Color(0xFF3B82F6),
                        contentColor = Color.White
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
                        color = Color(0xFF64748B)
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
                        text = "Contacts",
                        color = Color.White,
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
                            color = Color(0xFF64748B),
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
                                    containerColor = Color(0xFF1D263B)
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
                                                if (isOnline) Color(0xFF10B981) else Color(0xFF64748B)
                                            )
                                    )

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = contact.name,
                                                color = Color.White,
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
                                                    tint = Color(0xFF3B82F6),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = if (isOnline) "Active / In Range" else "Offline / Out of Range",
                                            color = if (isOnline) Color(0xFF10B981) else Color(0xFF64748B),
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
                                                tint = Color(0xFF3B82F6)
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
                                                tint = Color(0xFFEF4444)
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
