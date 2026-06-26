package com.example.bluemesh.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluemesh.data.DataRepository
import com.example.bluemesh.data.models.ChatMessage
import com.example.bluemesh.data.models.ConnectionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatScreenViewModel(
    private val dataRepository: DataRepository,
    private val peerUuid: String
) : ViewModel() {

    val connectionStatus: StateFlow<ConnectionStatus> = dataRepository.connectionStatus
    val isReady: StateFlow<Boolean> = dataRepository.isReady
    val chatMessages: StateFlow<List<ChatMessage>> = dataRepository.chatMessages

    private val _canSend = MutableStateFlow(true)
    val canSend: StateFlow<Boolean> = _canSend.asStateFlow()

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
        if (text.isBlank() || !_canSend.value) return false
        val sent = dataRepository.sendMessage(text)
        if (sent) {
            _canSend.value = false
            viewModelScope.launch {
                delay(1500)
                _canSend.value = true
            }
        }
        return sent
    }

    fun clearChatHistory() {
        dataRepository.clearChatHistory()
    }

    fun deleteOutgoingMessage(timestamp: Long) {
        dataRepository.deleteOutgoingMessage(timestamp)
    }
}
