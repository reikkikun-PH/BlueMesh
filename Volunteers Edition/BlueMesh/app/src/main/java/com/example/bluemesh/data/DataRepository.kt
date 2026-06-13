package com.example.bluemesh.data

import android.bluetooth.BluetoothDevice
import com.example.bluemesh.data.models.BluetoothPeer
import com.example.bluemesh.data.models.ChatMessage
import com.example.bluemesh.data.models.ConnectionStatus
import kotlinx.coroutines.flow.StateFlow

interface DataRepository {
    val discoveredPeers: StateFlow<List<BluetoothPeer>>
    val connectionStatus: StateFlow<ConnectionStatus>
    val isReady: StateFlow<Boolean>
    val chatMessages: StateFlow<List<ChatMessage>>
    val isScanning: StateFlow<Boolean>
    val isAdvertising: StateFlow<Boolean>

    fun startScan()
    fun stopScan()
    fun startAdvertising(name: String)
    fun stopAdvertising()
    fun connectToPeer(device: BluetoothDevice)
    fun disconnect()
    fun sendMessage(text: String): Boolean
    fun getDisplayName(): String
    fun saveDisplayName(name: String)
    fun clearChatHistory()

    fun getUserUuid(): String
    fun getContacts(): List<BluetoothPeer>
    fun saveContact(uuid: String, name: String)
    fun deleteContact(uuid: String)
    fun isContact(uuid: String): Boolean
    fun setActiveChat(uuid: String)
    fun connectToPeerByUuid(uuid: String)

    fun isPasscodeEnabled(): Boolean
    fun savePasscode(pin: String)
    fun verifyPasscode(pin: String): Boolean
    fun disablePasscode()
}
