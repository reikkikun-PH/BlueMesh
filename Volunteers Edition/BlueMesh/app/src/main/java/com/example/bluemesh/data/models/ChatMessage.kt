package com.example.bluemesh.data.models

data class ChatMessage(
    val text: String,
    val isFromMe: Boolean,
    val timestamp: Long,
    val status: String = "SENT",
    val senderHash: Int? = null,
    val recipientHash: Int? = null
)
