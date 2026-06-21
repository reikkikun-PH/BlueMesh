package com.example.bluemesh.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bluemesh.data.DefaultDataRepository
import com.example.bluemesh.data.models.ChatMessage
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.bluemesh.data.models.ConnectionStatus
import com.example.bluemesh.ui.AccessibilityState
import com.example.bluemesh.ui.LocalAccessibility

@Composable
fun ChatScreen(
    peerUuid: String,
    peerName: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val viewModel: ChatScreenViewModel = viewModel {
        ChatScreenViewModel(DefaultDataRepository.getInstance(context), peerUuid)
    }
    val messages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val status by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val isReady by viewModel.isReady.collectAsStateWithLifecycle()

    val repository = remember { DefaultDataRepository.getInstance(context) }
    val accessibility = LocalAccessibility.current
    val discoveredPeers by repository.discoveredPeers.collectAsStateWithLifecycle()
    val isOfficial = remember(discoveredPeers, peerUuid) {
        discoveredPeers.find {
            com.example.bluemesh.utils.uuidsMatch(it.uuid, peerUuid)
        }?.isOfficial == true ||
        repository.getContacts().find {
            com.example.bluemesh.utils.uuidsMatch(it.uuid, peerUuid)
        }?.isOfficial == true
    }

    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()


    val lifecycleOwner = LocalLifecycleOwner.current
    val isPasscodeEnabled = remember { repository.isPasscodeEnabled() }

    LaunchedEffect(peerUuid) {
        repository.setActiveChat(peerUuid)
        val currentStatus = viewModel.connectionStatus.value
        if (currentStatus != ConnectionStatus.CONNECTED && currentStatus != ConnectionStatus.SYNCHRONIZING) {
            viewModel.connect()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    val currentStatus = viewModel.connectionStatus.value
                    if (currentStatus != ConnectionStatus.CONNECTED && currentStatus != ConnectionStatus.SYNCHRONIZING) {
                        viewModel.connect()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            repository.setActiveChat("")
        }
    }



    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E131E)) // Slate Navy background
            .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            val showDeleteConfirm = remember { mutableStateOf(false) }

            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        onBackClick()
                    },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                ) {
                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = peerName,
                            color = Color.White,
                            fontSize = accessibility.headerFontSize,
                            fontWeight = accessibility.headerFontWeight,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isOfficial) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Official Profile",
                                tint = Color(0xFF3B82F6),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Connection Status text with color
                    val (statusText, statusColor) = when (status) {
                        ConnectionStatus.CONNECTING -> "Connecting..." to Color(0xFFF59E0B) // Amber
                        ConnectionStatus.SYNCHRONIZING -> "Synchronizing..." to Color(0xFF38BDF8) // Sky Blue
                        ConnectionStatus.CONNECTED -> "Connected" to Color(0xFF10B981) // Emerald
                        ConnectionStatus.DISCONNECTED -> "Disconnected" to Color(0xFFEF4444) // Red
                    }
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontSize = accessibility.subtitleFontSize,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                IconButton(
                    onClick = { showDeleteConfirm.value = true },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFFEF4444))
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Chat",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            if (showDeleteConfirm.value) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm.value = false },
                    title = {
                        Text("Delete Chat History", color = Color.White)
                    },
                    text = {
                        Text("Are you sure you want to delete all chat history with this contact? This cannot be undone.", color = Color(0xFF94A3B8))
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showDeleteConfirm.value = false
                            viewModel.clearChatHistory()
                        }) {
                            Text("Delete", color = Color(0xFFEF4444))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm.value = false }) {
                            Text("Cancel", color = Color(0xFF94A3B8))
                        }
                    },
                    containerColor = Color(0xFF1D263B)
                )
            }

            HorizontalDivider(color = Color(0xFF1E293B), thickness = 1.dp)

            // Message Area
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(messages) { message ->
                    ChatBubble(message = message, accessibility = accessibility)
                }
            }

            // Input Area
            Column(modifier = Modifier.fillMaxWidth()) {
                if (textInput.length >= 200) {
                    Text(
                        text = "${textInput.length}/300",
                        color = if (textInput.length == 300) Color(0xFFEF4444) else Color(0xFF3B82F6),
                        fontSize = accessibility.captionFontSize,
                        modifier = Modifier.align(Alignment.End).padding(end = 64.dp, bottom = 2.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = {
                            if (it.length <= 300) {
                                textInput = it
                            }
                        },
                        placeholder = { Text("Type a message...", color = Color(0xFF64748B)) },
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF3B82F6),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedContainerColor = Color(0xFF1D263B),
                            unfocusedContainerColor = Color(0xFF1D263B),
                            cursorColor = Color(0xFF3B82F6)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )

                IconButton(
                    onClick = {
                        if (textInput.isNotBlank()) {
                            val messageText = textInput.trim()
                            textInput = ""
                            viewModel.sendMessage(messageText)
                        }
                    },
                    enabled = textInput.isNotBlank(),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (textInput.isNotBlank()) Color(0xFF3B82F6) else Color(0xFF1D263B),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF1D263B),
                        disabledContentColor = Color(0xFF475569)
                    ),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(imageVector = Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
    }
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    accessibility: AccessibilityState = AccessibilityState()
) {
    val alignment = if (message.isFromMe) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (message.isFromMe) {
        Brush.linearGradient(colors = listOf(Color(0xFF6366F1), Color(0xFF3B82F6)))
    } else {
        Brush.linearGradient(colors = listOf(Color(0xFF1D263B), Color(0xFF1D263B)))
    }
    val textColor = Color.White

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    brush = bubbleColor,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (message.isFromMe) 16.dp else 4.dp,
                        bottomEnd = if (message.isFromMe) 4.dp else 16.dp
                    )
                )
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = message.text,
                color = textColor,
                fontSize = accessibility.messageFontSize,
                lineHeight = accessibility.messageFontSize * 1.3f,
                fontWeight = accessibility.fontWeight
            )
            if (message.isFromMe) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (message.status == "PENDING") {
                        Text(
                            text = "Sending…",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 10.sp
                        )
                    } else if (message.status == "SENT") {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Check,
                            contentDescription = "Sent",
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(12.dp)
                        )
                        if (message.latencyMs != null) {
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "${message.latencyMs}ms",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            } else if (message.latencyMs != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${message.latencyMs}ms",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.End)
                )
            }

        }
    }
}
