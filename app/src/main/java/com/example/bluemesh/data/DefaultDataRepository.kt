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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val contactsDbCache = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String, Boolean>>()

    // Message send queue: serializes all outgoing messages to prevent GATT write collisions
    private data class SendRequest(
        val payload: ByteArray,
        val activeChatUuid: String,
        val text: String,
        val timestamp: Long,
        val isPending: Boolean = false,
        val dbMessageId: Int = -1
    )
    private val sendQueue = Channel<SendRequest>(Channel.BUFFERED)

    private data class ProcessedMessageEntry(val senderUuid: String, val timestamp: Long)
    private val processedMessagesCache = java.util.Collections.synchronizedList(mutableListOf<ProcessedMessageEntry>())
    private val PROCESSED_CACHE_MAX_SIZE = 200

    private suspend fun isDuplicateMessage(senderUuid: String, timestamp: Long): Boolean = withContext(Dispatchers.IO) {
        if (isPasscodeEnabled()) {
            try {
                if (dbHelper.isDuplicateMessage(senderUuid, timestamp)) {
                    return@withContext true
                }
            } catch (e: Exception) {
                Log.e("DataRepository", "Error checking duplicate in DB", e)
            }
        }
        synchronized(processedMessagesCache) {
            if (processedMessagesCache.any { it.senderUuid == senderUuid && it.timestamp == timestamp }) {
                return@synchronized true
            }
            processedMessagesCache.add(ProcessedMessageEntry(senderUuid, timestamp))
            if (processedMessagesCache.size > PROCESSED_CACHE_MAX_SIZE) {
                processedMessagesCache.removeAt(0)
            }
            false
        }
    }

    override val discoveredPeers: StateFlow<List<BluetoothPeer>> = bluetoothHandler.discoveredPeers
    private val _activeConnectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    override val connectionStatus: StateFlow<ConnectionStatus> = _activeConnectionStatus.asStateFlow()
    private val _activeIsReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _activeIsReady.asStateFlow()
    override val isScanning: StateFlow<Boolean> = bluetoothHandler.isScanning
    override val isAdvertising: StateFlow<Boolean> = bluetoothHandler.isAdvertising

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    init {
        // User UUID is generated dynamically only when passcode is created or reset
        bluetoothHandler.onPeerConnectionStatusChanged = { peerUuid, status ->
            if (com.example.bluemesh.utils.uuidsMatch(peerUuid, activeChatUuid)) {
                _activeConnectionStatus.value = status
                _activeIsReady.value = (status == ConnectionStatus.CONNECTED)
                if (status == ConnectionStatus.DISCONNECTED && isPasscodeEnabled()) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            _chatMessages.value = dbHelper.getMessagesForContact(activeChatUuid)
                        } catch (e: Exception) {
                            Log.e("DataRepository", "Error loading messages on peer disconnect", e)
                        }
                    }
                }
            }
        }

        // Collect ACKs from peer and mark messages as SENT
        scope.launch(Dispatchers.IO) {
            try {
                bluetoothHandler.acks.collect { ackTimestamp ->
                    try {
                        dbHelper.markMessageAsSent(ackTimestamp)
                    } catch (e: Exception) {
                        Log.e("DataRepository", "Error updating ACK status in DB", e)
                    }

                    _chatMessages.update { current ->
                        current.map { msg ->
                            if (msg.isFromMe && msg.timestamp == ackTimestamp && msg.status != "SENT") {
                                msg.copy(status = "SENT")
                            } else {
                                msg
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DataRepository", "Error in acks collector flow", e)
            }
        }


        // Message send queue consumer: processes one message at a time
        scope.launch(Dispatchers.IO) {
            try {
                for (request in sendQueue) {
                    try {
                        // Check if message is already ACKed (marked as SENT) to avoid redundant sends
                        val isStillPending = try {
                            dbHelper.readableDatabase.rawQuery(
                                "SELECT status FROM QueuedMessages WHERE timestamp = ? AND is_from_me = 1",
                                arrayOf(request.timestamp.toString())
                            ).use { cursor ->
                                if (cursor.moveToFirst()) {
                                    cursor.getString(0) == "PENDING"
                                } else {
                                    _chatMessages.value.any { it.timestamp == request.timestamp && it.status == "PENDING" }
                                }
                            }
                        } catch (e: Exception) {
                            _chatMessages.value.any { it.timestamp == request.timestamp && it.status == "PENDING" }
                        }

                        if (!isStillPending) {
                            continue
                        }

                        if (bluetoothHandler.isPeerReady(request.activeChatUuid)) {
                            var sent = false
                            var retryCount = 0
                             while (!sent && retryCount < 3) {
                                 if (retryCount > 0) delay(200)
                                 val targetPeer = bluetoothHandler.discoveredPeers.value.find { com.example.bluemesh.utils.uuidsMatch(it.uuid, request.activeChatUuid) }
                                 val targetDevice = targetPeer?.device ?: bluetoothHandler.getConnectedDeviceByAddress(targetPeer?.address ?: "")
                                 sent = bluetoothHandler.sendMessageSuspend(request.payload, targetDevice)
                                 retryCount++
                             }
                            if (sent) {
                                try {
                                    dbHelper.markMessageAsSent(request.timestamp)
                                } catch (e: Exception) {
                                    Log.e("DataRepository", "Error updating status to SENT in DB", e)
                                }
                                _chatMessages.update { current ->
                                    current.map { msg ->
                                        if (msg.isFromMe && msg.timestamp == request.timestamp && msg.status != "SENT") {
                                            msg.copy(status = "SENT")
                                        } else {
                                            msg
                                        }
                                    }
                                }
                            } else {
                                sendPendingMessages(request.activeChatUuid)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("DataRepository", "Error processing sendQueue request", e)
                    }
                    // Small gap between messages to let BLE stack breathe
                    delay(100)
                }
            } catch (e: Exception) {
                Log.e("DataRepository", "sendQueue coroutine failed", e)
            }
        }


        scope.launch(Dispatchers.IO) {
            try {
                contactsDbCache.addAll(dbHelper.getContactsList())
                for (contact in contactsDbCache) {
                    dbHelper.getSessionKey(contact.first)?.let {
                        if (it.isNotEmpty()) sessionKeys[contact.first] = it.hexToBytes()
                    }
                }
            } catch (e: Exception) {
                Log.e("DataRepository", "Error loading session keys", e)
            }
        }

        bluetoothHandler.onBluetoothStateOn = {
            scope.launch {
                try {
                    startScan()
                    if (isDiscoverableEnabled()) {
                        val name = getDisplayName()
                        if (name.isNotEmpty()) {
                            startAdvertising(name)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DataRepository", "Error in onBluetoothStateOn invoke", e)
                }
            }
        }

        bluetoothHandler.onPeerDisconnectedCallback = { peerUuid ->
            scope.launch(Dispatchers.IO) {
                try {
                    sessionKeys.remove(peerUuid)
                    val shortUuid = com.example.bluemesh.utils.normalizeUuid(peerUuid).take(16)
                    sessionKeys.remove(shortUuid)
                    val matchingKeys = sessionKeys.keys.filter { com.example.bluemesh.utils.uuidsMatch(it, peerUuid) }
                    for (k in matchingKeys) {
                        sessionKeys.remove(k)
                    }
                    Log.d("DataRepository", "Cleared session key for peer: $peerUuid")
                } catch (e: Exception) {
                    Log.e("DataRepository", "Error clearing session key on disconnect", e)
                }
            }
        }

        bluetoothHandler.onPeerReadyCallback = { peerUuid, device ->
            scope.launch {
                try {
                    if (myKeyPair == null) myKeyPair = CryptoUtils.generateECKeyPair()
                     myKeyPair?.let {
                          bluetoothHandler.sendPublicKey(getUserUuid(), it.public.encoded, device)
                     }
                } catch (e: Exception) {
                    Log.e("DataRepository", "Error in PeerReadyCallback launch", e)
                }
                if (peerUuid.isNotEmpty()) {
                    sendPendingMessages(peerUuid)
                }
            }
        }

        bluetoothHandler.onKeyExchangeReceived = { senderUuid, peerPublicKeyBytes, device ->
            scope.launch(Dispatchers.IO) {
                try {
                    bluetoothHandler.updatePeerUuid(senderUuid, device.address)
                    
                    // Upgrade any matching contact UUID in database tables
                    try {
                        dbHelper.upgradeContactIfNeeded(senderUuid)
                        
                        // If there was an active session key for the short UUID in memory, migrate it to the full UUID
                        val contacts = contactsDbCache
                        val storedContact = contacts.find {
                            it.first != senderUuid && com.example.bluemesh.utils.uuidsMatch(it.first, senderUuid)
                        }
                        if (storedContact != null) {
                            val oldUuid = storedContact.first
                            val existingKey = sessionKeys[oldUuid]
                            if (existingKey != null) {
                                sessionKeys[senderUuid] = existingKey
                                sessionKeys.remove(oldUuid)
                            }
                        }
                        reloadContactsCache()
                    } catch (e: Exception) {
                        Log.e("DataRepository", "Error upgrading contact UUID on key exchange", e)
                    }

                    if (com.example.bluemesh.utils.uuidsMatch(activeChatUuid, senderUuid)) {
                        activeChatUuid = senderUuid
                        if (isPasscodeEnabled()) {
                            try {
                                _chatMessages.value = dbHelper.getMessagesForContact(senderUuid)
                            } catch (e: Exception) {
                                Log.e("DataRepository", "Error fetching messages for contact on uuid upgrade", e)
                            }
                        }
                    }
                    try {
                        if (myKeyPair == null) myKeyPair = CryptoUtils.generateECKeyPair()
                        myKeyPair?.let {
                             if (!bluetoothHandler.isClient()) {
                                 bluetoothHandler.sendPublicKey(getUserUuid(), it.public.encoded, device)
                             }
                            try {
                                val secret = CryptoUtils.generateSharedSecret(it.private, peerPublicKeyBytes)
                                val aesKey = CryptoUtils.deriveAESKey(secret)
                                sessionKeys[senderUuid] = aesKey
                                try {
                                    dbHelper.saveSessionKey(senderUuid, aesKey.toHex())
                                    if (contactsDbCache.none { com.example.bluemesh.utils.uuidsMatch(it.first, senderUuid) }) {
                                        contactsDbCache.add(Triple(senderUuid, "Temp_Peer", false))
                                    }
                                } catch (e: Exception) {
                                    Log.e("DataRepository", "Error saving session key to database", e)
                                }
                                sendPendingMessages(senderUuid)
                            } catch (e: Exception) {
                                Log.e("DataRepository", "Failed shared secret computation", e)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("DataRepository", "Error in KeyExchangeReceived coroutine", e)
                    }
                } catch (e: Exception) {
                    Log.e("DataRepository", "Error in onKeyExchangeReceived callback", e)
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                bluetoothHandler.messages.collect { message ->
                    try {
                        val connectedAddress = message.senderAddress
                        val peer = if (connectedAddress != null) {
                            bluetoothHandler.discoveredPeers.value.find { it.address == connectedAddress }
                        } else null
                        
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
                        if (textBytes.size >= 6 && textBytes[0] == 1.toByte() && textBytes[1] == 2.toByte()) {
                            // Encrypted payload
                            val ciphertext = textBytes.copyOfRange(6, textBytes.size)
                            val key = getSessionKeyForUuid(senderUuid)
                            if (key != null) {
                                try {
                                    val decrypted = CryptoUtils.decryptAESGCM(ciphertext, key)
                                    if (decrypted.size >= 8) {
                                        msgTimestamp = ByteBuffer.wrap(decrypted).long
                                        finalMessageText = String(decrypted, 8, decrypted.size - 8, Charsets.UTF_8)
                                    } else {
                                        finalMessageText = String(decrypted, Charsets.UTF_8)
                                    }
                                } catch (e: Exception) {
                                    Log.e("DataRepository", "Decryption failed for message from $senderUuid", e)
                                    finalMessageText = "[Decryption Failed]"
                                }
                            } else {
                                Log.w("DataRepository", "Missing session key for encrypted message from $senderUuid")
                                finalMessageText = "[Encrypted Message]"
                            }
                        } else {
                            // Plaintext
                            if (textBytes.size >= 8) {
                                msgTimestamp = ByteBuffer.wrap(textBytes).long
                                finalMessageText = String(textBytes, 8, textBytes.size - 8, Charsets.UTF_8)
                            } else {
                                finalMessageText = String(textBytes, Charsets.UTF_8)
                            }
                        }

                          if (!message.isFromMe && senderUuid.isNotEmpty()) {
                              val peerDevice = if (connectedAddress != null) {
                                  bluetoothHandler.getConnectedDeviceByAddress(connectedAddress)
                              } else {
                                  bluetoothHandler.discoveredPeers.value.find { com.example.bluemesh.utils.uuidsMatch(it.uuid, senderUuid) }?.device
                              }
                              if (isDuplicateMessage(senderUuid, msgTimestamp)) {
                                  scope.launch {
                                      bluetoothHandler.sendAck(msgTimestamp, peerDevice)
                                  }
                                  return@collect
                              }
                              scope.launch {
                                  bluetoothHandler.sendAck(msgTimestamp, peerDevice)
                              }
                          }

                        if (isPasscodeEnabled() && senderUuid.isNotEmpty() && !message.isFromMe) {
                            try {
                                dbHelper.insertMessage(senderUuid, finalMessageText, msgTimestamp, "RECEIVED", false)
                            } catch (e: Exception) {
                                Log.e("DataRepository", "Error inserting received message to database", e)
                            }
                        }

                        if (senderUuid == activeChatUuid && !message.isFromMe) {
                            _chatMessages.update { current -> current + message.copy(text = finalMessageText, timestamp = msgTimestamp) }
                        }
                    } catch (e: Exception) {
                        Log.e("DataRepository", "Error handling incoming message collect", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("DataRepository", "Error in messages collector flow", e)
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                bluetoothHandler.connectionStatus.collect { status ->
                    try {
                        if (status == ConnectionStatus.DISCONNECTED) {
                            if (activeChatUuid.isNotEmpty() && isPasscodeEnabled()) {
                                try {
                                    _chatMessages.value = dbHelper.getMessagesForContact(activeChatUuid)
                                } catch (e: Exception) {
                                    Log.e("DataRepository", "Error loading messages on disconnect", e)
                                }
                            }
                            startScan()
                            if (isDiscoverableEnabled()) {
                                val name = getDisplayName()
                                if (name.isNotEmpty()) {
                                    startAdvertising(name)
                                }
                            }
                        } else if (status == ConnectionStatus.CONNECTING || status == ConnectionStatus.CONNECTED || status == ConnectionStatus.SYNCHRONIZING) {
                            // Keep scanning and advertising active to support concurrent multi-user connections
                        }
                    } catch (e: Exception) {
                        Log.e("DataRepository", "Error processing connection status change", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("DataRepository", "Error in connectionStatus flow", e)
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                discoveredPeers.collect { peers ->
                    try {
                        if (isPasscodeEnabled()) {
                            for (peer in peers) {
                                if (isContact(peer.uuid)) {
                                    val stored = getContacts().find {
                                        com.example.bluemesh.utils.uuidsMatch(it.uuid, peer.uuid)
                                    }
                                    if (stored != null) {
                                        val sNorm = com.example.bluemesh.utils.normalizeUuid(stored.uuid)
                                        val pNorm = com.example.bluemesh.utils.normalizeUuid(peer.uuid)
                                        if (sNorm.length == 16 && pNorm.length > 16) {
                                            try {
                                                dbHelper.upgradeContactUuid(stored.uuid, peer.uuid)
                                                reloadContactsCache()
                                            } catch (e: Exception) {
                                                Log.e("DataRepository", "Error upgrading contact UUID in database", e)
                                            }
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

                        if (!bluetoothHandler.isClient()) {
                            var attemptedConnection = false
                            // 1. High priority: reconnect to active chat uuid if discovered
                            if (activeChatUuid.isNotEmpty() && !bluetoothHandler.isPeerConnected(activeChatUuid)) {
                                val activePeer = peers.find { peer ->
                                    com.example.bluemesh.utils.uuidsMatch(activeChatUuid, peer.uuid)
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

                            // 2. Reconnect to peers with pending messages
                            if (!attemptedConnection) {
                                for (peer in peers) {
                                    if (bluetoothHandler.isPeerConnected(peer.uuid)) continue
                                    val hasPending = try {
                                        dbHelper.getPendingMessages(peer.uuid).isNotEmpty()
                                    } catch (e: Exception) {
                                        Log.e("DataRepository", "Error reading pending messages", e)
                                        false
                                    }
                                    if (hasPending) {
                                        val lastAttempt = lastConnectionAttempts[peer.uuid] ?: 0L
                                        if (System.currentTimeMillis() - lastAttempt > 5000) {
                                            lastConnectionAttempts[peer.uuid] = System.currentTimeMillis()
                                            Log.d("DataRepository", "Auto-reconnecting to peer with pending messages: ${peer.uuid}")
                                            connectToPeerByUuid(peer.uuid)
                                            attemptedConnection = true
                                            break
                                        }
                                    }
                                }
                            }

                            // 3. Connect to any detected peer automatically to receive messages
                            if (!attemptedConnection) {
                                for (peer in peers) {
                                    if (bluetoothHandler.isPeerConnected(peer.uuid)) continue
                                    val lastAttempt = lastConnectionAttempts[peer.uuid] ?: 0L
                                    if (System.currentTimeMillis() - lastAttempt > 10000) {
                                        lastConnectionAttempts[peer.uuid] = System.currentTimeMillis()
                                        Log.d("DataRepository", "Auto-connecting to detected peer to listen for messages: ${peer.uuid}")
                                        connectToPeerByUuid(peer.uuid)
                                        break
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("DataRepository", "Error processing discoveredPeers updates", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("DataRepository", "Error in discoveredPeers flow", e)
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
        if (text.length > 300) return false
        val timestamp = System.currentTimeMillis()
        val messageId = (System.currentTimeMillis() and 0x7FFFFFFFL).toInt()

        if (!bluetoothHandler.isPeerReady(activeChatUuid)) {
            bluetoothHandler.advertiseMeshMessage(messageId, getUserUuid(), activeChatUuid, text)
        }

        // Optimistically add to chat UI as PENDING, will be updated to SENT by queue consumer
        scope.launch(Dispatchers.IO) {
            try {
                dbHelper.insertMessage(activeChatUuid, text, timestamp, "PENDING", true)
            } catch (e: Exception) {
                Log.e("DataRepository", "Error inserting offline message", e)
            }
        }
        _chatMessages.update { current -> current + ChatMessage(text, true, timestamp, "PENDING") }

        val sessionKey = getSessionKeyForUuid(activeChatUuid)
        val payload = if (sessionKey != null) {
            val rawPayload = BluetoothHandler.encodePayload(timestamp, text)
            val ciphertext = CryptoUtils.encryptAESGCM(rawPayload, sessionKey)
            
            ByteArray(ciphertext.size + 6).apply {
                this[0] = 1
                this[1] = 2 // flags = 2
                val encryptedMessageId = messageId or java.lang.Integer.MIN_VALUE
                val idBytes = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(encryptedMessageId).array()
                System.arraycopy(idBytes, 0, this, 2, 4)
                System.arraycopy(ciphertext, 0, this, 6, ciphertext.size)
            }
        } else {
            BluetoothHandler.encodePayload(timestamp, text)
        }

        // Enqueue for serialized sending
        scope.launch(Dispatchers.IO) {
            try {
                sendQueue.send(SendRequest(
                    payload = payload,
                    activeChatUuid = activeChatUuid,
                    text = text,
                    timestamp = timestamp
                ))
            } catch (e: Exception) {
                Log.e("DataRepository", "Error sending message to queue channel", e)
            }
        }
        return true
    }

    override fun clearDiscoveredPeers() {
        bluetoothHandler.clearDiscoveredPeers()
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
            scope.launch(Dispatchers.IO) {
                try {
                    val whereClause = if (isPasscodeEnabled()) {
                        "contact_uuid = ?"
                    } else {
                        "contact_uuid = ? AND status != 'PENDING'"
                    }
                    dbHelper.writableDatabase.delete("QueuedMessages", whereClause, arrayOf(activeChatUuid))
                } catch (e: Exception) {
                    Log.e("DataRepository", "Error clearing chat history", e)
                }
            }
        }
    }

    override fun getUserUuid(): String = prefs.getString("user_uuid", "") ?: ""

    override fun getContacts(): List<BluetoothPeer> {
        val currentDiscovered = bluetoothHandler.discoveredPeers.value
        return contactsDbCache.filter { !it.second.startsWith("Temp_") }.map { (uuid, name, isOfficialDb) ->
            val discoveredPeer = currentDiscovered.find {
                com.example.bluemesh.utils.uuidsMatch(uuid, it.uuid)
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
            com.example.bluemesh.utils.uuidsMatch(uuid, it.uuid)
        }?.isOfficial ?: false
        val normalized = com.example.bluemesh.utils.normalizeUuid(uuid)
        contactsDbCache.removeAll { com.example.bluemesh.utils.normalizeUuid(it.first) == normalized }
        contactsDbCache.add(Triple(uuid, name, isOfficial))
        scope.launch(Dispatchers.IO) {
            try {
                dbHelper.saveContact(uuid, name, isOfficial)
            } catch (e: Exception) {
                Log.e("DataRepository", "Error saving contact", e)
            }
        }
    }

    override fun deleteContact(uuid: String) {
        val normalized = com.example.bluemesh.utils.normalizeUuid(uuid)
        contactsDbCache.removeAll { com.example.bluemesh.utils.normalizeUuid(it.first) == normalized }
        scope.launch(Dispatchers.IO) {
            try {
                dbHelper.deleteContact(uuid)
            } catch (e: Exception) {
                Log.e("DataRepository", "Error deleting contact", e)
            }
        }
    }

    override fun isContact(uuid: String): Boolean {
        return contactsDbCache.any {
            com.example.bluemesh.utils.uuidsMatch(it.first, uuid) && !it.second.startsWith("Temp_")
        }
    }

    override fun setActiveChat(uuid: String) {
        activeChatUuid = uuid
        if (uuid.isEmpty()) {
            disconnect()
            _activeConnectionStatus.value = ConnectionStatus.DISCONNECTED
            _activeIsReady.value = false
        } else {
            val status = bluetoothHandler.getConnectionStatusForPeer(uuid)
            _activeConnectionStatus.value = status
            _activeIsReady.value = (status == ConnectionStatus.CONNECTED)
        }
        _chatMessages.value = if (isPasscodeEnabled()) {
            dbHelper.getMessagesForContact(uuid)
        } else {
            // In ephemeral mode, only load pending messages that are yet to be sent
            dbHelper.getMessagesForContact(uuid).filter { it.status == "PENDING" }
        }
    }

    override fun connectToPeerByUuid(uuid: String) {
        bluetoothHandler.discoveredPeers.value.find { com.example.bluemesh.utils.uuidsMatch(it.uuid, uuid) }?.device?.let {
            bluetoothHandler.connectToPeer(it)
        }
    }

    private suspend fun sendPendingMessages(peerUuid: String) = withContext(Dispatchers.IO) {
        delay(500)
        val canonicalUuid = dbHelper.getContactsList().find {
            com.example.bluemesh.utils.uuidsMatch(it.first, peerUuid)
        }?.first ?: peerUuid

        val pending = dbHelper.getPendingMessages(canonicalUuid)
        if (pending.isEmpty()) return@withContext

        for (msg in pending) {
            val sessionKey = getSessionKeyForUuid(canonicalUuid)
            if (isPasscodeEnabled() && sessionKey == null) {
                // Wait for E2EE key exchange to complete before sending
                continue
            }
            val msgId = (System.currentTimeMillis() and 0x7FFFFFFFL).toInt()
            val payload = if (sessionKey != null) {
                val rawPayload = BluetoothHandler.encodePayload(msg.third, msg.second)
                val ciphertext = CryptoUtils.encryptAESGCM(rawPayload, sessionKey)
                
                ByteArray(ciphertext.size + 6).apply {
                    this[0] = 1
                    this[1] = 2 // flags = 2
                    val encryptedMessageId = msgId or java.lang.Integer.MIN_VALUE
                    val idBytes = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(encryptedMessageId).array()
                    System.arraycopy(idBytes, 0, this, 2, 4)
                    System.arraycopy(ciphertext, 0, this, 6, ciphertext.size)
                }
            } else {
                BluetoothHandler.encodePayload(msg.third, msg.second)
            }
            // Enqueue through the serialized send queue
            sendQueue.send(SendRequest(
                payload = payload,
                activeChatUuid = canonicalUuid,
                text = msg.second,
                timestamp = msg.third,
                isPending = true,
                dbMessageId = msg.first
            ))
        }
    }

    private fun getOrGenerateSalt(): String {
        var salt = prefs.getString("passcode_salt", "") ?: ""
        if (salt.isEmpty()) {
            val random = java.security.SecureRandom()
            val saltBytes = ByteArray(16)
            random.nextBytes(saltBytes)
            salt = saltBytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString("passcode_salt", salt).apply()
        }
        return salt
    }

    private fun hashPin(pin: String): String = try {
        val salt = getOrGenerateSalt()
        val digest = MessageDigest.getInstance("SHA-256")
        val input = salt + pin
        digest.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { "" }

    override fun getLockoutTimeRemaining(): Long {
        val lockoutUntil = prefs.getLong("lockout_until", 0L)
        val now = System.currentTimeMillis()
        return if (lockoutUntil > now) lockoutUntil - now else 0L
    }

    override fun isPasscodeEnabled(): Boolean = prefs.getBoolean("is_passcode_enabled", false)

    override fun savePasscode(pin: String) {
        prefs.edit()
            .putInt("failed_attempts", 0)
            .putLong("lockout_until", 0L)
            .remove("passcode_salt")
            .apply()
        val currentUuid = getUserUuid()
        val finalUuid = if (currentUuid.isEmpty()) UUID.randomUUID().toString() else currentUuid
        prefs.edit()
            .putString("passcode_hash", hashPin(pin))
            .putBoolean("is_passcode_enabled", true)
            .putString("user_uuid", finalUuid)
            .apply()
        getDisplayName().let {
            if (it.isNotEmpty()) {
                bluetoothHandler.stopAdvertising()
                bluetoothHandler.startAdvertising(it)
            }
        }
    }

    override fun verifyPasscode(pin: String): Boolean {
        val lockoutUntil = prefs.getLong("lockout_until", 0L)
        val now = System.currentTimeMillis()
        if (now < lockoutUntil) {
            return false
        }
        val savedHash = prefs.getString("passcode_hash", "") ?: ""
        val correct = savedHash.isNotEmpty() && hashPin(pin) == savedHash
        if (correct) {
            prefs.edit().putInt("failed_attempts", 0).putLong("lockout_until", 0L).apply()
            return true
        } else {
            val attempts = prefs.getInt("failed_attempts", 0) + 1
            prefs.edit().putInt("failed_attempts", attempts).apply()
            val delayMs = when {
                attempts >= 7 -> 15 * 60 * 1000L
                attempts == 6 -> 5 * 60 * 1000L
                attempts == 5 -> 30 * 1000L
                attempts == 4 -> 5 * 1000L
                else -> 0L
            }
            if (delayMs > 0) {
                prefs.edit().putLong("lockout_until", System.currentTimeMillis() + delayMs).apply()
            }
            return false
        }
    }

    override fun disablePasscode() {
        prefs.edit()
            .remove("passcode_hash")
            .remove("passcode_salt")
            .remove("failed_attempts")
            .remove("lockout_until")
            .putBoolean("is_passcode_enabled", false)
            .putString("user_uuid", UUID.randomUUID().toString())
            .apply()
        getDisplayName().let {
            if (it.isNotEmpty()) {
                bluetoothHandler.stopAdvertising()
                bluetoothHandler.startAdvertising(it)
            }
        }
    }

    override fun isShareLocationEnabled(): Boolean = prefs.getBoolean("is_share_location_enabled", true)

    override fun setShareLocationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("is_share_location_enabled", enabled).apply()
        if (isDiscoverableEnabled()) {
            bluetoothHandler.stopAdvertising()
            val name = getDisplayName()
            if (name.isNotEmpty()) {
                bluetoothHandler.startAdvertising(name)
            }
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
            if (bluetoothHandler is com.example.bluemesh.bluetooth.BluetoothHandler) {
                bluetoothHandler.stopAdvertisingWithGoodbye()
            } else {
                stopAdvertising()
            }
        }
    }

    override fun resetUserUuid(passcode: String): Boolean {
        if (verifyPasscode(passcode)) {
            prefs.edit().putString("user_uuid", java.util.UUID.randomUUID().toString()).apply()
            if (isDiscoverableEnabled()) {
                bluetoothHandler.stopAdvertising()
                bluetoothHandler.startAdvertising(getDisplayName())
            }
            return true
        }
        return false
    }



    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray {
        val result = ByteArray(length / 2)
        for (i in 0 until length step 2) {
            result[i / 2] = substring(i, i + 2).toInt(16).toByte()
        }
        return result
    }

    private suspend fun reloadContactsCache() = withContext(Dispatchers.IO) {
        try {
            val list = dbHelper.getContactsList()
            contactsDbCache.clear()
            contactsDbCache.addAll(list)
        } catch (e: Exception) {
            Log.e("DataRepository", "Error reloading contacts cache", e)
        }
    }

    private fun getSessionKeyForUuid(uuid: String): ByteArray? {
        val direct = sessionKeys[uuid]
        if (direct != null) return direct

        val matchingKeys = sessionKeys.keys.filter {
            com.example.bluemesh.utils.uuidsMatch(it, uuid)
        }
        if (matchingKeys.size == 1) {
            return sessionKeys[matchingKeys.first()]
        } else if (matchingKeys.size > 1) {
            Log.w("DataRepository", "Ambiguous session keys found in memory for UUID $uuid: $matchingKeys")
        }

        try {
            val dbKeyHex = dbHelper.getSessionKey(uuid)
            if (!dbKeyHex.isNullOrEmpty()) {
                val dbKey = dbKeyHex.hexToBytes()
                sessionKeys[uuid] = dbKey
                return dbKey
            }
            val stored = getContacts().find { com.example.bluemesh.utils.uuidsMatch(it.uuid, uuid) }
            if (stored != null) {
                val dbKeyHex2 = dbHelper.getSessionKey(stored.uuid)
                if (!dbKeyHex2.isNullOrEmpty()) {
                    val dbKey = dbKeyHex2.hexToBytes()
                    sessionKeys[uuid] = dbKey
                    sessionKeys[stored.uuid] = dbKey
                    return dbKey
                }
            }
        } catch (e: Exception) {
            Log.e("DataRepository", "Error loading session key from database for $uuid", e)
        }
        return null
    }

    private fun uuidMatchesHash(uuid: String, hash: Int): Boolean {
        if (uuid.hashCode() == hash) return true
        val normalized = com.example.bluemesh.utils.normalizeUuid(uuid)
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
