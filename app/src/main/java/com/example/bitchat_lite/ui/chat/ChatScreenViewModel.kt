package com.example.bitchat_lite.ui.chat

import androidx.lifecycle.ViewModel
import com.example.bitchat_lite.data.DataRepository
import com.example.bitchat_lite.data.models.ChatMessage
import com.example.bitchat_lite.data.models.ConnectionStatus
import kotlinx.coroutines.flow.StateFlow

class ChatScreenViewModel(
    private val dataRepository: DataRepository,
    private val peerUuid: String
) : ViewModel() {

    val connectionStatus: StateFlow<ConnectionStatus> = dataRepository.connectionStatus
    val isReady: StateFlow<Boolean> = dataRepository.isReady
    val chatMessages: StateFlow<List<ChatMessage>> = dataRepository.chatMessages

    fun connect() {
        dataRepository.connectToPeerByUuid(peerUuid)
    }

    fun disconnect() {
        dataRepository.disconnect()
    }

    fun sendMessage(text: String): Boolean {
        if (text.isBlank()) return false
        return dataRepository.sendMessage(text)
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
