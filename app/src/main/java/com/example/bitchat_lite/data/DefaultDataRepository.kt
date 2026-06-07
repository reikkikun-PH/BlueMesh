package com.example.bitchat_lite.data

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.example.bitchat_lite.bluetooth.BluetoothHandler
import com.example.bitchat_lite.data.models.BluetoothPeer
import com.example.bitchat_lite.data.models.ChatMessage
import com.example.bitchat_lite.data.models.ConnectionStatus
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DefaultDataRepository private constructor(context: Context) : DataRepository {

    companion object {
        @Volatile
        private var INSTANCE: DefaultDataRepository? = null

        fun getInstance(context: Context): DefaultDataRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DefaultDataRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences("bluemesh_prefs", Context.MODE_PRIVATE)
    private val bluetoothHandler = BluetoothHandler(context)
    private val scope = CoroutineScope(Dispatchers.Default)
    private val dbHelper = OfflineQueueDbHelper(context)
    private var activeChatUuid: String = ""

    override val discoveredPeers: StateFlow<List<BluetoothPeer>> = bluetoothHandler.discoveredPeers
    override val connectionStatus: StateFlow<ConnectionStatus> = bluetoothHandler.connectionStatus
    override val isReady: StateFlow<Boolean> = bluetoothHandler.isReady
    override val isScanning: StateFlow<Boolean> = bluetoothHandler.isScanning
    override val isAdvertising: StateFlow<Boolean> = bluetoothHandler.isAdvertising

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    init {
        val uuidStr = prefs.getString("user_uuid", "") ?: ""
        if (uuidStr.isEmpty()) {
            prefs.edit().putString("user_uuid", UUID.randomUUID().toString()).apply()
        }

        bluetoothHandler.onPeerReadyCallback = { peerUuid ->
            scope.launch {
                sendPendingMessages(peerUuid)
            }
        }

        scope.launch {
            bluetoothHandler.messages.collect { message ->
                val connectedAddress = bluetoothHandler.getConnectedDeviceAddress()
                val peer = bluetoothHandler.discoveredPeers.value.find { it.address == connectedAddress }
                val senderUuid = peer?.uuid ?: activeChatUuid

                if (senderUuid.isNotEmpty() && !message.isFromMe) {
                    dbHelper.insertMessage(senderUuid, message.text, message.timestamp, "RECEIVED", false)
                }

                if (senderUuid == activeChatUuid && !message.isFromMe) {
                    _chatMessages.update { current -> current + message }
                }
            }
        }

        scope.launch {
            bluetoothHandler.connectionStatus.collect { status ->
                if (status == ConnectionStatus.DISCONNECTED) {
                    if (activeChatUuid.isNotEmpty()) {
                        _chatMessages.value = dbHelper.getMessagesForContact(activeChatUuid)
                    } else {
                        _chatMessages.value = emptyList()
                    }
                }
            }
        }
    }

    override fun startScan() {
        bluetoothHandler.startScanning()
    }

    override fun stopScan() {
        bluetoothHandler.stopScanning()
    }

    override fun startAdvertising(name: String) {
        bluetoothHandler.startAdvertising(name)
    }

    override fun stopAdvertising() {
        bluetoothHandler.stopAdvertising()
    }

    override fun connectToPeer(device: BluetoothDevice) {
        bluetoothHandler.connectToPeer(device)
    }

    override fun disconnect() {
        bluetoothHandler.disconnect()
    }

    override fun sendMessage(text: String): Boolean {
        if (activeChatUuid.isEmpty()) return false
        val timestamp = System.currentTimeMillis()
        val peer = bluetoothHandler.discoveredPeers.value.find { it.uuid == activeChatUuid }

        if (peer != null && bluetoothHandler.isReady.value) {
            val sent = bluetoothHandler.sendMessage(text)
            if (sent) {
                dbHelper.insertMessage(activeChatUuid, text, timestamp, "SENT", true)
                _chatMessages.update { current -> current + ChatMessage(text, true, timestamp, "SENT") }
                return true
            }
        }

        dbHelper.insertMessage(activeChatUuid, text, timestamp, "PENDING", true)
        _chatMessages.update { current -> current + ChatMessage(text, true, timestamp, "PENDING") }
        return false
    }

    override fun getDisplayName(): String {
        return prefs.getString("display_name", "") ?: ""
    }

    override fun saveDisplayName(name: String) {
        prefs.edit().putString("display_name", name).apply()
    }

    override fun clearChatHistory() {
        _chatMessages.value = emptyList()
        if (activeChatUuid.isNotEmpty()) {
            val db = dbHelper.writableDatabase
            db.delete("QueuedMessages", "contact_uuid = ?", arrayOf(activeChatUuid))
        }
    }

    override fun getUserUuid(): String {
        return prefs.getString("user_uuid", "") ?: ""
    }

    override fun getContacts(): List<BluetoothPeer> {
        val list = dbHelper.getContactsList()
        val currentDiscovered = bluetoothHandler.discoveredPeers.value
        return list.map { (uuid, name) ->
            val discoveredPeer = currentDiscovered.find { it.uuid == uuid }
            val address = discoveredPeer?.address ?: ""
            BluetoothPeer(
                address = address,
                name = name,
                device = discoveredPeer?.device,
                lastSeen = discoveredPeer?.lastSeen ?: 0L,
                uuid = uuid
            )
        }
    }

    override fun saveContact(uuid: String, name: String) {
        dbHelper.saveContact(uuid, name)
    }

    override fun deleteContact(uuid: String) {
        dbHelper.deleteContact(uuid)
    }

    override fun isContact(uuid: String): Boolean {
        return dbHelper.isContact(uuid)
    }

    override fun setActiveChat(uuid: String) {
        activeChatUuid = uuid
        _chatMessages.value = dbHelper.getMessagesForContact(uuid)
    }

    override fun connectToPeerByUuid(uuid: String) {
        val peer = bluetoothHandler.discoveredPeers.value.find { it.uuid == uuid }
        if (peer != null && peer.device != null) {
            bluetoothHandler.connectToPeer(peer.device)
        }
    }

    private fun sendPendingMessages(peerUuid: String) {
        val pending = dbHelper.getPendingMessages(peerUuid)
        if (pending.isEmpty()) return
        Log.d("DataRepository", "Found ${pending.size} pending messages for $peerUuid. Sending sequentially...")

        for (msg in pending) {
            val success = bluetoothHandler.sendMessage(msg.second)
            if (success) {
                dbHelper.markMessageAsSent(msg.first)
                if (peerUuid == activeChatUuid) {
                    _chatMessages.update { current ->
                        current.map {
                            if (it.text == msg.second && it.status == "PENDING") {
                                it.copy(status = "SENT")
                            } else {
                                it
                            }
                        }
                    }
                }
            } else {
                Log.e("DataRepository", "Failed to send pending message ID ${msg.first}. Aborting queue.")
                return
            }
        }
    }

    override fun isPasscodeEnabled(): Boolean {
        return prefs.getBoolean("is_passcode_enabled", false)
    }

    override fun savePasscode(pin: String) {
        val hash = hashPin(pin)
        prefs.edit()
            .putString("passcode_hash", hash)
            .putBoolean("is_passcode_enabled", true)
            .apply()
    }

    override fun verifyPasscode(pin: String): Boolean {
        val savedHash = prefs.getString("passcode_hash", "") ?: ""
        if (savedHash.isEmpty()) return false
        return hashPin(pin) == savedHash
    }

    override fun disablePasscode() {
        prefs.edit()
            .remove("passcode_hash")
            .putBoolean("is_passcode_enabled", false)
            .apply()
    }

    private fun hashPin(pin: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { String.format("%02x", it) }
        } catch (e: Exception) {
            ""
        }
    }
}
