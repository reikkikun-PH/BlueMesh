package com.example.bitchat_lite.data.models

data class ChatMessage(
    val text: String,
    val isFromMe: Boolean,
    val timestamp: Long,
    val status: String = "SENT"
)
