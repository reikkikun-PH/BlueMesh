package com.example.bluemesh.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.util.Log
import com.example.bluemesh.data.models.BluetoothPeer
import com.example.bluemesh.data.models.ConnectionStatus
import com.example.bluemesh.utils.uuidsMatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ClientConnection(
    val gatt: BluetoothGatt,
    var characteristic: BluetoothGattCharacteristic? = null,
    val deviceAddress: String = "",
    @Volatile var cccdReady: Boolean = false,
    val connectedAt: Long = System.currentTimeMillis()
) {
    val sessionScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val pendingChunks: MutableList<ByteArray> = mutableListOf()
}

class ConnectionTracker(private val context: android.content.Context, private val scope: CoroutineScope) {

    val clientConnections = ConcurrentHashMap<String, ClientConnection>()
    val connectedClients = java.util.concurrent.ConcurrentHashMap.newKeySet<BluetoothDevice>()
    @Volatile var connectedServerDevice: BluetoothDevice? = null
    val cccdStates = ConcurrentHashMap<String, ByteArray>()
    val deviceMtus = ConcurrentHashMap<String, Int>()
    val uuidToServerAddress = ConcurrentHashMap<String, String>()
    val deviceConnectionStatuses = ConcurrentHashMap<String, ConnectionStatus>()
    val syncingStartTimes = ConcurrentHashMap<String, Long>()
    val incomingChunks = ConcurrentHashMap<String, ConcurrentHashMap<Int, ByteArray>>()
    val lastChunkTimestamp = ConcurrentHashMap<String, Long>()
    val actualSendTimes = ConcurrentHashMap<Long, Long>()
    val addressToUuid = ConcurrentHashMap<String, String>()
    val otaToCreationTime = ConcurrentHashMap<Long, Long>()
    val messageIdCache = java.util.Collections.synchronizedList(mutableListOf<Int>())
    val currentWriteDeferreds = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()
    val currentNotificationDeferreds = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()
    val peerDisplayNames = ConcurrentHashMap<String, String>()

    @Volatile var myUuid: String = ""

    fun getAddressByUuid(uuid: String): String? {
        return _discoveredPeers.value.find { uuidsMatch(it.uuid, uuid) }?.address
    }

    private val _discoveredPeers = MutableStateFlow<List<BluetoothPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<BluetoothPeer>> = _discoveredPeers.asStateFlow()

    private val _messages = MutableSharedFlow<com.example.bluemesh.data.models.ChatMessage>(extraBufferCapacity = 128)
    val messages: SharedFlow<com.example.bluemesh.data.models.ChatMessage> = _messages.asSharedFlow()

    private val _acks = MutableSharedFlow<Pair<Long, Long?>>(extraBufferCapacity = 64)
    val acks: SharedFlow<Pair<Long, Long?>> = _acks.asSharedFlow()

    var onPeerConnectionStatusChanged: ((String, ConnectionStatus) -> Unit)? = null
    var onPeerDisconnectedCallback: ((String) -> Unit)? = null

    private var evictionJob: Job? = null

    init {
        startEvictionTimer()
    }

    fun startEvictionTimer() {
        evictionJob?.cancel()
        evictionJob = scope.launch {
            while (true) {
                delay(Constants.EVICTION_INTERVAL_MS)
                val now = System.currentTimeMillis()
                _discoveredPeers.update { current ->
                    current.filter { peer ->
                        val isConnected = clientConnections.containsKey(peer.address) ||
                                connectedClients.any { it.address == peer.address }
                        isConnected || (now - peer.lastSeen < Constants.PEER_EXPIRY_MS)
                    }.sortedWith(compareBy({ it.name.lowercase() }, { it.address }))
                }
                val keysToRemove = mutableListOf<String>()
                lastChunkTimestamp.forEach { (k, timestamp) ->
                    if (now - timestamp > Constants.CHUNK_REASSEMBLY_TIMEOUT_MS) {
                        keysToRemove.add(k)
                    }
                }
                for (k in keysToRemove) {
                    incomingChunks.remove(k)
                    lastChunkTimestamp.remove(k)
                }
            }
        }
    }

    fun stopEvictionTimer() {
        evictionJob?.cancel()
        evictionJob = null
    }

    fun getPeerList(): List<BluetoothPeer> = _discoveredPeers.value

    fun updateDiscoveredPeers(transform: (List<BluetoothPeer>) -> List<BluetoothPeer>) {
        _discoveredPeers.update(transform)
    }

    suspend fun emitMessage(msg: com.example.bluemesh.data.models.ChatMessage) {
        _messages.emit(msg)
    }

    fun tryEmitMessage(msg: com.example.bluemesh.data.models.ChatMessage): Boolean {
        scope.launch {
            _messages.emit(msg)
        }
        return true
    }

    fun emitAck(ack: Pair<Long, Long?>) {
        scope.launch {
            _acks.emit(ack)
        }
    }

    fun getConnectionStatusForAddress(address: String): ConnectionStatus {
        return deviceConnectionStatuses[address] ?: ConnectionStatus.DISCONNECTED
    }

    fun isAlreadyServerConnected(device: BluetoothDevice): Boolean {
        val isDirectConnected = connectedClients.any { it.address == device.address }
        if (isDirectConnected) return true
        val deviceUuid = _discoveredPeers.value.find { it.address == device.address || it.device?.address == device.address }?.uuid
            ?: getUuidByAddress(device.address)
        if (deviceUuid != null && deviceUuid.isNotEmpty()) {
            val isUuidConnected = connectedClients.any { client ->
                val mappedAddr = uuidToServerAddress.entries.firstOrNull { uuidsMatch(it.key, deviceUuid) }?.value
                if (mappedAddr != null && client.address == mappedAddr) return@any true
                val matchesDiscovery = _discoveredPeers.value.any { peer ->
                    uuidsMatch(peer.uuid, deviceUuid) && peer.address == client.address
                }
                if (matchesDiscovery) return@any true
                false
            }
            if (isUuidConnected) return true
        }
        return false
    }

    fun getConnectionStatusForPeer(peerUuid: String): ConnectionStatus {
        val serverAddress = uuidToServerAddress.entries.firstOrNull { uuidsMatch(it.key, peerUuid) }?.value
        if (serverAddress != null) {
            val status = getConnectionStatusForAddress(serverAddress)
            if (status != ConnectionStatus.DISCONNECTED) return status
            val isServerConnected = connectedClients.any { it.address == serverAddress }
            if (isServerConnected) return ConnectionStatus.CONNECTED
        }
        val peer = _discoveredPeers.value.find { uuidsMatch(it.uuid, peerUuid) }
        if (peer != null) {
            val status = getConnectionStatusForAddress(peer.address)
            if (status != ConnectionStatus.DISCONNECTED) return status
            val isServerConnected = connectedClients.any { it.address == peer.address }
            if (isServerConnected) return ConnectionStatus.CONNECTED
        }
        return ConnectionStatus.DISCONNECTED
    }

    fun isPeerConnected(peerUuid: String): Boolean {
        val peer = _discoveredPeers.value.find { uuidsMatch(it.uuid, peerUuid) }
        val address = peer?.address
        if (address != null) {
            if (clientConnections.containsKey(address)) return true
            if (connectedClients.any { it.address == address }) return true
        }
        val serverAddress = uuidToServerAddress.entries.firstOrNull { uuidsMatch(it.key, peerUuid) }?.value
        if (serverAddress != null) {
            if (clientConnections.containsKey(serverAddress)) return true
            if (connectedClients.any { it.address == serverAddress }) return true
        }
        return false
    }

    fun isPeerReady(peerUuid: String): Boolean {
        val anyClientReady = clientConnections.values.any { conn ->
            val clientMatch = _discoveredPeers.value.any { peer ->
                uuidsMatch(peer.uuid, peerUuid) && (peer.address == conn.deviceAddress || peer.device?.address == conn.deviceAddress)
            }
            if (clientMatch && conn.cccdReady) return@any true
            val serverAddress = uuidToServerAddress.entries.firstOrNull { uuidsMatch(it.key, peerUuid) }?.value
            if (serverAddress != null && conn.deviceAddress == serverAddress && conn.cccdReady) return@any true
            false
        }
        if (anyClientReady) return true
        val serverAddress = connectedClients.firstOrNull { client ->
            val matchesDiscovery = _discoveredPeers.value.any { peer ->
                uuidsMatch(peer.uuid, peerUuid) && (peer.address == client.address || peer.device?.address == client.address)
            }
            if (matchesDiscovery) return@firstOrNull true
            val mappedAddr = uuidToServerAddress.entries.firstOrNull { uuidsMatch(it.key, peerUuid) }?.value
            if (mappedAddr != null && client.address == mappedAddr) return@firstOrNull true
            false
        }?.address
        if (serverAddress != null) {
            val cccd = cccdStates[serverAddress]
            if (cccd != null && cccd.isNotEmpty() && (cccd[0].toInt() and 0x01 != 0)) return true
        }
        val uuidAddress = uuidToServerAddress.entries.firstOrNull { uuidsMatch(it.key, peerUuid) }?.value
        if (uuidAddress != null) {
            val clientReady = clientConnections.values.any { it.deviceAddress == uuidAddress && it.cccdReady }
            if (clientReady) return true
            val hasClient = connectedClients.any { it.address == uuidAddress }
            if (hasClient) {
                val cccd = cccdStates[uuidAddress]
                return cccd != null && cccd.isNotEmpty() && (cccd[0].toInt() and 0x01 != 0)
            }
        }
        return false
    }

    fun updateDeviceStatus(address: String, status: ConnectionStatus) {
        deviceConnectionStatuses[address] = status
        if (status == ConnectionStatus.SYNCHRONIZING) {
            syncingStartTimes.putIfAbsent(address, System.currentTimeMillis())
        } else {
            syncingStartTimes.remove(address)
        }
        val peerUuid = _discoveredPeers.value.find { it.address == address }?.uuid
        if (peerUuid != null) {
            onPeerConnectionStatusChanged?.invoke(peerUuid, status)
        }
    }

    fun isStuckInSynchronizing(address: String, timeoutMs: Long = 15000): Boolean {
        val status = deviceConnectionStatuses[address]
        if (status != ConnectionStatus.SYNCHRONIZING) return false
        val startTime = syncingStartTimes[address] ?: return false
        return (System.currentTimeMillis() - startTime) > timeoutMs
    }

    fun isDuplicateMessage(messageId: Int): Boolean {
        synchronized(messageIdCache) {
            if (messageIdCache.contains(messageId)) return true
            if (messageIdCache.size >= Constants.CACHE_MAX_SIZE) messageIdCache.removeAt(0)
            messageIdCache.add(messageId)
            return false
        }
    }

    fun getConnectedDeviceByPeerUuid(peerUuid: String): BluetoothDevice? {
        connectedClients.firstOrNull { client ->
            _discoveredPeers.value.any { peer -> uuidsMatch(peer.uuid, peerUuid) && peer.address == client.address }
        }?.let { return it }
        val clientMatch = clientConnections.values.firstOrNull { conn ->
            _discoveredPeers.value.any { peer -> uuidsMatch(peer.uuid, peerUuid) && peer.address == conn.deviceAddress }
        }
        if (clientMatch != null) return clientMatch.gatt.device
        val addr = uuidToServerAddress.entries.firstOrNull { uuidsMatch(it.key, peerUuid) }?.value
        if (addr != null) {
            connectedClients.firstOrNull { it.address == addr }?.let { return it }
            clientConnections[addr]?.let { return it.gatt.device }
        }
        return _discoveredPeers.value.find { uuidsMatch(it.uuid, peerUuid) }?.device
    }

    fun getDeviceByUuid(uuid: String): BluetoothDevice? {
        val peer = _discoveredPeers.value.find { uuidsMatch(it.uuid, uuid) }
        if (peer != null) {
            return peer.device ?: getConnectedDeviceByAddress(peer.address)
        }
        val serverAddress = uuidToServerAddress.entries.firstOrNull { uuidsMatch(it.key, uuid) }?.value
        if (serverAddress != null) {
            return getConnectedDeviceByAddress(serverAddress)
        }
        return null
    }

    fun getCachedAddressForUuid(uuid: String): String? {
        val peer = _discoveredPeers.value.find { uuidsMatch(it.uuid, uuid) }
        if (peer != null && peer.address.isNotEmpty()) {
            if (peer.device == null && peer.isRelayed) {
                val serverAddr = uuidToServerAddress.entries.firstOrNull { uuidsMatch(it.key, uuid) }?.value
                if (serverAddr != null && serverAddr != peer.address) return serverAddr
            }
            return peer.address
        }
        return uuidToServerAddress.entries.firstOrNull { uuidsMatch(it.key, uuid) }?.value
    }

    fun refreshPeerFromServerAddress(uuid: String) {
        val serverAddr = uuidToServerAddress.entries.firstOrNull { uuidsMatch(it.key, uuid) }?.value
        if (serverAddr != null) {
            _discoveredPeers.update { current ->
                current.map { peer ->
                    if (uuidsMatch(peer.uuid, uuid) && peer.isRelayed && peer.device == null && peer.address != serverAddr) {
                        Log.d(Constants.TAG, "refreshPeerFromServerAddress: updating $uuid address ${peer.address} -> $serverAddr")
                        val connectedDevice = getConnectedDeviceByAddress(serverAddr)
                        peer.copy(address = serverAddr, device = connectedDevice ?: peer.device, isRelayed = connectedDevice != null)
                    } else peer
                }
            }
        }
    }

    fun getConnectedDeviceByAddress(address: String): BluetoothDevice? {
        connectedClients.find { it.address == address }?.let { return it }
        clientConnections[address]?.let { return it.gatt.device }
        return null
    }

    fun getUuidByAddress(deviceAddress: String): String? {
        return addressToUuid[deviceAddress]
            ?: uuidToServerAddress.entries.firstOrNull { it.value == deviceAddress }?.key
    }

    fun getUuidMapDump(): String {
        val fwd = uuidToServerAddress.entries.joinToString(";") { "${it.key}→${it.value}" }
        val rev = addressToUuid.entries.joinToString(";") { "${it.key}→${it.value}" }
        return "fwd=[$fwd] rev=[$rev]"
    }

    fun getConnectedDeviceAddress(): String? {
        connectedClients.firstOrNull()?.let { return it.address }
        connectedServerDevice?.let { return it.address }
        return null
    }

    var onResolvePeerName: ((String) -> String)? = null

    fun setPeerDisplayName(uuid: String, name: String) {
        if (name.isNotEmpty()) peerDisplayNames[uuid] = name
    }

    fun updatePeerUuid(fullUuid: String, deviceAddress: String) {
        uuidToServerAddress[fullUuid] = deviceAddress
        addressToUuid[deviceAddress] = fullUuid
        Log.d(Constants.TAG, "updatePeerUuid added $fullUuid → $deviceAddress, map size=${uuidToServerAddress.size}")
        uuidToServerAddress.keys.toList().forEach { key ->
            if (key != fullUuid && uuidsMatch(key, fullUuid)) {
                val oldAddr = uuidToServerAddress.remove(key)
                Log.d(Constants.TAG, "updatePeerUuid removed duplicate key $key")
            }
        }
        _discoveredPeers.update { current ->
            val existingByUuid = current.find { uuidsMatch(it.uuid, fullUuid) }
            if (existingByUuid != null) {
                current.map { peer ->
                    if (uuidsMatch(peer.uuid, fullUuid))
                        peer.copy(uuid = fullUuid, address = deviceAddress, lastSeen = System.currentTimeMillis(), device = getConnectedDeviceByAddress(deviceAddress))
                    else peer
                }
            } else {
                val existingByAddr = current.find { it.address == deviceAddress }
                if (existingByAddr != null) {
                    current.map { peer ->
                        if (peer.address == deviceAddress)
                            peer.copy(uuid = fullUuid, lastSeen = System.currentTimeMillis())
                        else peer
                    }
                } else {
                    val resolvedName = onResolvePeerName?.invoke(fullUuid) ?: peerDisplayNames[fullUuid] ?: fullUuid.take(8)
                    val device = getConnectedDeviceByAddress(deviceAddress)
                    current + BluetoothPeer(
                        address = deviceAddress, name = resolvedName, device = device,
                        lastSeen = System.currentTimeMillis(), uuid = fullUuid
                    )
                }
            }
        }
        val status = deviceConnectionStatuses[deviceAddress] ?: ConnectionStatus.DISCONNECTED
        onPeerConnectionStatusChanged?.invoke(fullUuid, status)
    }

    fun isClient(): Boolean = clientConnections.isNotEmpty()

    fun clearDiscoveredPeers() {
        _discoveredPeers.value = emptyList()
    }

    fun clearAll() {
        clientConnections.clear()
        connectedClients.clear()
        connectedServerDevice = null
        cccdStates.clear()
        deviceMtus.clear()
        uuidToServerAddress.clear()
        deviceConnectionStatuses.clear()
        syncingStartTimes.clear()
        incomingChunks.clear()
        lastChunkTimestamp.clear()
        actualSendTimes.clear()
        otaToCreationTime.clear()
        addressToUuid.clear()
        messageIdCache.clear()
        currentWriteDeferreds.values.forEach { it.complete(false) }
        currentWriteDeferreds.clear()
        currentNotificationDeferreds.values.forEach { it.complete(false) }
        currentNotificationDeferreds.clear()
        peerDisplayNames.clear()
    }
}
