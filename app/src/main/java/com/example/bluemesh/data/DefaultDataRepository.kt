package com.example.bluemesh.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.bluemesh.bluetooth.BluetoothHandler
import com.example.bluemesh.data.models.BluetoothPeer
import com.example.bluemesh.data.models.ChatMessage
import com.example.bluemesh.data.models.ConnectionStatus
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DefaultDataRepository private constructor(private val context: Context) : DataRepository {

    companion object {
        @Volatile private var INSTANCE: DefaultDataRepository? = null
        fun getInstance(context: Context): DefaultDataRepository =
            INSTANCE ?: synchronized(this) { INSTANCE ?: DefaultDataRepository(context.applicationContext).also { INSTANCE = it } }
    }

    private val prefs = context.getSharedPreferences("bluemesh_prefs", Context.MODE_PRIVATE)
    private val bluetoothHandler = BluetoothHandler(context)
    private val scope = CoroutineScope(Dispatchers.Default)
    private val dbHelper = OfflineQueueDbHelper(context)
    private var activeChatUuid: String = ""
    private val sessionKeys = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private var myKeyPair: java.security.KeyPair? = null
    private val lastConnectionAttempts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private data class ProcessedMessageEntry(val senderUuid: String, val text: String, val timestamp: Long)
    private val processedMessagesCache = java.util.Collections.synchronizedList(mutableListOf<ProcessedMessageEntry>())
    private val PROCESSED_CACHE_MAX_SIZE = 50

    private fun isDuplicateMessageContent(senderUuid: String, text: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(processedMessagesCache) {
            processedMessagesCache.removeAll { now - it.timestamp > 8000 }
            if (processedMessagesCache.any { it.senderUuid == senderUuid && it.text == text }) return true
            processedMessagesCache.add(ProcessedMessageEntry(senderUuid, text, now))
            if (processedMessagesCache.size > PROCESSED_CACHE_MAX_SIZE) processedMessagesCache.removeAt(0)
            return false
        }
    }

    override val discoveredPeers: StateFlow<List<BluetoothPeer>> = bluetoothHandler.discoveredPeers
    override val connectionStatus: StateFlow<ConnectionStatus> = bluetoothHandler.connectionStatus
    override val isReady: StateFlow<Boolean> = bluetoothHandler.isReady
    override val isScanning: StateFlow<Boolean> = bluetoothHandler.isScanning
    override val isAdvertising: StateFlow<Boolean> = bluetoothHandler.isAdvertising

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    init {
        if (isPasscodeEnabled()) {
            if (getUserUuid().isEmpty()) {
                prefs.edit().putString("user_uuid", UUID.randomUUID().toString()).apply()
            }
        } else {
            prefs.edit().putString("user_uuid", UUID.randomUUID().toString()).apply()
        }

        scope.launch {
            try {
                for (contact in dbHelper.getContactsList()) {
                    dbHelper.getSessionKey(contact.first)?.let {
                        if (it.isNotEmpty()) sessionKeys[contact.first] = it.hexToBytes()
                    }
                }
            } catch (e: Exception) {
                Log.e("DataRepository", "Error loading session keys", e)
            }
        }

        bluetoothHandler.onPeerReadyCallback = { peerUuid ->
            scope.launch {
                if (myKeyPair == null) myKeyPair = CryptoUtils.generateECKeyPair()
                myKeyPair?.let {
                    bluetoothHandler.sendPublicKey(getUserUuid(), it.public.encoded)
                }
            }
        }

        bluetoothHandler.onKeyExchangeReceived = { senderUuid, peerPublicKeyBytes ->
            bluetoothHandler.updatePeerUuid(senderUuid)
            val activeNorm = activeChatUuid.replace("-", "").lowercase()
            val senderNorm = senderUuid.replace("-", "").lowercase()
            
            if (isPasscodeEnabled()) {
                val shortSenderUuid = if (senderNorm.length >= 16) senderNorm.take(16) else senderNorm
                val contacts = dbHelper.getContactsList()
                val storedContact = contacts.find {
                    val storedNorm = it.first.replace("-", "").lowercase()
                    storedNorm == shortSenderUuid || (storedNorm.length == 16 && shortSenderUuid.startsWith(storedNorm))
                }
                if (storedContact != null) {
                    val storedUuid = storedContact.first
                    val storedNorm = storedUuid.replace("-", "").lowercase()
                    if (storedNorm.length == 16 && senderNorm.length > 16) {
                        dbHelper.upgradeContactUuid(storedUuid, senderUuid)
                        val existingKey = sessionKeys[storedUuid]
                        if (existingKey != null) {
                            sessionKeys[senderUuid] = existingKey
                            sessionKeys.remove(storedUuid)
                        }
                    }
                }
            }

            if (activeNorm.length == 16 && senderNorm.startsWith(activeNorm)) {
                activeChatUuid = senderUuid
                if (isPasscodeEnabled()) {
                    _chatMessages.value = dbHelper.getMessagesForContact(senderUuid)
                }
            }
            scope.launch {
                if (myKeyPair == null) myKeyPair = CryptoUtils.generateECKeyPair()
                myKeyPair?.let {
                    try {
                        val secret = CryptoUtils.generateSharedSecret(it.private, peerPublicKeyBytes)
                        val aesKey = CryptoUtils.deriveAESKey(secret)
                        sessionKeys[senderUuid] = aesKey
                        if (isPasscodeEnabled()) dbHelper.saveSessionKey(senderUuid, aesKey.toHex())
                        sendPendingMessages(senderUuid)
                    } catch (e: Exception) {
                        Log.e("DataRepository", "Failed shared secret computation", e)
                    }
                }
            }
        }

        scope.launch {
            bluetoothHandler.messages.collect { message ->
                val connectedAddress = bluetoothHandler.getConnectedDeviceAddress()
                val peer = bluetoothHandler.discoveredPeers.value.find { it.address == connectedAddress }
                
                val senderUuid = if (message.senderHash != null) {
                    val myUuidHash = getUserUuid().hashCode()
                    if (message.recipientHash != myUuidHash && message.recipientHash != 0) return@collect
                    val resolved = if (activeChatUuid.isNotEmpty() && uuidMatchesHash(activeChatUuid, message.senderHash)) {
                        activeChatUuid
                    } else {
                        getContacts().find { uuidMatchesHash(it.uuid, message.senderHash) }?.uuid
                            ?: bluetoothHandler.discoveredPeers.value.find { uuidMatchesHash(it.uuid, message.senderHash) }?.uuid
                    }
                    resolved ?: "mesh_${message.senderHash}"
                } else {
                    peer?.uuid ?: activeChatUuid
                }

                val textBytes = message.text.toByteArray(Charsets.ISO_8859_1)
                var msgTimestamp = message.timestamp
                var finalMessageText = ""

                if (textBytes.size >= 8) {
                    msgTimestamp = ByteBuffer.wrap(textBytes).long
                    finalMessageText = String(textBytes, 8, textBytes.size - 8, Charsets.UTF_8)
                } else {
                    finalMessageText = String(textBytes, Charsets.UTF_8)
                }

                if (senderUuid.isNotEmpty() && !message.isFromMe) {
                    if (isDuplicateMessageContent(senderUuid, finalMessageText)) return@collect
                }

                if (isPasscodeEnabled() && senderUuid.isNotEmpty() && !message.isFromMe) {
                    dbHelper.insertMessage(senderUuid, finalMessageText, msgTimestamp, "RECEIVED", false)
                }

                if (senderUuid == activeChatUuid && !message.isFromMe) {
                    _chatMessages.update { current -> current + message.copy(text = finalMessageText, timestamp = msgTimestamp) }
                }
            }
        }

        scope.launch {
            bluetoothHandler.connectionStatus.collect { status ->
                if (status == ConnectionStatus.DISCONNECTED && activeChatUuid.isNotEmpty() && isPasscodeEnabled()) {
                    _chatMessages.value = dbHelper.getMessagesForContact(activeChatUuid)
                }
            }
        }

        scope.launch {
            discoveredPeers.collect { peers ->
                if (isPasscodeEnabled()) {
                    for (peer in peers) {
                        if (isContact(peer.uuid)) {
                            val stored = getContacts().find {
                                val s = it.uuid.replace("-", "").lowercase()
                                val p = peer.uuid.replace("-", "").lowercase()
                                s == p || (p.length == 16 && s.startsWith(p)) || (s.length == 16 && p.startsWith(s))
                            }
                            if (stored != null) {
                                val sNorm = stored.uuid.replace("-", "").lowercase()
                                val pNorm = peer.uuid.replace("-", "").lowercase()
                                if (sNorm.length == 16 && pNorm.length > 16) {
                                    dbHelper.upgradeContactUuid(stored.uuid, peer.uuid)
                                    val existingKey = sessionKeys[stored.uuid]
                                    if (existingKey != null) {
                                        sessionKeys[peer.uuid] = existingKey
                                        sessionKeys.remove(stored.uuid)
                                    }
                                } else if (stored.name != peer.name || stored.isOfficial != peer.isOfficial) {
                                    saveContact(stored.uuid, peer.name)
                                }
                            }
                        }
                    }
                }

                if (connectionStatus.value == ConnectionStatus.DISCONNECTED) {
                    var attemptedConnection = false
                    // 1. High priority: reconnect to active chat uuid if discovered
                    if (activeChatUuid.isNotEmpty()) {
                        val activePeer = peers.find { peer ->
                            val s = activeChatUuid.replace("-", "").lowercase()
                            val p = peer.uuid.replace("-", "").lowercase()
                            s == p || (p.length == 16 && s.startsWith(p)) || (s.length == 16 && p.startsWith(s))
                        }
                        if (activePeer != null) {
                            val lastAttempt = lastConnectionAttempts[activePeer.uuid] ?: 0L
                            if (System.currentTimeMillis() - lastAttempt > 5000) {
                                lastConnectionAttempts[activePeer.uuid] = System.currentTimeMillis()
                                Log.d("DataRepository", "Auto-reconnecting to active chat peer: ${activePeer.uuid}")
                                connectToPeerByUuid(activePeer.uuid)
                                attemptedConnection = true
                            }
                        }
                    }

                    // 2. Reconnect to contacts with pending messages (if passcode enabled)
                    if (!attemptedConnection && isPasscodeEnabled()) {
                        for (peer in peers) {
                            if (isContact(peer.uuid) && dbHelper.getPendingMessages(peer.uuid).isNotEmpty()) {
                                val lastAttempt = lastConnectionAttempts[peer.uuid] ?: 0L
                                if (System.currentTimeMillis() - lastAttempt > 5000) {
                                    lastConnectionAttempts[peer.uuid] = System.currentTimeMillis()
                                    Log.d("DataRepository", "Auto-reconnecting to contact with pending messages: ${peer.uuid}")
                                    connectToPeerByUuid(peer.uuid)
                                    break
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun startScan() = bluetoothHandler.startScanning()
    override fun stopScan() = bluetoothHandler.stopScanning()
    override fun startAdvertising(name: String) = bluetoothHandler.startAdvertising(name)
    override fun stopAdvertising() = bluetoothHandler.stopAdvertising()
    override fun connectToPeer(device: BluetoothDevice) = bluetoothHandler.connectToPeer(device)
    override fun disconnect() = bluetoothHandler.disconnect()

    override fun sendMessage(text: String): Boolean {
        if (activeChatUuid.isEmpty()) return false
        val timestamp = System.currentTimeMillis()
        val peer = bluetoothHandler.discoveredPeers.value.find { it.uuid == activeChatUuid }
        val messageId = (System.currentTimeMillis() and 0x7FFFFFFFL).toInt()
        var sent = false

        bluetoothHandler.advertiseMeshMessage(messageId, getUserUuid(), activeChatUuid, text)
        if (peer != null && bluetoothHandler.isReady.value) {
            sent = bluetoothHandler.sendMessage(BluetoothHandler.encodePayload(timestamp, text))
        }

        if (isPasscodeEnabled()) {
            dbHelper.insertMessage(activeChatUuid, text, timestamp, if (sent) "SENT" else "PENDING", true)
            if (!sent) {
                scope.launch {
                    sendPendingMessages(activeChatUuid)
                }
            }
        }
        _chatMessages.update { current -> current + ChatMessage(text, true, timestamp, if (sent) "SENT" else "PENDING") }
        return true
    }

    override fun getDisplayName(): String = prefs.getString("display_name", "") ?: ""

    override fun saveDisplayName(name: String) {
        prefs.edit().putString("display_name", name).apply()
        if (isAdvertising.value) {
            bluetoothHandler.stopAdvertising()
            bluetoothHandler.startAdvertising(name)
        }
    }

    override fun clearChatHistory() {
        _chatMessages.value = emptyList()
        if (activeChatUuid.isNotEmpty()) {
            dbHelper.writableDatabase.delete("QueuedMessages", "contact_uuid = ?", arrayOf(activeChatUuid))
        }
    }

    override fun getUserUuid(): String = prefs.getString("user_uuid", "") ?: ""

    override fun getContacts(): List<BluetoothPeer> {
        val list = if (isPasscodeEnabled()) dbHelper.getContactsList() else emptyList()
        val currentDiscovered = bluetoothHandler.discoveredPeers.value
        return list.map { (uuid, name, isOfficialDb) ->
            val discoveredPeer = currentDiscovered.find {
                val s = uuid.replace("-", "").lowercase()
                val p = it.uuid.replace("-", "").lowercase()
                s == p || (p.length == 16 && s.startsWith(p)) || (s.length == 16 && p.startsWith(s))
            }
            BluetoothPeer(
                address = discoveredPeer?.address ?: "", name = name, device = discoveredPeer?.device,
                lastSeen = discoveredPeer?.lastSeen ?: 0L, uuid = uuid,
                hasPasscode = discoveredPeer?.hasPasscode ?: isOfficialDb,
                isOfficial = isOfficialDb || (discoveredPeer?.isOfficial == true)
            )
        }
    }

    override fun saveContact(uuid: String, name: String) {
        val isOfficial = bluetoothHandler.discoveredPeers.value.find {
            val s = uuid.replace("-", "").lowercase()
            val p = it.uuid.replace("-", "").lowercase()
            s == p || (p.length == 16 && s.startsWith(p)) || (s.length == 16 && p.startsWith(s))
        }?.isOfficial ?: false
        dbHelper.saveContact(uuid, name, isOfficial)
    }

    override fun deleteContact(uuid: String) = dbHelper.deleteContact(uuid)

    override fun isContact(uuid: String): Boolean {
        if (dbHelper.isContact(uuid)) return true
        val normalized = uuid.replace("-", "").lowercase()
        return dbHelper.getContactsList().any {
            val dbNorm = it.first.replace("-", "").lowercase()
            dbNorm == normalized || (normalized.length == 16 && dbNorm.startsWith(normalized)) || (dbNorm.length == 16 && normalized.startsWith(dbNorm))
        }
    }

    override fun setActiveChat(uuid: String) {
        activeChatUuid = uuid
        _chatMessages.value = if (isPasscodeEnabled()) dbHelper.getMessagesForContact(uuid) else emptyList()
    }

    override fun connectToPeerByUuid(uuid: String) {
        bluetoothHandler.discoveredPeers.value.find { it.uuid == uuid }?.device?.let {
            bluetoothHandler.connectToPeer(it)
        }
    }

    private suspend fun sendPendingMessages(peerUuid: String) {
        delay(500)
        val canonicalUuid = dbHelper.getContactsList().find {
            val s = it.first.replace("-", "").lowercase()
            val p = peerUuid.replace("-", "").lowercase()
            s == p || (p.length == 16 && s.startsWith(p)) || (s.length == 16 && p.startsWith(s))
        }?.first ?: peerUuid

        val pending = dbHelper.getPendingMessages(canonicalUuid)
        if (pending.isEmpty()) return

        for (msg in pending) {
            var success = false
            var retryCount = 0
            while (!success && retryCount < 3) {
                if (retryCount > 0) delay(300)
                success = bluetoothHandler.sendMessage(BluetoothHandler.encodePayload(msg.third, msg.second))
                retryCount++
            }
            if (success) {
                dbHelper.markMessageAsSent(msg.first)
                val activeNorm = activeChatUuid.replace("-", "").lowercase()
                val targetNorm = canonicalUuid.replace("-", "").lowercase()
                if (activeNorm == targetNorm || (targetNorm.length == 16 && activeNorm.startsWith(targetNorm)) || (activeNorm.length == 16 && targetNorm.startsWith(activeNorm))) {
                    _chatMessages.update { current ->
                        current.map { if (it.text == msg.second && it.status == "PENDING") it.copy(status = "SENT") else it }
                    }
                }
                delay(100)
            } else {
                break
            }
        }
    }

    override fun isPasscodeEnabled(): Boolean = prefs.getBoolean("is_passcode_enabled", false)

    override fun savePasscode(pin: String) {
        prefs.edit().putString("passcode_hash", hashPin(pin)).putBoolean("is_passcode_enabled", true)
            .putString("user_uuid", UUID.randomUUID().toString()).apply()
        getDisplayName().let {
            if (it.isNotEmpty()) {
                bluetoothHandler.stopAdvertising()
                bluetoothHandler.startAdvertising(it)
            }
        }
    }

    override fun verifyPasscode(pin: String): Boolean {
        val savedHash = prefs.getString("passcode_hash", "") ?: ""
        return savedHash.isNotEmpty() && hashPin(pin) == savedHash
    }

    override fun disablePasscode() {
        prefs.edit().remove("passcode_hash").putBoolean("is_passcode_enabled", false)
            .putString("user_uuid", UUID.randomUUID().toString()).apply()
        getDisplayName().let {
            if (it.isNotEmpty()) {
                bluetoothHandler.stopAdvertising()
                bluetoothHandler.startAdvertising(it)
            }
        }
    }

    override fun isShareLocationEnabled(): Boolean = prefs.getBoolean("is_share_location_enabled", false)

    override fun setShareLocationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("is_share_location_enabled", enabled).apply()
        if (enabled) {
            bluetoothHandler.stopAdvertising()
            bluetoothHandler.startAdvertising(getDisplayName())
        }
    }

    override fun isDiscoverableEnabled(): Boolean = prefs.getBoolean("is_discoverable_enabled", true)

    override fun setDiscoverableEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("is_discoverable_enabled", enabled).apply()
        if (enabled) {
            val name = getDisplayName()
            if (name.isNotEmpty()) {
                startAdvertising(name)
            }
        } else {
            stopAdvertising()
        }
    }

    private fun hashPin(pin: String): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.digest(pin.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { "" }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray {
        val result = ByteArray(length / 2)
        for (i in 0 until length step 2) {
            result[i / 2] = substring(i, i + 2).toInt(16).toByte()
        }
        return result
    }

    private fun getSessionKeyForUuid(uuid: String): ByteArray? {
        val direct = sessionKeys[uuid]
        if (direct != null) return direct
        val normalized = uuid.replace("-", "").lowercase()
        val matchKey = sessionKeys.keys.find {
            val kNorm = it.replace("-", "").lowercase()
            kNorm == normalized || (normalized.length == 16 && kNorm.startsWith(normalized)) || (kNorm.length == 16 && normalized.startsWith(kNorm))
        }
        return if (matchKey != null) sessionKeys[matchKey] else null
    }

    private fun uuidMatchesHash(uuid: String, hash: Int): Boolean {
        if (uuid.hashCode() == hash) return true
        val normalized = uuid.replace("-", "").lowercase()
        if (normalized.hashCode() == hash) return true
        val shortUuid = if (normalized.length >= 16) normalized.take(16) else normalized
        if (shortUuid.hashCode() == hash) return true
        if (uuid.startsWith("mesh_")) {
            val meshId = uuid.substringAfter("mesh_").toIntOrNull()
            if (meshId == hash) return true
        }
        return false
    }
}
