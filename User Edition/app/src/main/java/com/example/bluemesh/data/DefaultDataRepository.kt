package com.example.bluemesh.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.Context
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

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
    private var lastChatPeerUuid: String = ""
    private val sessionKeys = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private var myKeyPair: java.security.KeyPair? = null
    private val lastConnectionAttempts = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val contactsDbCache = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String, Boolean>>()
    private val pendingMeshMessageIds = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    // Per-peer send queues: independent channels so messages to different peers don't block each other
    private data class SendRequest(
        val payload: ByteArray,
        val activeChatUuid: String,
        val text: String,
        val timestamp: Long,
        val messageId: Int,
        val isPending: Boolean = false,
        val dbMessageId: Int = -1,
        val retryCount: Int = 0
    )
    private val perPeerSendQueues = ConcurrentHashMap<String, Channel<SendRequest>>()
    private val sendQueueConsumers = ConcurrentHashMap<String, Job>()

    private val pendingAcks = java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val seenKeyExchangeHashes = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val seenStore = SeenMessageStore(400)
    private val peerReconnectTimestamps = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun encryptPayload(timestamp: Long, text: String, sessionKey: ByteArray, messageId: Int): ByteArray {
        val rawPayload = text.toByteArray(Charsets.UTF_8)
        val ciphertext = CryptoUtils.encryptAESGCM(rawPayload, sessionKey)
        val header = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN).apply {
            put(1.toByte())
            put(2.toByte()) // E2EE flag
            putInt(messageId)
            putLong(timestamp)
        }.array()
        return ByteArray(header.size + ciphertext.size).apply {
            System.arraycopy(header, 0, this, 0, header.size)
            System.arraycopy(ciphertext, 0, this, header.size, ciphertext.size)
        }
    }

    private fun encodePlaintextPayload(timestamp: Long, text: String, messageId: Int): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN).apply {
            put(1.toByte())
            put(3.toByte()) // Plaintext flag
            putInt(messageId)
            putLong(timestamp)
        }.array()
        return ByteArray(header.size + textBytes.size).apply {
            System.arraycopy(header, 0, this, 0, header.size)
            System.arraycopy(textBytes, 0, this, header.size, textBytes.size)
        }
    }

    private fun getMeshMessageDedupId(senderHash: Int, messageId: Int, text: String): String {
        val stableId = messageId and 0xFFFFFF00.toInt()
        val raw = "$senderHash:$stableId:$text"
        return try {
            val digest = java.security.MessageDigest.getInstance("MD5")
            val hashBytes = digest.digest(raw.toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            raw
        }
    }

    private fun markMessageSent(timestamp: Long, latencyMs: Long? = null) {
        scope.launch(Dispatchers.IO) {
            try { dbHelper.markMessageAsSent(timestamp) } catch (e: Exception) { Log.e("DataRepository", "Error marking msg sent in DB", e) }
        }
        val finalLatency = latencyMs ?: (System.currentTimeMillis() - timestamp)
        _chatMessages.update { current ->
            current.map { msg ->
                if (msg.isFromMe && msg.timestamp == timestamp && msg.status != "SENT") msg.copy(status = "SENT", latencyMs = finalLatency) else msg
            }
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

    private fun reloadChatMessagesFromDb(contactUuid: String, filterPending: Boolean = false) {
        val currentLatencyMap = _chatMessages.value.associate { (it.timestamp to it.isFromMe) to it.latencyMs }
        val dbMessages = if (filterPending) {
            dbHelper.getMessagesForContact(contactUuid).filter { it.status == "PENDING" }
        } else {
            dbHelper.getMessagesForContact(contactUuid)
        }
        _chatMessages.value = dbMessages.map { msg ->
            val saved = currentLatencyMap[msg.timestamp to msg.isFromMe]
            if (saved != null) msg.copy(latencyMs = saved) else msg
        }
    }

    init {
        bluetoothHandler.setMyUuid(getUserUuid())

        // User UUID is generated dynamically only when passcode is created or reset
        bluetoothHandler.onPeerConnectionStatusChanged = { peerUuid, status ->
            if (com.example.bluemesh.utils.uuidsMatch(peerUuid, activeChatUuid)) {
                _activeConnectionStatus.value = status
                _activeIsReady.value = (status == ConnectionStatus.CONNECTED)
                if (status == ConnectionStatus.DISCONNECTED && isPasscodeEnabled()) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            reloadChatMessagesFromDb(activeChatUuid)
                        } catch (e: Exception) {
                            Log.e("DataRepository", "Error loading messages on peer disconnect", e)
                        }
                    }
                }
            }
        }

        bluetoothHandler.setPeerNameResolver { uuid ->
            getContacts().find { com.example.bluemesh.utils.uuidsMatch(it.uuid, uuid) }?.name
                ?: bluetoothHandler.discoveredPeers.value.find { com.example.bluemesh.utils.uuidsMatch(it.uuid, uuid) }?.name
                ?: ""
        }

        scope.launch(Dispatchers.IO) {
            try {
                dbHelper.resolveStalePendingMessages()
            } catch (e: Exception) {
                Log.e("DataRepository", "Error resolving stale PENDING messages", e)
            }
        }

        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(120000)
                try {
                    dbHelper.resolveStalePendingMessages()
                } catch (e: Exception) {
                    Log.e("DataRepository", "Error in periodic PENDING sweep", e)
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                val recentMessages = dbHelper.getLastReceivedMessages(400)
                for (msg in recentMessages) {
                    val contactUuid = msg.first
                    val timestamp = msg.second
                    val text = msg.third
                    
                    val sHash = if (contactUuid.startsWith("mesh_")) {
                        contactUuid.substringAfter("mesh_").toIntOrNull() ?: contactUuid.hashCode()
                    } else {
                        contactUuid.hashCode()
                    }
                    val mId = (timestamp and 0x7FFFFFFFL).toInt()
                    
                    val meshKey = getMeshMessageDedupId(sHash, mId, text)
                    seenStore.add(meshKey)
                    if (!contactUuid.startsWith("mesh_")) {
                        seenStore.add("gatt:$contactUuid:$mId")
                    }
                }
            } catch (e: Exception) {
                Log.e("DataRepository", "Error populating seen store from DB", e)
            }
        }

        // Mesh ACK handler: when an ACK comes back through the relay, mark the message as SENT
        bluetoothHandler.onMeshAckReceived = { originalMessageId, _, recipientHash ->
            if (recipientHash == getUserUuid().hashCode()) {
                val timestamp = pendingMeshMessageIds.remove(originalMessageId)
                if (timestamp != null) {
                    markMessageSent(timestamp)
                    pendingAcks.remove(timestamp)?.let { it.complete(true) }
                    bluetoothHandler.cancelMeshAdvertise()
                    Log.d("DataRepository", "Mesh ACK received for msgId=$originalMessageId, marked SENT")
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                bluetoothHandler.acks.collect { (ackTimestamp, latencyMs) ->
                    markMessageSent(ackTimestamp, latencyMs)
                    pendingAcks.remove(ackTimestamp)?.let { it.complete(true) }
                }
            } catch (e: Exception) {
                Log.e("DataRepository", "Error in acks collector flow", e)
            }
        }


        scope.launch(Dispatchers.IO) {
            val peerReadyStates = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
            val healCooldowns = java.util.concurrent.ConcurrentHashMap<String, Long>()
            while (true) {
                try {
                    val now = System.currentTimeMillis()
                    val currentChat = activeChatUuid
                    val peerChecks = discoveredPeers.value.map { peer ->
                        async {
                            val peerUuid = peer.uuid
                            if (peer.isRelayed) {
                                if (!bluetoothHandler.isPeerConnected(peerUuid) && !bluetoothHandler.isPeerReady(peerUuid)) {
                                    Log.d("DataRepository", "Relayed peer $peerUuid not connected, trying direct GATT")
                                    connectToPeerByUuid(peerUuid)
                                }
                                return@async
                            }
                            val isReady = bluetoothHandler.isPeerReady(peerUuid)
                            val wasReady = peerReadyStates[peerUuid]
                            if (wasReady == true && !isReady) {
                                Log.w("DataRepository", "Peer $peerUuid lost readiness, reconnecting.")
                                val staleAddr = bluetoothHandler.discoveredPeers.value.find { com.example.bluemesh.utils.uuidsMatch(it.uuid, peerUuid) }?.address
                                if (staleAddr != null) bluetoothHandler.disconnectClient(staleAddr)
                                connectToPeerByUuid(peerUuid)
                            }
                            peerReadyStates[peerUuid] = isReady
                        }
                    }
                    peerChecks.forEach { it.await() }
                    if (currentChat.isNotEmpty()) {
                        val peerStatus = bluetoothHandler.getConnectionStatusForPeer(currentChat)
                        val peerDiscovered = discoveredPeers.value.any { com.example.bluemesh.utils.uuidsMatch(it.uuid, currentChat) }
                        if (peerDiscovered && peerStatus == ConnectionStatus.DISCONNECTED) {
                            val lastReconnect = healCooldowns["reconnect_$currentChat"] ?: 0L
                            if (now - lastReconnect > 15000) {
                                Log.d("DataRepository", "Peer $currentChat discovered but disconnected, reconnecting.")
                                healCooldowns["reconnect_$currentChat"] = now
                                connectToPeerByUuid(currentChat)
                            }
                        } else if (!peerDiscovered && peerStatus == ConnectionStatus.DISCONNECTED) {
                            val lastReconnect = healCooldowns["rediscover_$currentChat"] ?: 0L
                            if (now - lastReconnect > 15000) {
                                Log.d("DataRepository", "Peer $currentChat not discovered, retrying.")
                                healCooldowns["rediscover_$currentChat"] = now
                                val cachedAddr = bluetoothHandler.getCachedAddressForUuid(currentChat)
                                if (cachedAddr != null) {
                                    bluetoothHandler.connectToPeerByAddress(cachedAddr)
                                }
                                bluetoothHandler.startScanning()
                            }
                        }
                        if (peerDiscovered) {
                            val peerAddr = discoveredPeers.value.find { com.example.bluemesh.utils.uuidsMatch(it.uuid, currentChat) }?.address ?: ""
                            if (peerAddr.isNotEmpty() && bluetoothHandler.isStuckInSynchronizing(peerAddr)) {
                                val lastHeal = healCooldowns[currentChat] ?: 0L
                                if (now - lastHeal > 30000) {
                                    Log.w("DataRepository", "Peer $currentChat stuck in SYNCHRONIZING >15s, healing.")
                                    healCooldowns[currentChat] = now
                                    bluetoothHandler.disconnectFromPeer(currentChat)
                                    delay(500)
                                    connectToPeerByUuid(currentChat)
                                }
                            }
                        }
                        val hasStuckPending = _chatMessages.value.any { it.isFromMe && it.status == "PENDING" && (now - it.timestamp > 20000) }
                        if (hasStuckPending) {
                            val lastHeal = healCooldowns[currentChat] ?: 0L
                            if (peerStatus == ConnectionStatus.CONNECTED || peerStatus == ConnectionStatus.SYNCHRONIZING) {
                                if (now - lastHeal > 30000) {
                                    Log.w("DataRepository", "Stuck PENDING >20s while $peerStatus, healing.")
                                    healCooldowns[currentChat] = now
                                    bluetoothHandler.disconnectFromPeer(currentChat)
                                    delay(500)
                                    connectToPeerByUuid(currentChat)
                                } else {
                                    Log.d("DataRepository", "Stuck PENDING >20s, skipping heal (cooldown).")
                                }
                            } else if (peerStatus == ConnectionStatus.DISCONNECTED) {
                                Log.w("DataRepository", "Stuck PENDING >20s, peer DISCONNECTED. Reconnecting.")
                                connectToPeerByUuid(currentChat)
                            }
                        }
                    }
                    delay(if (currentChat.isNotEmpty() && bluetoothHandler.getConnectionStatusForPeer(currentChat) != ConnectionStatus.CONNECTED) 2000 else 5000)
                } catch (e: Exception) {
                    Log.e("DataRepository", "Error in health monitor loop", e)
                    delay(5000)
                }
            }
        }

        // Per-peer send queue consumers: each peer has an independent channel so sends don't block each other
        // Consumers are launched lazily when the first message to a peer is enqueued.


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

        // Re-queue any pending outgoing messages from previous session
        requeuePendingMessages()

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
            // Reconnect to active chat peer immediately (don't wait 5s for health monitor)
            val currentChat = activeChatUuid
            if (currentChat.isNotEmpty()) {
                scope.launch(Dispatchers.IO) {
                    delay(3000) // Give BT stack + scan time to settle + peer to re-advertise
                    connectToPeerByUuid(currentChat)
                }
            }
        }

        bluetoothHandler.onPeerDisconnectedCallback = { peerUuid ->
            scope.launch(Dispatchers.IO) {
                try {
                    if (!bluetoothHandler.isPeerConnected(peerUuid)) {
                        sessionKeys.remove(peerUuid)
                        val shortUuid = com.example.bluemesh.utils.normalizeUuid(peerUuid).take(16)
                        sessionKeys.remove(shortUuid)
                        val matchingKeys = sessionKeys.keys.filter { com.example.bluemesh.utils.uuidsMatch(it, peerUuid) }
                        for (k in matchingKeys) {
                            sessionKeys.remove(k)
                        }
                        synchronized(seenKeyExchangeHashes) {
                            seenKeyExchangeHashes.remove(peerUuid)
                            seenKeyExchangeHashes.remove(shortUuid)
                            val matchingHashes = seenKeyExchangeHashes.keys.filter { com.example.bluemesh.utils.uuidsMatch(it, peerUuid) }
                            for (k in matchingHashes) {
                                seenKeyExchangeHashes.remove(k)
                            }
                        }
                        Log.d("DataRepository", "Cleared session key for peer: $peerUuid (fully disconnected)")
                    } else {
                        Log.d("DataRepository", "Peer $peerUuid disconnected from one path, keeping session key (still connected via other path)")
                    }
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
                // Pending messages are sent after key exchange completes
                // to avoid encrypting with a stale key (see onKeyExchangeReceived)
            }
        }

        bluetoothHandler.onKeyExchangeReceived = { senderUuid, peerPublicKeyBytes, device ->
            Log.d("DataRepository", "KEY_X received uuid=$senderUuid addr=${device.address} keySize=${peerPublicKeyBytes.size}")
            val newAddress = device.address
            val oldAddress = bluetoothHandler.discoveredPeers.value.find { com.example.bluemesh.utils.uuidsMatch(it.uuid, senderUuid) }?.address
            // Always update address mapping — the peer may have changed addresses even if the key is the same
            bluetoothHandler.updatePeerUuid(senderUuid, device.address)
            if (oldAddress != null && oldAddress != newAddress && com.example.bluemesh.utils.uuidsMatch(activeChatUuid, senderUuid)) {
                Log.d("DataRepository", "Peer $senderUuid changed address $oldAddress → $newAddress, refreshing connection")
                bluetoothHandler.disconnectClient(oldAddress)
                scope.launch {
                    connectToPeerByUuid(senderUuid)
                }
            }
            val keyHash = java.util.Arrays.hashCode(peerPublicKeyBytes)
            val prevHash = synchronized(seenKeyExchangeHashes) {
                seenKeyExchangeHashes.put(senderUuid, keyHash)
            }
            if (prevHash != null && prevHash == keyHash && getSessionKeyForUuid(senderUuid) != null) {
                Log.d("DataRepository", "KEY_X dedup: skipping duplicate key exchange from $senderUuid")
                // Still send public key if address changed — peer may have rotated and needs our key
                if (oldAddress != null && oldAddress != newAddress) {
                    scope.launch {
                        try {
                            if (myKeyPair == null) myKeyPair = CryptoUtils.generateECKeyPair()
                            myKeyPair?.let {
                                bluetoothHandler.sendPublicKey(getUserUuid(), it.public.encoded, device)
                            }
                            sendPendingMessages(senderUuid)
                        } catch (e: Exception) {
                            Log.e("DataRepository", "Error sending public key on deduped KEY_X", e)
                        }
                    }
                }
            } else {
                scope.launch(Dispatchers.IO) {
                    try {
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
                                reloadChatMessagesFromDb(senderUuid)
                            } catch (e: Exception) {
                                Log.e("DataRepository", "Error fetching messages for contact on uuid upgrade", e)
                            }
                        }
                    }
                    try {
                        if (myKeyPair == null) myKeyPair = CryptoUtils.generateECKeyPair()
                        myKeyPair?.let {
                            bluetoothHandler.sendPublicKey(getUserUuid(), it.public.encoded, device)
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
                            val uuidFromAddr = connectedAddress?.let { bluetoothHandler.getUuidByAddress(it) }
                            if (uuidFromAddr != null) uuidFromAddr
                            else peer?.uuid
                                ?: "gatt_${connectedAddress ?: "unknown"}"
                        }

                        val textBytes = message.text.toByteArray(Charsets.ISO_8859_1)
                        var msgTimestamp = message.timestamp
                        var msgId: Int? = null
                        var finalMessageText = ""

                        // Resolve UUID from connected address for fallback matching and key lookup
                        val resolvedUuid = connectedAddress?.let { addr ->
                            bluetoothHandler.discoveredPeers.value.find { it.address == addr || it.device?.address == addr }?.uuid
                                ?: bluetoothHandler.getUuidByAddress(addr)
                        }

                        // When senderUuid is gatt_<addr>, try resolved UUID for session key lookup
                        val effectiveSenderUuid = if (senderUuid.startsWith("gatt_") && resolvedUuid != null) resolvedUuid else senderUuid

                        if (message.senderHash != null) {
                            // Mesh message
                            msgId = message.messageId
                            finalMessageText = message.text
                        } else if (textBytes.size >= 14 && textBytes[0] == 1.toByte() && (textBytes[1] == 2.toByte() || textBytes[1] == 3.toByte())) {
                            val headerBuffer = ByteBuffer.wrap(textBytes, 0, 14).order(ByteOrder.BIG_ENDIAN)
                            headerBuffer.get() // Skip type (1)
                            val formatType = headerBuffer.get() // format (2 or 3)
                            msgId = headerBuffer.int
                            msgTimestamp = headerBuffer.long

                            if (formatType == 2.toByte()) {
                                // Encrypted payload
                                val ciphertext = textBytes.copyOfRange(14, textBytes.size)
                                val key = getSessionKeyForUuid(effectiveSenderUuid)
                                if (key != null) {
                                    try {
                                        val decrypted = CryptoUtils.decryptAESGCM(ciphertext, key)
                                        finalMessageText = String(decrypted, Charsets.UTF_8)
                                    } catch (e: Exception) {
                                        Log.e("DataRepository", "Decryption failed for message from $effectiveSenderUuid, dropping silently", e)
                                        triggerKeyExchangeForPeer(effectiveSenderUuid)
                                        return@collect
                                    }
                                } else {
                                    Log.w("DataRepository", "Missing session key for encrypted message from $effectiveSenderUuid (senderUuid=$senderUuid), dropping silently")
                                    triggerKeyExchangeForPeer(effectiveSenderUuid)
                                    return@collect
                                }
                            } else {
                                // Plaintext
                                finalMessageText = String(textBytes, 14, textBytes.size - 14, Charsets.UTF_8)
                            }
                        } else {
                            // Legacy plaintext fallback
                            if (textBytes.size >= 8) {
                                msgTimestamp = ByteBuffer.wrap(textBytes, 0, 8).order(ByteOrder.BIG_ENDIAN).long
                                finalMessageText = String(textBytes, 8, textBytes.size - 8, Charsets.UTF_8)
                            } else {
                                finalMessageText = String(textBytes, Charsets.UTF_8)
                            }
                            msgId = (msgTimestamp and 0x7FFFFFFFL).toInt()
                        }

                        // Calculate dedup keys
                        val senderHash = if (senderUuid.startsWith("mesh_")) {
                            senderUuid.substringAfter("mesh_").toIntOrNull() ?: senderUuid.hashCode()
                        } else {
                            senderUuid.hashCode()
                        }

                        val meshKey = getMeshMessageDedupId(senderHash, msgId ?: 0, finalMessageText)
                        val gattKey = if (message.senderHash == null) {
                            "gatt:$senderUuid:$msgId"
                        } else null

                        // Check duplicate: true if either key is in the seen store
                        val isMeshDup = seenStore.isDuplicateAndTouch(meshKey)
                        val isGattDup = gattKey?.let { seenStore.isDuplicateAndTouch(it) } ?: false
                        val isDup = isMeshDup || isGattDup

                        // Send ACK even if duplicate (to stop peer retries)
                        if (!message.isFromMe && senderUuid.isNotEmpty()) {
                            // Log incoming message for debugging drop issues
                            if (senderUuid.startsWith("gatt_") || !com.example.bluemesh.utils.uuidsMatch(senderUuid, activeChatUuid)) {
                                val uuidOk = com.example.bluemesh.utils.uuidsMatch(senderUuid, activeChatUuid)
                                val resolvedOk = resolvedUuid != null && com.example.bluemesh.utils.uuidsMatch(resolvedUuid, activeChatUuid)
                                val mapDump = bluetoothHandler.getUuidMapDump()
                                Log.d("DataRepository", "RXDROP senderUuid=$senderUuid activeChatUuid=$activeChatUuid addr=$connectedAddress uuidOk=$uuidOk resolvedUuid=$resolvedUuid resolvedOk=$resolvedOk uuidMap=$mapDump")
                            }
                            val peerDeviceObj = if (connectedAddress != null) {
                                bluetoothHandler.getConnectedDeviceByAddress(connectedAddress)
                            } else {
                                bluetoothHandler.discoveredPeers.value.find { com.example.bluemesh.utils.uuidsMatch(it.uuid, senderUuid) }?.device
                            }
                            scope.launch {
                                var ackSent = false
                                var ackRetries = 0
                                while (!ackSent && ackRetries < 3) {
                                    ackSent = bluetoothHandler.sendAck(msgTimestamp, peerDeviceObj)
                                    if (!ackSent) {
                                        ackRetries++
                                        delay(200)
                                    }
                                }
                            }
                            // Broadcast mesh ACK via relay so sender gets confirmation even without GATT
                            val ackMsgId = message.messageId ?: msgId
                            val ackSenderHash = message.senderHash ?: senderHash
                            if (ackSenderHash != 0 && ackMsgId != null) {
                                scope.launch {
                                    bluetoothHandler.advertiseMeshMessage(
                                        (System.currentTimeMillis() and 0x7FFFFFFFL).toInt(),
                                        getUserUuid(), senderUuid, "ACK|$ackMsgId"
                                    )
                                }
                            }
                            if (isDup) {
                                return@collect
                            }
                        } else if (isDup) {
                            return@collect
                        }

                        // If not a duplicate, ensure BOTH keys are registered in the seen store
                        seenStore.add(meshKey)
                        if (gattKey != null) {
                            seenStore.add(gattKey)
                        }

                        // Proceed with non-duplicate processing
                        if (isPasscodeEnabled() && senderUuid.isNotEmpty() && !message.isFromMe) {
                            try {
                                val dbContactUuid = effectiveSenderUuid.ifEmpty { senderUuid }
                                dbHelper.insertMessage(dbContactUuid, finalMessageText, msgTimestamp, "RECEIVED", false)
                            } catch (e: Exception) {
                                Log.e("DataRepository", "Error inserting received message to database", e)
                            }
                        }

                        val knownPeer = resolvedUuid != null || (connectedAddress != null && bluetoothHandler.getUuidByAddress(connectedAddress) != null)
                        val matchesActiveChat = if (activeChatUuid.isNotEmpty()) {
                            com.example.bluemesh.utils.uuidsMatch(senderUuid, activeChatUuid) ||
                            (resolvedUuid != null && com.example.bluemesh.utils.uuidsMatch(resolvedUuid, activeChatUuid))
                        } else {
                            knownPeer
                        }
                        if (matchesActiveChat && !message.isFromMe) {
                            _chatMessages.update { current ->
                                current + message.copy(
                                    text = finalMessageText,
                                    timestamp = msgTimestamp,
                                    latencyMs = null,
                                    messageId = msgId
                                )
                            }
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
                                    reloadChatMessagesFromDb(activeChatUuid)
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

                        val disconnectedPeers = peers.filter { peer ->
                            !bluetoothHandler.isPeerConnected(peer.uuid) && !bluetoothHandler.isPeerReady(peer.uuid)
                        }
                        if (disconnectedPeers.isNotEmpty()) {
                            // Only auto-connect to the active chat peer to avoid tearing down
                            // an active GATT connection on a different peer mid-send
                            val activePeer = if (activeChatUuid.isNotEmpty()) {
                                disconnectedPeers.firstOrNull { peer ->
                                    com.example.bluemesh.utils.uuidsMatch(activeChatUuid, peer.uuid)
                                }
                            } else null
                            if (activePeer != null) {
                                val lastAttempt = lastConnectionAttempts[activePeer.uuid] ?: 0L
                                if (System.currentTimeMillis() - lastAttempt > 5000) {
                                    lastConnectionAttempts[activePeer.uuid] = System.currentTimeMillis()
                                    Log.d("DataRepository", "Auto-connecting to active chat peer: ${activePeer.uuid}")
                                    connectToPeerByUuid(activePeer.uuid)
                                }
                            } else if (activeChatUuid.isEmpty()) {
                                // No active chat: try a peer with pending messages
                                val pendingPeer = disconnectedPeers.firstOrNull { peer ->
                                    try {
                                        dbHelper.getPendingMessages(peer.uuid).isNotEmpty()
                                    } catch (e: Exception) {
                                        Log.e("DataRepository", "Error reading pending messages", e)
                                        false
                                    }
                                }
                                if (pendingPeer != null) {
                                    val lastAttempt = lastConnectionAttempts[pendingPeer.uuid] ?: 0L
                                    if (System.currentTimeMillis() - lastAttempt > 5000) {
                                        lastConnectionAttempts[pendingPeer.uuid] = System.currentTimeMillis()
                                        Log.d("DataRepository", "Auto-connecting to peer with pending: ${pendingPeer.uuid}")
                                        connectToPeerByUuid(pendingPeer.uuid)
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

    override fun onPermissionsReady() {
        bluetoothHandler.ensureGattServerRunning()
        bluetoothHandler.stopScanning()
        bluetoothHandler.startScanning()
        if (isDiscoverableEnabled()) {
            val name = getDisplayName()
            if (name.isNotEmpty()) {
                bluetoothHandler.startAdvertising(name)
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
        val messageId = (timestamp and 0x7FFFFFFFL).toInt()

        if (!bluetoothHandler.isPeerReady(activeChatUuid)) {
            Log.d("DataRepository", "Peer not ready on send. Triggering immediate reconnect.")
            connectToPeerByUuid(activeChatUuid)
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
        if (isPasscodeEnabled() && sessionKey == null) {
            triggerKeyExchangeForPeer(activeChatUuid)
        }
        val payload = if (sessionKey != null) {
            encryptPayload(timestamp, text, sessionKey, messageId)
        } else {
            encodePlaintextPayload(timestamp, text, messageId)
        }

        // Track pending mesh message for ACK matching
        pendingMeshMessageIds[messageId] = timestamp

        // Enqueue for per-peer serialized sending (each peer has its own channel + consumer)
        scope.launch(Dispatchers.IO) {
            try {
                val peerUuid = activeChatUuid
                val channel = perPeerSendQueues.getOrPut(peerUuid) {
                    Channel<SendRequest>(Channel.BUFFERED).also { ch ->
                        launchPerPeerConsumer(peerUuid, ch)
                    }
                }
                channel.send(SendRequest(
                    payload = payload,
                    activeChatUuid = peerUuid,
                    text = text,
                    timestamp = timestamp,
                    messageId = messageId
                ))
            } catch (e: Exception) {
                Log.e("DataRepository", "Error sending message to per-peer queue", e)
            }
        }

        // Broadcast via BLE mesh advertisements so ESP32 relays can extend range
        if (sessionKey == null) {
            scope.launch {
                bluetoothHandler.advertiseMeshMessage(messageId, getUserUuid(), activeChatUuid, text)
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
                    val db = dbHelper.writableDatabase
                    // Resolve the canonical UUID so we delete messages stored under any UUID representation
                    val resolvedUuid = dbHelper.resolveCanonicalUuid(activeChatUuid)
                    db.delete("QueuedMessages", "contact_uuid = ?", arrayOf(resolvedUuid))
                    if (resolvedUuid != activeChatUuid) {
                        db.delete("QueuedMessages", "contact_uuid = ?", arrayOf(activeChatUuid))
                    }
                    // Also clear pending ACK tracking for this contact
                    val timestampsToRemove = pendingAcks.keys.filter { ts ->
                        _chatMessages.value.any { it.timestamp == ts }
                    }
                    timestampsToRemove.forEach { pendingAcks.remove(it) }
                } catch (e: Exception) {
                    Log.e("DataRepository", "Error clearing chat history", e)
                }
            }
        }
    }

    override fun deleteOutgoingMessage(timestamp: Long) {
        scope.launch(Dispatchers.IO) {
            try {
                dbHelper.deleteMessageByTimestamp(timestamp)
            } catch (e: Exception) {
                Log.e("DataRepository", "Error deleting outgoing message", e)
            }
        }
        pendingAcks.remove(timestamp)?.complete(false)
        pendingMeshMessageIds.entries.removeAll { it.value == timestamp }
        _chatMessages.update { current ->
            current.filter { !(it.isFromMe && it.timestamp == timestamp) }
        }
    }

    private fun markMessageAsFailed(timestamp: Long) {
        scope.launch(Dispatchers.IO) {
            try {
                dbHelper.markMessageAsFailed(timestamp)
            } catch (e: Exception) {
                Log.e("DataRepository", "Error marking message as failed", e)
            }
        }
        _chatMessages.update { current ->
            current.map { msg ->
                if (msg.isFromMe && msg.timestamp == timestamp && msg.status != "SENT")
                    msg.copy(status = "FAILED") else msg
            }
        }
    }

    private fun requeuePendingMessages() {
        scope.launch(Dispatchers.IO) {
            try {
                val allPending = dbHelper.getAllPendingMessages()
                for ((contactUuid, pendingList) in allPending) {
                    if (pendingList.isEmpty()) continue
                    val sessionKey = getSessionKeyForUuid(contactUuid)
                    for (msg in pendingList) {
                        val msgId = (System.currentTimeMillis() and 0x7FFFFFFFL).toInt()
                        val payload = if (sessionKey != null) {
                            encryptPayload(msg.third, msg.second, sessionKey, msgId)
                        } else {
                            encodePlaintextPayload(msg.third, msg.second, msgId)
                        }
                        val channel = perPeerSendQueues.getOrPut(contactUuid) {
                            Channel<SendRequest>(Channel.BUFFERED).also { ch ->
                                launchPerPeerConsumer(contactUuid, ch)
                            }
                        }
                        channel.send(SendRequest(
                            payload = payload,
                            activeChatUuid = contactUuid,
                            text = msg.second,
                            timestamp = msg.third,
                            messageId = msgId,
                            isPending = true,
                            dbMessageId = msg.first
                        ))
                    }
                }
            } catch (e: Exception) {
                Log.e("DataRepository", "Error re-queuing pending messages", e)
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
        val previousUuid = activeChatUuid
        activeChatUuid = uuid
        if (uuid.isEmpty()) {
            _activeConnectionStatus.value = ConnectionStatus.DISCONNECTED
            _activeIsReady.value = false
        } else {
            // Disconnect from previous peer if switching to a different peer
            // previousUuid may be empty due to ChatScreen onDispose pattern, check lastChatPeerUuid as well
            val peerToDisconnect = if (previousUuid.isNotEmpty() && !com.example.bluemesh.utils.uuidsMatch(uuid, previousUuid)) previousUuid
                else if (lastChatPeerUuid.isNotEmpty() && !com.example.bluemesh.utils.uuidsMatch(uuid, lastChatPeerUuid)) lastChatPeerUuid
                else null
            if (peerToDisconnect != null) {
                Log.d("DataRepository", "Switching chat from $peerToDisconnect to $uuid, disconnecting old peer")
                val disconnectUuid = peerToDisconnect
                scope.launch(Dispatchers.IO) {
                    bluetoothHandler.disconnectFromPeer(disconnectUuid)
                }
            }
            lastChatPeerUuid = uuid
            val status = bluetoothHandler.getConnectionStatusForPeer(uuid)
            _activeConnectionStatus.value = status
            _activeIsReady.value = (status == ConnectionStatus.CONNECTED)
            // Immediately trigger auto-connect if peer is discovered but not connected
            if (status == ConnectionStatus.DISCONNECTED) {
                scope.launch(Dispatchers.IO) {
                    try {
                        // Small delay to let the UI settle
                        delay(200)
                        connectToPeerByUuid(uuid)
                    } catch (e: Exception) {
                        Log.e("DataRepository", "Error in setActiveChat auto-connect", e)
                    }
                }
            }
        }
        reloadChatMessagesFromDb(uuid, filterPending = !isPasscodeEnabled())
    }

    override fun connectToPeerByUuid(uuid: String) {
        val latestAddr = bluetoothHandler.getCachedAddressForUuid(uuid)
        val peer = bluetoothHandler.discoveredPeers.value.find { com.example.bluemesh.utils.uuidsMatch(it.uuid, uuid) }
        if (peer != null && latestAddr != null && peer.address != latestAddr) {
            Log.d("DataRepository", "Peer $uuid address changed ${peer.address} -> $latestAddr, using latest")
            bluetoothHandler.connectToPeerByAddress(latestAddr)
            bluetoothHandler.startScanning()
            return
        }
        val device = peer?.device ?: bluetoothHandler.getDeviceByUuid(uuid)
        if (device != null && !(peer?.isRelayed == true && peer.device == null)) {
            bluetoothHandler.connectToPeer(device)
        } else if (latestAddr != null) {
            bluetoothHandler.connectToPeerByAddress(latestAddr)
            bluetoothHandler.startScanning()
        } else {
            bluetoothHandler.startScanning()
        }
    }

    override fun refreshConnection(uuid: String) {
        scope.launch(Dispatchers.IO) {
            try {
                Log.d("DataRepository", "Refreshing connection for $uuid")
                // 1. Tear down stale client connection to this peer
                val peer = bluetoothHandler.discoveredPeers.value.find { com.example.bluemesh.utils.uuidsMatch(it.uuid, uuid) }
                if (peer != null) {
                    bluetoothHandler.disconnectClient(peer.address)
                }
                // 2. Ensure scanning is active
                bluetoothHandler.startScanning()
                // 3. Wait briefly for discovery if needed
                delay(500)
                // 4. Reconnect
                connectToPeerByUuid(uuid)
            } catch (e: Exception) {
                Log.e("DataRepository", "Error refreshing connection", e)
            }
        }
    }

    private suspend fun sendPendingMessages(peerUuid: String) = withContext(Dispatchers.IO) {
        val canonicalUuid = dbHelper.getContactsList().find {
            com.example.bluemesh.utils.uuidsMatch(it.first, peerUuid)
        }?.first ?: peerUuid

        val pending = dbHelper.getPendingMessages(canonicalUuid)
        if (pending.isEmpty()) return@withContext

        for (msg in pending) {
            val sessionKey = getSessionKeyForUuid(canonicalUuid)
            if (isPasscodeEnabled() && sessionKey == null) {
                // Wait for E2EE key exchange to complete before sending, trigger it proactively
                triggerKeyExchangeForPeer(canonicalUuid)
                continue
            }
            val msgId = (System.currentTimeMillis() and 0x7FFFFFFFL).toInt()
            val payload = if (sessionKey != null) {
                encryptPayload(msg.third, msg.second, sessionKey, msgId)
            } else {
                encodePlaintextPayload(msg.third, msg.second, msgId)
            }
            // Enqueue through per-peer send queue
            val channel = perPeerSendQueues.getOrPut(canonicalUuid) {
                Channel<SendRequest>(Channel.BUFFERED).also { ch ->
                    launchPerPeerConsumer(canonicalUuid, ch)
                }
            }
            channel.send(SendRequest(
                payload = payload,
                activeChatUuid = canonicalUuid,
                text = msg.second,
                timestamp = msg.third,
                messageId = msgId,
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

    override fun isBoldTextEnabled(): Boolean = prefs.getBoolean("bold_text_enabled", false)
    override fun setBoldTextEnabled(enabled: Boolean) { prefs.edit().putBoolean("bold_text_enabled", enabled).apply() }
    override fun getFontSizeLevel(): Int = prefs.getInt("font_size_level", 2)
    override fun setFontSizeLevel(level: Int) { prefs.edit().putInt("font_size_level", level.coerceIn(1, 7)).apply() }

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
            val newUuid = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("user_uuid", newUuid).apply()
            bluetoothHandler.setMyUuid(newUuid)
            bluetoothHandler.stopAdvertising()
            bluetoothHandler.disconnect()
            bluetoothHandler.stopGattServer()
            bluetoothHandler.clearDiscoveredPeers()
            bluetoothHandler.clearAllTracker()
            sessionKeys.clear()
            myKeyPair = null
            pendingAcks.values.forEach { it.complete(false) }
            pendingAcks.clear()
            seenKeyExchangeHashes.clear()
            pendingMeshMessageIds.clear()
            perPeerSendQueues.values.forEach { while (it.tryReceive().isSuccess) { } }
            perPeerSendQueues.clear()
            sendQueueConsumers.values.forEach { it.cancel() }
            sendQueueConsumers.clear()
            activeChatUuid = ""
            lastChatPeerUuid = ""
            bluetoothHandler.startAdvertising(getDisplayName())
            bluetoothHandler.startScanning()
            return true
        }
        return false
    }



    private fun launchPerPeerConsumer(uuid: String, channel: Channel<SendRequest>) {
        sendQueueConsumers[uuid] = scope.launch(Dispatchers.IO) {
            try {
                for (request in channel) {
                    try {
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

                        if (!isStillPending) continue

                        val retrySessionKey = getSessionKeyForUuid(request.activeChatUuid)
                        val isPlaintext = retrySessionKey == null
                        val maxGattRetries = if (isPlaintext) 10 else 20

                        var sent = false
                        var acked = false
                        var currentRetry = 0
                        while (!sent && currentRetry < maxGattRetries) {
                            if (bluetoothHandler.isPeerReady(request.activeChatUuid)) {
                                var innerRetryCount = 0
                                val ackDeferred = CompletableDeferred<Boolean>()
                                pendingAcks[request.timestamp] = ackDeferred
                                while (!sent && innerRetryCount < 3) {
                                    if (innerRetryCount > 0) delay(300)
                                    val targetPeer = bluetoothHandler.discoveredPeers.value.find { com.example.bluemesh.utils.uuidsMatch(it.uuid, request.activeChatUuid) }
                                    val targetDevice = bluetoothHandler.getConnectedDeviceByPeerUuid(request.activeChatUuid) ?: targetPeer?.device
                                    sent = bluetoothHandler.sendMessageSuspend(request.payload, targetDevice)
                                    innerRetryCount++
                                    if (sent) {
                                        acked = withTimeoutOrNull(2000) { ackDeferred.await() } == true
                                    }
                                    if (!sent) {
                                        delay(300)
                                    }
                                }
                                pendingAcks.remove(request.timestamp)
                                if (sent && acked) {
                                    markMessageSent(request.timestamp)
                                    pendingMeshMessageIds.remove(request.messageId)
                                    bluetoothHandler.cancelMeshAdvertise()
                                    break
                                }
                                Log.d("DataRepository", "GATT write failed or E2EE ACK timeout (acked=$acked) for ${request.activeChatUuid}, retrying.")
                                pendingMeshMessageIds[request.messageId] = request.timestamp
                                if (isPlaintext && currentRetry % 2 == 0) {
                                    bluetoothHandler.advertiseMeshMessage(
                                        request.messageId,
                                        getUserUuid(), request.activeChatUuid, request.text
                                    )
                                }
                            } else {
                                Log.d("DataRepository", "Peer ${request.activeChatUuid} not ready, attempting reconnect and retrying (retry=$currentRetry).")
                                pendingMeshMessageIds[request.messageId] = request.timestamp
                                if (isPlaintext && currentRetry % 2 == 0) {
                                    bluetoothHandler.advertiseMeshMessage(
                                        request.messageId,
                                        getUserUuid(), request.activeChatUuid, request.text
                                    )
                                }
                            }

                            val peerUuid = request.activeChatUuid
                            val now = System.currentTimeMillis()
                            val lastReconnect = peerReconnectTimestamps[peerUuid] ?: 0L
                            if (!sent && now - request.timestamp > 5000) {
                                Log.d("DataRepository", "Message stuck >5s, force healing connection to $peerUuid")
                                bluetoothHandler.disconnectFromPeer(peerUuid)
                                peerReconnectTimestamps[peerUuid] = now
                                connectToPeerByUuid(peerUuid)
                            } else if (now - lastReconnect > 2000) {
                                peerReconnectTimestamps[peerUuid] = now
                                val stalePeerAddr = bluetoothHandler.discoveredPeers.value.find { com.example.bluemesh.utils.uuidsMatch(it.uuid, peerUuid) }?.address
                                if (stalePeerAddr != null) bluetoothHandler.disconnectClient(stalePeerAddr)
                                connectToPeerByUuid(peerUuid)
                            }

                            currentRetry++
                            if (!sent && currentRetry < maxGattRetries) {
                                val backoffDelay = java.lang.Math.min(500L shl currentRetry.coerceAtMost(4), 8000L)
                                delay(backoffDelay)
                            }
                        }

                        if (!sent) {
                            Log.d("DataRepository", "Message to ${request.activeChatUuid} exhausted retries ($maxGattRetries), marking as FAILED.")
                            markMessageAsFailed(request.timestamp)
                            pendingMeshMessageIds.remove(request.messageId)
                        }
                    } catch (e: Exception) {
                        Log.d("DataRepository", "Error processing per-peer sendQueue request for $uuid", e)
                    }
                }
            } catch (e: Exception) {
                Log.d("DataRepository", "sendQueue coroutine failed for $uuid", e)
            } finally {
                perPeerSendQueues.remove(uuid)
                sendQueueConsumers.remove(uuid)
            }
        }
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

    private fun triggerKeyExchangeForPeer(peerUuid: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val device = bluetoothHandler.discoveredPeers.value.find { com.example.bluemesh.utils.uuidsMatch(it.uuid, peerUuid) }?.device
                    ?: bluetoothHandler.getDeviceByUuid(peerUuid)
                if (device != null) {
                    if (myKeyPair == null) myKeyPair = CryptoUtils.generateECKeyPair()
                    myKeyPair?.let {
                        Log.d("DataRepository", "Triggering proactive key exchange for $peerUuid")
                        bluetoothHandler.sendPublicKey(getUserUuid(), it.public.encoded, device)
                    }
                }
            } catch (e: Exception) {
                Log.e("DataRepository", "Failed proactive key exchange", e)
            }
        }
    }
}

private class SeenMessageStore(private val capacity: Int = 400) {
    private val set = LinkedHashSet<String>(capacity)

    @Synchronized
    fun isDuplicateAndTouch(id: String): Boolean {
        if (set.contains(id)) {
            set.remove(id)
            set.add(id)
            return true
        }
        if (set.size >= capacity) {
            val oldest = set.iterator().next()
            set.remove(oldest)
        }
        set.add(id)
        return false
    }

    @Synchronized
    fun add(id: String) {
        if (set.contains(id)) {
            set.remove(id)
        } else if (set.size >= capacity) {
            val oldest = set.iterator().next()
            set.remove(oldest)
        }
        set.add(id)
    }
}
