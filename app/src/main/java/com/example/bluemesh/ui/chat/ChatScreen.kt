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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    var isSending by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val lifecycleOwner = LocalLifecycleOwner.current
    val isPasscodeEnabled = remember { repository.isPasscodeEnabled() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (status != ConnectionStatus.CONNECTED && status != ConnectionStatus.SYNCHRONIZING) {
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
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
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
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
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
                    ChatBubble(message = message)
                }
            }

            // Input Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
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
                        if (textInput.isNotBlank() && !isSending) {
                            val messageText = textInput.trim()
                            textInput = ""
                            isSending = true
                            viewModel.sendMessage(messageText)
                            coroutineScope.launch {
                                delay(600) // 600ms cooldown to match BLE transmission rate
                                isSending = false
                            }
                        }
                    },
                    enabled = textInput.isNotBlank() && !isSending,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (textInput.isNotBlank() && !isSending) Color(0xFF3B82F6) else Color(0xFF1D263B),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF1D263B),
                        disabledContentColor = Color(0xFF475569)
                    ),
                    modifier = Modifier.size(48.dp)
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(imageVector = Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
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
                fontSize = 15.sp,
                lineHeight = 20.sp
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
                    }
                }
            }

        }
    }
}
