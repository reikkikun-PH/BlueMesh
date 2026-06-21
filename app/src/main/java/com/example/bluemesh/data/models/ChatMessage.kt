package com.example.bluemesh.data.models

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    SYNCHRONIZING,
    CONNECTED
}

data class ChatMessage(
    val text: String,
    val isFromMe: Boolean,
    val timestamp: Long,
    val status: String = "SENT",
    val senderHash: Int? = null,
    val recipientHash: Int? = null,
    val senderAddress: String? = null,
    val latencyMs: Long? = null
)
