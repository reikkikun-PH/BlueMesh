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
        val isPasscode = isPasscodeEnabled()
        if (isPasscode) {
            val uuidStr = prefs.getString("user_uuid", "") ?: ""
            if (uuidStr.isEmpty()) {
                prefs.edit().putString("user_uuid", UUID.randomUUID().toString()).apply()
            }
        } else {
            // Non-password user: generate a new temporary random UUID on every launch (disposable ID)
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
                
                val senderUuid = if (message.senderHash != null) {
                    val myUuidHash = getUserUuid().hashCode()
                    if (message.recipientHash != myUuidHash && message.recipientHash != 0) {
                        Log.d("DataRepository", "Mesh: Dropped message because recipient hash ${message.recipientHash} did not match my hash $myUuidHash")
                        return@collect
                    }
                    val resolved = getContacts().find { it.uuid.hashCode() == message.senderHash }?.uuid
                        ?: bluetoothHandler.discoveredPeers.value.find { it.uuid.hashCode() == message.senderHash }?.uuid
                    
                    if (resolved != null) {
                        resolved
                    } else {
                        val tempUuid = "mesh_${message.senderHash}"
                        if (isPasscodeEnabled()) {
                            if (!isContact(tempUuid)) {
                                saveContact(tempUuid, "Mesh Peer ${message.senderHash.toLong() and 0xFFFFFFFFL}")
                            }
                        }
                        tempUuid
                    }
                } else {
                    peer?.uuid ?: activeChatUuid
                }

                if (isPasscodeEnabled() && senderUuid.isNotEmpty() && !message.isFromMe) {
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
                    if (activeChatUuid.isNotEmpty() && isPasscodeEnabled()) {
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

        // Always broadcast the message via the flooding mesh protocol
        val messageId = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()
        val senderUuid = getUserUuid()
        val recipientUuid = activeChatUuid
        bluetoothHandler.advertiseMeshMessage(messageId, senderUuid, recipientUuid, text)

        // If direct GATT is ready, also send it for instant delivery
        if (peer != null && bluetoothHandler.isReady.value) {
            bluetoothHandler.sendMessage(text)
        }

        // Save to DB as SENT only if passcode is enabled
        if (isPasscodeEnabled()) {
            dbHelper.insertMessage(activeChatUuid, text, timestamp, "SENT", true)
        }
        _chatMessages.update { current -> current + ChatMessage(text, true, timestamp, "SENT") }
        return true
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
        val list = if (isPasscodeEnabled()) dbHelper.getContactsList() else emptyList()
        val currentDiscovered = bluetoothHandler.discoveredPeers.value
        return list.map { (uuid, name) ->
            val discoveredPeer = currentDiscovered.find { it.uuid == uuid }
            val address = discoveredPeer?.address ?: ""
            BluetoothPeer(
                address = address,
                name = name,
                device = discoveredPeer?.device,
                lastSeen = discoveredPeer?.lastSeen ?: 0L,
                uuid = uuid,
                hasPasscode = discoveredPeer?.hasPasscode ?: false
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
        _chatMessages.value = if (isPasscodeEnabled()) {
            dbHelper.getMessagesForContact(uuid)
        } else {
            emptyList()
        }
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
