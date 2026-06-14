package com.example.bluemesh.data

import android.bluetooth.BluetoothDevice
import android.content.Context
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
    private val sessionKeys = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private var myKeyPair: java.security.KeyPair? = null
    private val lastConnectionAttempts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private data class ProcessedMessageEntry(
        val senderUuid: String,
        val text: String,
        val timestamp: Long
    )
    private val processedMessagesCache = java.util.Collections.synchronizedList(mutableListOf<ProcessedMessageEntry>())
    private val PROCESSED_CACHE_MAX_SIZE = 50

    private fun isDuplicateMessageContent(senderUuid: String, text: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(processedMessagesCache) {
            processedMessagesCache.removeAll { now - it.timestamp > 8000 }
            if (processedMessagesCache.any { it.senderUuid == senderUuid && it.text == text }) {
                return true
            }
            processedMessagesCache.add(ProcessedMessageEntry(senderUuid, text, now))
            if (processedMessagesCache.size > PROCESSED_CACHE_MAX_SIZE) {
                processedMessagesCache.removeAt(0)
            }
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

        // Load session keys from database
        scope.launch {
            try {
                for (contact in dbHelper.getContactsList()) {
                    val keyHex = dbHelper.getSessionKey(contact.first)
                    if (keyHex != null && keyHex.isNotEmpty()) {
                        sessionKeys[contact.first] = keyHex.hexToBytes()
                        Log.i("DataRepository", "Loaded session key for contact ${contact.first}")
                    }
                }
            } catch (e: Exception) {
                Log.e("DataRepository", "Error loading session keys from DB", e)
            }
        }

        bluetoothHandler.onPeerReadyCallback = { peerUuid ->
            scope.launch {
                if (myKeyPair == null) {
                    myKeyPair = CryptoUtils.generateECKeyPair()
                }
                val myKeyPairObj = myKeyPair
                if (myKeyPairObj != null) {
                    val pubKeyBytes = myKeyPairObj.public.encoded
                    val myUuid = getUserUuid()
                    Log.i("DataRepository", "Initiating key exchange with $peerUuid. Sending public key (${pubKeyBytes.size} bytes)")
                    bluetoothHandler.sendPublicKey(myUuid, pubKeyBytes)
                }
                sendPendingMessages(peerUuid)
            }
        }

        bluetoothHandler.onKeyExchangeReceived = { senderUuid, peerPublicKeyBytes ->
            bluetoothHandler.updatePeerUuid(senderUuid)
            val activeNorm = activeChatUuid.replace("-", "").lowercase()
            val senderNorm = senderUuid.replace("-", "").lowercase()
            if (activeNorm.length == 16 && senderNorm.startsWith(activeNorm)) {
                activeChatUuid = senderUuid
                if (isPasscodeEnabled()) {
                    _chatMessages.value = dbHelper.getMessagesForContact(senderUuid)
                }
            }
            scope.launch {
                Log.i("DataRepository", "Received public key from $senderUuid (${peerPublicKeyBytes.size} bytes)")
                if (myKeyPair == null) {
                    myKeyPair = CryptoUtils.generateECKeyPair()
                }
                val myKeyPairObj = myKeyPair
                if (myKeyPairObj != null) {
                    try {
                          val sharedSecret = CryptoUtils.generateSharedSecret(myKeyPairObj.private, peerPublicKeyBytes)
                          val aesKey = CryptoUtils.deriveAESKey(sharedSecret)
                          sessionKeys[senderUuid] = aesKey
                          if (isPasscodeEnabled()) {
                              dbHelper.saveSessionKey(senderUuid, aesKey.toHex())
                          }
                          Log.i("DataRepository", "Successfully negotiated E2EE session key for peer $senderUuid")
                          sendPendingMessages(senderUuid)
                      } catch (e: Exception) {
                          Log.e("DataRepository", "Failed to compute shared secret for $senderUuid", e)
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
                    if (message.recipientHash != myUuidHash && message.recipientHash != 0) {
                        Log.d("DataRepository", "Mesh: Dropped message because recipient hash ${message.recipientHash} did not match my hash $myUuidHash")
                        return@collect
                    }
                    val resolved = if (activeChatUuid.isNotEmpty() && (activeChatUuid.hashCode() == message.senderHash || (activeChatUuid.startsWith("mesh_") && activeChatUuid.substringAfter("mesh_").toIntOrNull() == message.senderHash))) {
                        activeChatUuid
                    } else {
                        getContacts().find { it.uuid.hashCode() == message.senderHash }?.uuid
                            ?: bluetoothHandler.discoveredPeers.value.find { it.uuid.hashCode() == message.senderHash }?.uuid
                    }
                    
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

                val textBytes = message.text.toByteArray(Charsets.ISO_8859_1)
                var msgTimestamp = message.timestamp // fallback to receipt time
                var finalMessageText = ""

                var isEncrypted = false
                if (textBytes.size >= 4) {
                    val buffer = ByteBuffer.wrap(textBytes, 0, 4)
                    buffer.order(ByteOrder.BIG_ENDIAN)
                    val messageId = buffer.int
                    isEncrypted = (messageId and java.lang.Integer.MIN_VALUE) != 0
                }

                if (isEncrypted) {
                    val sessionKey = sessionKeys[senderUuid]
                    if (sessionKey != null) {
                        try {
                            val messageId = ByteBuffer.wrap(textBytes, 0, 4).apply { order(ByteOrder.BIG_ENDIAN) }.int
                            val ciphertextBytes = textBytes.copyOfRange(4, textBytes.size)
                            val decryptedBytes = CryptoUtils.encryptDecryptXOR(ciphertextBytes, sessionKey, messageId)
                            
                            if (decryptedBytes.size >= 8) {
                                val decBuffer = ByteBuffer.wrap(decryptedBytes)
                                msgTimestamp = decBuffer.long
                                finalMessageText = String(decryptedBytes, 8, decryptedBytes.size - 8, Charsets.UTF_8)
                                Log.i("DataRepository", "E2EE: Decrypted message with timestamp $msgTimestamp: $finalMessageText")
                            } else {
                                finalMessageText = String(decryptedBytes, Charsets.UTF_8)
                                Log.w("DataRepository", "E2EE: Decrypted message too short for timestamp prefix")
                            }
                        } catch (e: Exception) {
                            Log.e("DataRepository", "E2EE: Failed decryption attempt", e)
                            finalMessageText = "[Encrypted Message — Decryption Error]"
                        }
                    } else {
                        Log.w("DataRepository", "E2EE: Received encrypted message from $senderUuid but no session key exists")
                        finalMessageText = "[Encrypted Message — Pair to Decrypt]"
                    }
                } else {
                    // Plaintext message
                    if (textBytes.size >= 8) {
                        val buffer = ByteBuffer.wrap(textBytes)
                        msgTimestamp = buffer.long
                        finalMessageText = String(textBytes, 8, textBytes.size - 8, Charsets.UTF_8)
                    } else {
                        finalMessageText = String(textBytes, Charsets.UTF_8)
                    }
                }

                if (senderUuid.isNotEmpty() && !message.isFromMe) {
                    if (isDuplicateMessageContent(senderUuid, finalMessageText)) {
                        Log.d("DataRepository", "Deduplication: Discarded duplicate message: $finalMessageText")
                        return@collect
                    }
                }

                if (isPasscodeEnabled() && senderUuid.isNotEmpty() && !message.isFromMe) {
                    dbHelper.insertMessage(senderUuid, finalMessageText, msgTimestamp, "RECEIVED", false)
                }

                if (senderUuid == activeChatUuid && !message.isFromMe) {
                    val displayMessage = message.copy(text = finalMessageText, timestamp = msgTimestamp)
                    _chatMessages.update { current -> current + displayMessage }
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

        scope.launch {
            discoveredPeers.collect { peers ->
                if (isPasscodeEnabled()) {
                    for (peer in peers) {
                        if (isContact(peer.uuid)) {
                            val storedContact = getContacts().find { 
                                val storedNorm = it.uuid.replace("-", "").lowercase()
                                val peerNorm = peer.uuid.replace("-", "").lowercase()
                                storedNorm == peerNorm || (peerNorm.length == 16 && storedNorm.startsWith(peerNorm)) || (storedNorm.length == 16 && peerNorm.startsWith(storedNorm))
                            }
                            if (storedContact != null) {
                                val storedNorm = storedContact.uuid.replace("-", "").lowercase()
                                val peerNorm = peer.uuid.replace("-", "").lowercase()
                                if (storedNorm.length == 16 && peerNorm.length > 16) {
                                    dbHelper.deleteContact(storedContact.uuid)
                                    saveContact(peer.uuid, peer.name)
                                    Log.i("DataRepository", "Contact sync: Migrated contact ${storedContact.uuid} to full UUID ${peer.uuid}")
                                } else if (storedContact.name != peer.name || storedContact.isOfficial != peer.isOfficial) {
                                    Log.i("DataRepository", "Contact sync: Discovered name/official update for contact ${storedContact.uuid}: '${storedContact.name}' -> '${peer.name}', official: ${storedContact.isOfficial} -> ${peer.isOfficial}")
                                    saveContact(storedContact.uuid, peer.name)
                                }
                            }
                        }
                    }
                }

                // Auto-connect to contacts with pending messages
                if (isPasscodeEnabled() && connectionStatus.value == ConnectionStatus.DISCONNECTED) {
                    for (peer in peers) {
                        if (isContact(peer.uuid)) {
                            val pending = dbHelper.getPendingMessages(peer.uuid)
                            if (pending.isNotEmpty()) {
                                val lastAttempt = lastConnectionAttempts[peer.uuid] ?: 0L
                                val now = System.currentTimeMillis()
                                if (now - lastAttempt > 15000) {
                                    lastConnectionAttempts[peer.uuid] = now
                                    Log.i("DataRepository", "Auto-connecting to contact ${peer.uuid} (${peer.name}) to send ${pending.size} pending messages")
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
        val sessionKey = sessionKeys[activeChatUuid]

        val messageId = (System.currentTimeMillis() and 0x7FFFFFFFL).toInt() // Ensure MSB is 0 initially
        var sentSuccessfully = false

        if (sessionKey != null) {
            // Encrypt the message!
            val plaintextBytes = BluetoothHandler.encodePayload(timestamp, text)
            val encryptedBytes = CryptoUtils.encryptDecryptXOR(plaintextBytes, sessionKey, messageId or java.lang.Integer.MIN_VALUE)
            val encryptedTextLatin1 = String(encryptedBytes, Charsets.ISO_8859_1)
            
            Log.i("DataRepository", "E2EE: Encrypting and sending message to $activeChatUuid. Message ID = $messageId")

            val senderUuid = getUserUuid()
            val recipientUuid = activeChatUuid
            bluetoothHandler.advertiseMeshMessage(messageId or java.lang.Integer.MIN_VALUE, senderUuid, recipientUuid, encryptedTextLatin1)

            if (peer != null && bluetoothHandler.isReady.value) {
                sentSuccessfully = bluetoothHandler.sendMessageEncrypted(encryptedBytes, messageId)
            }
        } else {
            Log.i("DataRepository", "E2EE: No session key found for $activeChatUuid. Sending plain text.")
            val senderUuid = getUserUuid()
            val recipientUuid = activeChatUuid
            bluetoothHandler.advertiseMeshMessage(messageId, senderUuid, recipientUuid, text)

            if (peer != null && bluetoothHandler.isReady.value) {
                val plaintextBytes = BluetoothHandler.encodePayload(timestamp, text)
                sentSuccessfully = bluetoothHandler.sendMessage(plaintextBytes)
            }
        }

        val status = if (sentSuccessfully) "SENT" else "PENDING"

        // Save to DB only if passcode is enabled
        if (isPasscodeEnabled()) {
            dbHelper.insertMessage(activeChatUuid, text, timestamp, status, true)
        }
        _chatMessages.update { current -> current + ChatMessage(text, true, timestamp, status) }
        return true
    }

    override fun getDisplayName(): String {
        return prefs.getString("display_name", "") ?: ""
    }

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
        return list.map { (uuid, name, isOfficialDb) ->
            val discoveredPeer = currentDiscovered.find {
                val storedNorm = uuid.replace("-", "").lowercase()
                val peerNorm = it.uuid.replace("-", "").lowercase()
                storedNorm == peerNorm || (peerNorm.length == 16 && storedNorm.startsWith(peerNorm)) || (storedNorm.length == 16 && peerNorm.startsWith(storedNorm))
            }
            val address = discoveredPeer?.address ?: ""
            BluetoothPeer(
                address = address,
                name = name,
                device = discoveredPeer?.device,
                lastSeen = discoveredPeer?.lastSeen ?: 0L,
                uuid = uuid,
                hasPasscode = discoveredPeer?.hasPasscode ?: isOfficialDb,
                isOfficial = isOfficialDb || (discoveredPeer?.isOfficial == true)
            )
        }
    }

    override fun saveContact(uuid: String, name: String) {
        val isOfficial = bluetoothHandler.discoveredPeers.value.find {
            val storedNorm = uuid.replace("-", "").lowercase()
            val peerNorm = it.uuid.replace("-", "").lowercase()
            storedNorm == peerNorm || (peerNorm.length == 16 && storedNorm.startsWith(peerNorm)) || (storedNorm.length == 16 && peerNorm.startsWith(storedNorm))
        }?.isOfficial ?: false
        dbHelper.saveContact(uuid, name, isOfficial)
    }

    override fun deleteContact(uuid: String) {
        dbHelper.deleteContact(uuid)
    }

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

        val sessionKey = sessionKeys[peerUuid]
        if (isContact(peerUuid) && sessionKey == null) {
            Log.d("DataRepository", "sendPendingMessages: Contact $peerUuid has pending messages but no E2EE key yet, waiting for exchange.")
            return
        }

        Log.d("DataRepository", "Found ${pending.size} pending messages for $peerUuid. Sending sequentially...")

        for (msg in pending) {
            val success = if (sessionKey != null) {
                val messageId = (System.currentTimeMillis() and 0x7FFFFFFFL).toInt()
                val plaintextBytes = BluetoothHandler.encodePayload(msg.third, msg.second)
                val encryptedBytes = CryptoUtils.encryptDecryptXOR(plaintextBytes, sessionKey, messageId or java.lang.Integer.MIN_VALUE)
                bluetoothHandler.sendMessageEncrypted(encryptedBytes, messageId)
            } else {
                val plaintextBytes = BluetoothHandler.encodePayload(msg.third, msg.second)
                bluetoothHandler.sendMessage(plaintextBytes)
            }
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
        val newLockedUuid = UUID.randomUUID().toString()
        prefs.edit()
            .putString("passcode_hash", hash)
            .putBoolean("is_passcode_enabled", true)
            .putString("user_uuid", newLockedUuid)
            .apply()
            
        val name = getDisplayName()
        if (name.isNotEmpty()) {
            bluetoothHandler.stopAdvertising()
            bluetoothHandler.startAdvertising(name)
        }
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
            .putString("user_uuid", UUID.randomUUID().toString())
            .apply()
            
        val name = getDisplayName()
        if (name.isNotEmpty()) {
            bluetoothHandler.stopAdvertising()
            bluetoothHandler.startAdvertising(name)
        }
    }

    override fun isShareLocationEnabled(): Boolean {
        return prefs.getBoolean("is_share_location_enabled", false)
    }

    override fun setShareLocationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("is_share_location_enabled", enabled).apply()
        val name = getDisplayName()
        if (name.isNotEmpty()) {
            bluetoothHandler.stopAdvertising()
            bluetoothHandler.startAdvertising(name)
        }
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

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray {
        val result = ByteArray(length / 2)
        for (i in 0 until length step 2) {
            result[i / 2] = substring(i, i + 2).toInt(16).toByte()
        }
        return result
    }
}
