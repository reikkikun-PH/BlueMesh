package com.example.bluemesh.ui.chat

import androidx.lifecycle.ViewModel
import com.example.bluemesh.data.DataRepository
import com.example.bluemesh.data.models.ChatMessage
import com.example.bluemesh.data.models.ConnectionStatus
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
        dataRepository.startScan()
    }

    fun disconnect() {
        dataRepository.disconnect()
    }

    fun stopScan() {
        dataRepository.stopScan()
    }

    fun sendMessage(text: String): Boolean {
        if (text.isBlank()) return false
        return dataRepository.sendMessage(text)
    }

    fun clearChatHistory() {
        dataRepository.clearChatHistory()
    }

    override fun onCleared() {
        super.onCleared()
    }
}
