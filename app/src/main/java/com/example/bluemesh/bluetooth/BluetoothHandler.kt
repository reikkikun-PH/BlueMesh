package com.example.bluemesh.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.internal.view.SupportMenu
import com.example.bluemesh.data.models.BluetoothPeer
import com.example.bluemesh.data.models.ChatMessage
import com.example.bluemesh.data.models.ConnectionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import net.jpountz.lz4.LZ4Factory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@SuppressLint("MissingPermission")
class BluetoothHandler(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothHandler"
        val SERVICE_UUID: UUID = UUID.fromString("b17c8a70-8bde-4d76-bc3e-1b32d2f7881c")
        val MESSAGE_CHARACTERISTIC_UUID: UUID = UUID.fromString("b17c8a71-8bde-4d76-bc3e-1b32d2f7881c")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MESH_SERVICE_UUID_STR = "12345678-1234-5678-1234-567890abcdef"
        private const val MESH_COMPANY_ID = 0xFFFF
        private const val PEER_MANUFACTURER_ID = 0xFFFF
        private const val CACHE_MAX_SIZE = 100

        fun encodePayload(timestamp: Long, text: String): ByteArray {
            val textBytes = text.toByteArray(Charsets.UTF_8)
            return ByteBuffer.allocate(8 + textBytes.size).apply {
                putLong(timestamp)
                put(textBytes)
            }.array()
        }

        fun decodePayload(payload: ByteArray): Pair<Long, String>? {
            if (payload.size < 8) return null
            val buffer = ByteBuffer.wrap(payload)
            val timestamp = buffer.long
            val textBytes = ByteArray(payload.size - 8)
            buffer.get(textBytes)
            return Pair(timestamp, String(textBytes, Charsets.UTF_8))
        }
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner
    private val advertiser: BluetoothLeAdvertiser? get() = bluetoothAdapter?.bluetoothLeAdvertiser

    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var messageCharacteristic: BluetoothGattCharacteristic? = null

    private val connectedClients = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
    @Volatile private var connectedServerDevice: BluetoothDevice? = null

    private val cccdStates = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    
    private var evictionJob: Job? = null
    private var scanLoopJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private val serverCccdTimeoutJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private var clientSyncTimeoutJob: Job? = null
    private var isCurrentConnectionRetry = false
    private val isDisconnecting = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastScanStartTime: Long = 0
    private var discoveryRetryCount = 0
    private val deviceMtus = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile private var currentWriteDeferred: CompletableDeferred<Boolean>? = null
    
    private val messageIdCache = java.util.Collections.synchronizedList(mutableListOf<Int>())
    private var meshAdvertiseJob: Job? = null
    private var lastDisplayName: String = ""
    @Volatile private var isStartingAdvertising = false

    private var isReceiverRegistered = false
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED == intent.action) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    Log.d(TAG, "Bluetooth turned ON. Restarting scanning/advertising.")
                    scope.launch {
                        try {
                            delay(1000)
                            onBluetoothStateOn?.invoke()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error invoking onBluetoothStateOn", e)
                        }
                    }
                } else if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    Log.d(TAG, "Bluetooth turned OFF/TURNING_OFF. Cleaning up connection states.")
                    try {
                        disconnect()
                        stopGattServer()
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                        _isReady.value = false
                        connectedClients.clear()
                        connectedServerDevice = null
                    } catch (e: Exception) {
                        Log.e(TAG, "Error cleaning up on Bluetooth state off", e)
                    }
                }
            }
        }
    }

    init {
        try {
            context.registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register bluetooth state receiver", e)
        }
    }

    private val gattWriteMutex = Mutex()

    // Cache to assemble incoming chunked BLE packets
    private val incomingChunks = java.util.Collections.synchronizedMap(
        mutableMapOf<String, MutableMap<Int, ByteArray>>()
    )
    private val lastChunkTimestamp = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun handleIncomingValue(device: BluetoothDevice, value: ByteArray) {
        handleIncomingPacket(device.address, value)?.let { (type, assembled) ->
            if (type == 2.toByte()) {
                if (assembled.size >= 17) {
                    val senderUuid = bytesToUuid(assembled.copyOfRange(1, 17)).toString()
                    onKeyExchangeReceived?.invoke(senderUuid, assembled.copyOfRange(17, assembled.size), device)
                }
            } else if (type == 1.toByte()) {
                parseAndDecompress(assembled)?.let {
                    scope.launch {
                        _messages.emit(ChatMessage(String(it, Charsets.ISO_8859_1), isFromMe = false, timestamp = System.currentTimeMillis(), senderAddress = device.address))
                    }
                }
            } else if (type == 3.toByte()) {
                if (assembled.size >= 8) {
                    val ackTimestamp = ByteBuffer.wrap(assembled).long
                    scope.launch {
                        _acks.emit(ackTimestamp)
                    }
                }
            }
        }
    }

    private fun handleIncomingPacket(senderAddress: String, packet: ByteArray): Pair<Byte, ByteArray>? {
        if (packet.size < 3) return null
        val type = packet[0]
        val chunkIndex = packet[1].toInt() and 0xFF
        val totalChunks = packet[2].toInt() and 0xFF
        val data = packet.copyOfRange(3, packet.size)

        if (totalChunks <= 1) {
            return Pair(type, data)
        }

        val key = "${senderAddress}_${type}"
        lastChunkTimestamp[key] = System.currentTimeMillis()
        val chunksMap = incomingChunks.getOrPut(key) { mutableMapOf() }
        chunksMap[chunkIndex] = data

        if (chunksMap.size == totalChunks) {
            val allPresent = (0 until totalChunks).all { chunksMap.containsKey(it) }
            if (allPresent) {
                incomingChunks.remove(key)
                lastChunkTimestamp.remove(key)
                val totalSize = (0 until totalChunks).sumOf { chunksMap[it]!!.size }
                val assembled = ByteArray(totalSize)
                var offset = 0
                for (i in 0 until totalChunks) {
                    val chunkData = chunksMap[i]!!
                    System.arraycopy(chunkData, 0, assembled, offset, chunkData.size)
                    offset += chunkData.size
                }
                return Pair(type, assembled)
            }
        }
        return null
    }

    private val scope = CoroutineScope(Dispatchers.Default)

    private val _discoveredPeers = MutableStateFlow<List<BluetoothPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<BluetoothPeer>> = _discoveredPeers.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _messages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 128)
    val messages: SharedFlow<ChatMessage> = _messages.asSharedFlow()

    private val _acks = MutableSharedFlow<Long>(extraBufferCapacity = 64)
    val acks: SharedFlow<Long> = _acks.asSharedFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val deviceConnectionStatuses = java.util.concurrent.ConcurrentHashMap<String, ConnectionStatus>()
    var onPeerConnectionStatusChanged: ((String, ConnectionStatus) -> Unit)? = null
    var onPeerReadyCallback: ((String, BluetoothDevice) -> Unit)? = null
    var onKeyExchangeReceived: ((String, ByteArray, BluetoothDevice) -> Unit)? = null
    var onPeerDisconnectedCallback: ((String) -> Unit)? = null
    var onBluetoothStateOn: (() -> Unit)? = null

    fun getConnectionStatusForAddress(address: String): ConnectionStatus {
        return deviceConnectionStatuses[address] ?: ConnectionStatus.DISCONNECTED
    }

    fun getConnectionStatusForPeer(peerUuid: String): ConnectionStatus {
        val peer = _discoveredPeers.value.find { com.example.bluemesh.utils.uuidsMatch(it.uuid, peerUuid) }
            ?: return ConnectionStatus.DISCONNECTED
        return getConnectionStatusForAddress(peer.address)
    }

    fun isPeerConnected(peerUuid: String): Boolean {
        val peer = _discoveredPeers.value.find { com.example.bluemesh.utils.uuidsMatch(it.uuid, peerUuid) }
            ?: return false
        val address = peer.address
        if (bluetoothGatt?.device?.address == address) return true
        if (connectedClients.any { it.address == address }) return true
        return false
    }

    fun isPeerReady(peerUuid: String): Boolean {
        val peer = _discoveredPeers.value.find { com.example.bluemesh.utils.uuidsMatch(it.uuid, peerUuid) }
            ?: return false
        val address = peer.address
        val clientGatt = bluetoothGatt
        if (clientGatt != null && clientGatt.device.address == address) {
            return _isReady.value
        }
        val isServerConnected = connectedClients.any { it.address == address }
        if (isServerConnected) {
            val cccd = cccdStates[address]
            return cccd != null && cccd.isNotEmpty() && (cccd[0].toInt() and 0x01 != 0)
        }
        return false
    }

    private fun updateDeviceStatus(address: String, status: ConnectionStatus) {
        deviceConnectionStatuses[address] = status
        val peerUuid = _discoveredPeers.value.find { it.address == address }?.uuid
        if (peerUuid != null) {
            onPeerConnectionStatusChanged?.invoke(peerUuid, status)
        }
    }

    private fun showToast(message: String) {
        try {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show toast", e)
        }
    }

    private fun isDuplicateMessage(messageId: Int): Boolean {
        synchronized(messageIdCache) {
            if (messageIdCache.contains(messageId)) return true
            if (messageIdCache.size >= CACHE_MAX_SIZE) messageIdCache.removeAt(0)
            messageIdCache.add(messageId)
            return false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val scanRecord = result.scanRecord ?: return
            
            val serviceUuids = scanRecord.serviceUuids
            val meshUuid = ParcelUuid(UUID.fromString(MESH_SERVICE_UUID_STR))
            if (serviceUuids != null && serviceUuids.contains(meshUuid)) {
                val manufacturerData = scanRecord.getManufacturerSpecificData(MESH_COMPANY_ID)
                if (manufacturerData != null && manufacturerData.size >= 12) {
                    val buffer = ByteBuffer.wrap(manufacturerData).order(ByteOrder.BIG_ENDIAN)
                    val messageId = buffer.int
                    val senderHash = buffer.int
                    val recipientHash = buffer.int
                    val messageText = String(manufacturerData.copyOfRange(12, manufacturerData.size), Charsets.UTF_8)
                    
                    if (!isDuplicateMessage(messageId)) {
                        _messages.tryEmit(ChatMessage(text = messageText, isFromMe = false, timestamp = System.currentTimeMillis(), senderHash = senderHash, recipientHash = recipientHash))
                    }
                }
                return
            }
            
            val manufacturerData = scanRecord.getManufacturerSpecificData(PEER_MANUFACTURER_ID) ?: return
            if (manufacturerData.size >= 8) {
                val peerUuid = manufacturerData.copyOfRange(0, 8).toHexString()
                val passcodeByte = if (manufacturerData.size >= 9) manufacturerData[8].toInt() else 0
                val isGoodbye = (passcodeByte and 0x08) != 0
                if (isGoodbye) {
                    _discoveredPeers.update { current ->
                        current.filterNot { it.address == device.address || com.example.bluemesh.utils.uuidsMatch(it.uuid, peerUuid) }
                    }
                    return
                }
                val hasPasscode = (passcodeByte and 0x03) != 0
                val isOfficial = (passcodeByte and 0x02) != 0
                val allowTracking = (passcodeByte and 0x04) != 0
                val nameOffset = if (manufacturerData.size >= 9) 9 else 8
                val displayName = String(manufacturerData, nameOffset, manufacturerData.size - nameOffset, Charsets.UTF_8).trim()
                
                if (displayName.isNotEmpty()) {
                    _discoveredPeers.update { current ->
                        var found = false
                        val updated = current.map { existing ->
                            val existingNorm = com.example.bluemesh.utils.normalizeUuid(existing.uuid)
                            val matches = existing.address == device.address || com.example.bluemesh.utils.uuidsMatch(existing.uuid, peerUuid)
                            if (matches) {
                                found = true
                                val smoothedRssi = if (existing.rssi == -100) result.rssi else (0.25 * result.rssi + 0.75 * existing.rssi).toInt()
                                existing.copy(
                                    address = device.address, name = displayName, device = device, lastSeen = System.currentTimeMillis(),
                                    uuid = if (existingNorm.length > 16) existing.uuid else peerUuid,
                                    hasPasscode = hasPasscode, isOfficial = isOfficial, rssi = smoothedRssi, allowTracking = allowTracking
                                )
                            } else existing
                        }
                        val finalResult = if (found) updated else updated + BluetoothPeer(
                            device.address, displayName, device, System.currentTimeMillis(), peerUuid, hasPasscode, isOfficial, result.rssi, allowTracking
                        )
                        finalResult.sortedWith(compareBy({ it.name.lowercase() }, { it.address }))
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            showToast("Scan failed with error $errorCode")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            _isAdvertising.value = true
            isStartingAdvertising = false
        }

        override fun onStartFailure(errorCode: Int) {
            _isAdvertising.value = false
            isStartingAdvertising = false
            if (errorCode != 3) {
                showToast("Advertising failed with error $errorCode")
            }
        }
    }

    private fun handleServerDisconnect(device: BluetoothDevice) {
        try {
            cancelServerCccdTimeout(device.address)
            connectedClients.remove(device)
            _isReady.value = false
            deviceMtus.remove(device.address)
            cccdStates.remove(device.address)
            updateDeviceStatus(device.address, ConnectionStatus.DISCONNECTED)
            val peerUuid = _discoveredPeers.value.find { it.address == device.address }?.uuid
            if (peerUuid != null) {
                onPeerDisconnectedCallback?.invoke(peerUuid)
            }
            if (connectedServerDevice == null && connectedClients.isEmpty()) {
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
            }
            // Cancel any pending writes immediately
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleServerDisconnect", e)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            try {
                if (status != 0 || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    handleServerDisconnect(device)
                    return
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (device.bondState != BluetoothDevice.BOND_NONE) {
                        try { device.javaClass.getMethod("removeBond").invoke(device) } catch (e: Exception) {}
                    }
                    connectedClients.add(device)
                    updateDeviceStatus(device.address, ConnectionStatus.SYNCHRONIZING)
                    _connectionStatus.value = ConnectionStatus.SYNCHRONIZING
                    startServerCccdTimeout(device)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in gattServerCallback.onConnectionStateChange", e)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            try {
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
                if (characteristic.uuid == MESSAGE_CHARACTERISTIC_UUID && value != null) {
                    handleIncomingValue(device, value)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in gattServerCallback.onCharacteristicWriteRequest", e)
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            try {
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, byteArrayOf())
            } catch (e: Exception) {
                Log.e(TAG, "Error in gattServerCallback.onCharacteristicReadRequest", e)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            try {
                if (descriptor.uuid == CCCD_UUID && value != null) {
                    cccdStates[device.address] = value
                    val isNotificationEnabled = value.isNotEmpty() && (value[0].toInt() and 0x01 != 0)
                    if (isNotificationEnabled) {
                        cancelServerCccdTimeout(device.address)
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                        _isReady.value = true
                        updateDeviceStatus(device.address, ConnectionStatus.CONNECTED)
                        val peerUuid = _discoveredPeers.value.find { it.address == device.address }?.uuid ?: ""
                        onPeerReadyCallback?.invoke(peerUuid, device)
                    }
                }
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in gattServerCallback.onDescriptorWriteRequest", e)
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            try {
                val value = if (descriptor.uuid == CCCD_UUID) cccdStates[device.address] ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE else byteArrayOf()
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            } catch (e: Exception) {
                Log.e(TAG, "Error in gattServerCallback.onDescriptorReadRequest", e)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            try {
                deviceMtus[device.address] = mtu
            } catch (e: Exception) {
                Log.e(TAG, "Error in gattServerCallback.onMtuChanged", e)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            try {
                connectionTimeoutJob?.cancel()
                
                if (status != 0 || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    cancelClientSyncTimeout()
                    val disconnectedAddress = gatt.device.address
                    deviceMtus.remove(disconnectedAddress)
                    val wasAttemptingConnection = (_connectionStatus.value == ConnectionStatus.CONNECTING || _connectionStatus.value == ConnectionStatus.SYNCHRONIZING)
                    
                    if (status != 0 && wasAttemptingConnection) {
                        Log.w(TAG, "GATT connection failure status $status for $disconnectedAddress. Healing and retrying.")
                        refreshDeviceCache(gatt)
                    }
                    
                    try { gatt.close() } catch (e: Exception) {}
                    updateDeviceStatus(disconnectedAddress, ConnectionStatus.DISCONNECTED)
                    if (bluetoothGatt == gatt) bluetoothGatt = null
                    isDisconnecting.set(false)
                    connectedServerDevice = null
                    val peerUuid = _discoveredPeers.value.find { it.address == disconnectedAddress }?.uuid
                    if (peerUuid != null) {
                        onPeerDisconnectedCallback?.invoke(peerUuid)
                    }
                    if (connectedClients.isEmpty()) {
                        _isReady.value = false
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                    }
                    currentWriteDeferred?.complete(false)
                    currentWriteDeferred = null
                    
                    if (status != 0 && wasAttemptingConnection && !isCurrentConnectionRetry) {
                        scope.launch {
                            delay(500)
                            Log.d(TAG, "Triggering connection retry for $disconnectedAddress")
                            connectToPeerInternal(gatt.device, true)
                        }
                    }
                    return
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    isDisconnecting.set(false)
                    connectedServerDevice = gatt.device
                    updateDeviceStatus(gatt.device.address, ConnectionStatus.SYNCHRONIZING)
                    _connectionStatus.value = ConnectionStatus.SYNCHRONIZING
                    discoveryRetryCount = 0
                    startClientSyncTimeout(gatt)
                    scope.launch {
                        try {
                            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                            gatt.requestMtu(512)
                            delay(600)
                            gatt.discoverServices()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error requesting connection configuration/discovery", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in gattCallback.onConnectionStateChange", e)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            try {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    deviceMtus[gatt.device.address] = mtu
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onMtuChanged callback", e)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            try {
                if (status != 0) {
                    if (discoveryRetryCount < 1) {
                        discoveryRetryCount++
                        scope.launch {
                            try {
                                Log.w(TAG, "onServicesDiscovered error status $status. Refreshing cache and retrying.")
                                refreshDeviceCache(gatt)
                                delay(1000)
                                gatt.discoverServices()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error retrying discoverServices on status error", e)
                            }
                        }
                    }
                    return
                }
                val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(MESSAGE_CHARACTERISTIC_UUID)
                
                if (characteristic != null) {
                    messageCharacteristic = characteristic
                    try {
                        gatt.setCharacteristicNotification(characteristic, true)
                    } catch (e: Exception) {
                        Log.e(TAG, "setCharacteristicNotification failed", e)
                    }
                    scope.launch {
                        try {
                            delay(200)
                            characteristic.getDescriptor(CCCD_UUID)?.let { descriptor ->
                                for (attempt in 1..3) {
                                    val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
                                    } else {
                                        @Suppress("DEPRECATION")
                                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                        @Suppress("DEPRECATION")
                                        gatt.writeDescriptor(descriptor)
                                    }
                                    if (success) break
                                    delay(300)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error writing CCCD descriptor", e)
                        }
                    }
                } else {
                    if (discoveryRetryCount < 1) {
                        discoveryRetryCount++
                        scope.launch {
                            try {
                                Log.w(TAG, "Chat service not found in discovery. Refreshing cache and retrying.")
                                refreshDeviceCache(gatt)
                                delay(1000)
                                gatt.discoverServices()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error retrying discoverServices", e)
                            }
                        }
                    } else {
                        showToast("Peer's chat service not found")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onServicesDiscovered", e)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            try {
                if (descriptor.uuid == CCCD_UUID) {
                    _isReady.value = true
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                    updateDeviceStatus(gatt.device.address, ConnectionStatus.CONNECTED)
                    val peerUuid = _discoveredPeers.value.find { it.address == gatt.device.address }?.uuid ?: ""
                    onPeerReadyCallback?.invoke(peerUuid, gatt.device)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onDescriptorWrite", e)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            try {
                currentWriteDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onCharacteristicWrite", e)
            }
        }


        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            try {
                if (characteristic.uuid == MESSAGE_CHARACTERISTIC_UUID) {
                    @Suppress("DEPRECATION")
                    val value = characteristic.value ?: return
                    handleIncomingValue(gatt.device, value)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onCharacteristicChanged", e)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            try {
                if (characteristic.uuid == MESSAGE_CHARACTERISTIC_UUID) {
                    handleIncomingValue(gatt.device, value)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onCharacteristicChanged (Tiramisu)", e)
            }
        }
    }

    private fun startClientSyncTimeout(gatt: BluetoothGatt) {
        clientSyncTimeoutJob?.cancel()
        clientSyncTimeoutJob = scope.launch {
            try {
                delay(10000) // 10-second timeout
                if (_connectionStatus.value == ConnectionStatus.SYNCHRONIZING) {
                    Log.w(TAG, "Client synchronization timeout for ${gatt.device.address}. Healing connection.")
                    refreshDeviceCache(gatt)
                    disconnect()
                    delay(300)
                    if (!isCurrentConnectionRetry) {
                        connectToPeerInternal(gatt.device, true)
                    } else {
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                        updateDeviceStatus(gatt.device.address, ConnectionStatus.DISCONNECTED)
                        showToast("Failed to synchronize with peer. Please try again.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in clientSyncTimeoutJob", e)
            }
        }
    }

    private fun cancelClientSyncTimeout() {
        clientSyncTimeoutJob?.cancel()
        clientSyncTimeoutJob = null
    }

    private fun startServerCccdTimeout(device: BluetoothDevice) {
        serverCccdTimeoutJobs[device.address]?.cancel()
        serverCccdTimeoutJobs[device.address] = scope.launch {
            delay(10000) // 10-second timeout
            if (connectedClients.contains(device) && (cccdStates[device.address] == null)) {
                Log.w(TAG, "Server CCCD write timeout for ${device.address}. Disconnecting.")
                try {
                    bluetoothGattServer?.cancelConnection(device)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cancelling connection on CCCD timeout", e)
                }
                handleServerDisconnect(device)
            }
            serverCccdTimeoutJobs.remove(device.address)
        }
    }

    private fun cancelServerCccdTimeout(deviceAddress: String) {
        serverCccdTimeoutJobs[deviceAddress]?.cancel()
        serverCccdTimeoutJobs.remove(deviceAddress)
    }

    private fun startEvictionTimer() {
        evictionJob?.cancel()
        evictionJob = scope.launch {
            while (true) {
                delay(2000)
                val now = System.currentTimeMillis()
                _discoveredPeers.update { current ->
                    current.filter { peer ->
                        val isConnected = (bluetoothGatt?.device?.address == peer.address) || 
                                          synchronized(connectedClients) { connectedClients.any { it.address == peer.address } }
                        isConnected || (now - peer.lastSeen < 25000)
                    }.sortedWith(compareBy({ it.name.lowercase() }, { it.address }))
                }
                
                // Evict stale chunk reassembly buffers
                val keysToRemove = mutableListOf<String>()
                lastChunkTimestamp.forEach { (k, timestamp) ->
                    if (now - timestamp > 5000) {
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

    private fun startPhysicalScan() {
        if (!hasPermissions()) return
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled || !isLocationEnabled()) return
        
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
            ScanFilter.Builder().setManufacturerData(PEER_MANUFACTURER_ID, byteArrayOf()).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(MESH_SERVICE_UUID_STR))).build()
        )

        val isPowerSave = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
        val settings = ScanSettings.Builder()
            .setScanMode(if (isPowerSave) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            scanner?.startScan(filters, settings, scanCallback)
            startEvictionTimer()
        } catch (e: Exception) {
            Log.e(TAG, "startPhysicalScan failed", e)
        }
    }

    private fun stopPhysicalScan() {
        try { scanner?.stopScan(scanCallback) } catch (e: Exception) {}
        evictionJob?.cancel()
        evictionJob = null
    }

    fun hasPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        return permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            android.provider.Settings.Secure.getInt(
                context.contentResolver,
                android.provider.Settings.Secure.LOCATION_MODE,
                android.provider.Settings.Secure.LOCATION_MODE_OFF
            ) != android.provider.Settings.Secure.LOCATION_MODE_OFF
        }
    }

    fun clearDiscoveredPeers() {
        _discoveredPeers.value = emptyList()
    }

    fun startScanning() {
        if (_isScanning.value) return
        val timeSinceLastScan = System.currentTimeMillis() - lastScanStartTime
        if (timeSinceLastScan < 3000) return

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            showToast("Please enable Bluetooth to scan for peers")
            return
        }
        if (!isLocationEnabled()) {
            showToast("Please enable Location/GPS to scan for peers")
            return
        }

        _isScanning.value = true
        lastScanStartTime = System.currentTimeMillis()

        scanLoopJob?.cancel()
        scanLoopJob = scope.launch {
            try {
                while (true) {
                    startPhysicalScan()
                    delay(300000)
                    stopPhysicalScan()
                    delay(100)
                }
            } finally {
                stopPhysicalScan()
            }
        }
    }

    fun stopScanning() {
        if (!_isScanning.value) return
        _isScanning.value = false
        scanLoopJob?.cancel()
        scanLoopJob = null
        stopPhysicalScan()
    }

    fun startAdvertising(displayName: String, isGoodbye: Boolean = false) {
        lastDisplayName = displayName
        if (!hasPermissions()) {
            _isAdvertising.value = false
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            showToast("Please enable Bluetooth to make your device discoverable")
            _isAdvertising.value = false
            return
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && !isLocationEnabled()) {
            showToast("Please enable Location/GPS to make your device discoverable")
            _isAdvertising.value = false
            return
        }

        if (_isAdvertising.value || isStartingAdvertising) return
        isStartingAdvertising = true

        scope.launch {
            try {
                var advertiserObj = advertiser
                var retries = 3
                while (advertiserObj == null && retries > 0) {
                    delay(150)
                    advertiserObj = advertiser
                    retries--
                }

                if (advertiserObj == null) {
                    showToast("Advertising is not supported on this device")
                    _isAdvertising.value = false
                    return@launch
                }

                stopGattServer()
                startGattServer()

                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .build()

                val prefs = context.getSharedPreferences("bluemesh_prefs", Context.MODE_PRIVATE)
                val uuidStr = prefs.getString("user_uuid", "") ?: ""
                val userUuid = if (uuidStr.isNotEmpty()) UUID.fromString(uuidStr) else UUID.randomUUID()
                val shortUuidBytes = uuidToBytes(userUuid).copyOfRange(0, 8)

                var truncatedName = displayName
                while (truncatedName.toByteArray(Charsets.UTF_8).size > 18) {
                    truncatedName = truncatedName.dropLast(1)
                }
                val nameBytes = truncatedName.toByteArray(Charsets.UTF_8)

                val isPasscode = prefs.getBoolean("is_passcode_enabled", false)
                val isOfficial = false
                val isShareLocation = prefs.getBoolean("is_share_location_enabled", true)
                
                var passcodeFlag = ((if (isOfficial) 2 else if (isPasscode) 1 else 0) or (if (isShareLocation) 4 else 0)).toByte()
                if (isGoodbye) {
                    passcodeFlag = (passcodeFlag.toInt() or 0x08).toByte()
                }

                val manufacturerData = ByteArray(9 + nameBytes.size).apply {
                    System.arraycopy(shortUuidBytes, 0, this, 0, 8)
                    this[8] = passcodeFlag
                    System.arraycopy(nameBytes, 0, this, 9, nameBytes.size)
                }

                val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).build()
                val scanResponseData = AdvertiseData.Builder().addManufacturerData(PEER_MANUFACTURER_ID, manufacturerData).build()

                try {
                    advertiserObj.startAdvertising(settings, data, scanResponseData, advertiseCallback)
                } catch (e: Exception) {
                    _isAdvertising.value = false
                    showToast("Failed to start advertising: ${e.localizedMessage}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in startAdvertising coroutine", e)
                _isAdvertising.value = false
            }
        }
    }

    fun stopAdvertising() {
        if (!hasPermissions()) return
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "stopAdvertising failed", e)
        }
        _isAdvertising.value = false
        isStartingAdvertising = false
    }

    fun stopAdvertisingWithGoodbye() {
        if (!hasPermissions()) return
        val name = lastDisplayName
        if (name.isNotEmpty() && _isAdvertising.value) {
            stopAdvertising()
            scope.launch {
                try {
                    startAdvertising(name, isGoodbye = true)
                    delay(800)
                    stopAdvertising()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in stopAdvertisingWithGoodbye", e)
                }
            }
        } else {
            stopAdvertising()
        }
    }

    private val meshAdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {}
        override fun onStartFailure(errorCode: Int) {}
    }

    fun advertiseMeshMessage(messageId: Int, senderUuid: String, recipientUuid: String, messageText: String) {
        if (!hasPermissions()) return
        val advertiserObj = advertiser ?: return

        meshAdvertiseJob?.cancel()
        meshAdvertiseJob = scope.launch {
            var wasAdvertisingDiscovery = false
            try {
                wasAdvertisingDiscovery = _isAdvertising.value
                if (wasAdvertisingDiscovery) {
                    stopAdvertising()
                    delay(150)
                }

                val senderHash = if (senderUuid.startsWith("mesh_")) senderUuid.substringAfter("mesh_").toIntOrNull() ?: senderUuid.hashCode() else senderUuid.hashCode()
                val recipientHash = if (recipientUuid.startsWith("mesh_")) recipientUuid.substringAfter("mesh_").toIntOrNull() ?: recipientUuid.hashCode() else recipientUuid.hashCode()
                val messageBytes = messageText.toByteArray(Charsets.UTF_8)
                
                val truncatedBytes = if (messageBytes.size > 15) messageBytes.copyOfRange(0, 15) else messageBytes

                val manufacturerSpecificData = ByteBuffer.allocate(12 + truncatedBytes.size).apply {
                    order(ByteOrder.BIG_ENDIAN)
                    putInt(messageId)
                    putInt(senderHash)
                    putInt(recipientHash)
                    put(truncatedBytes)
                }.array()

                val advertiseData = AdvertiseData.Builder().addServiceUuid(ParcelUuid(UUID.fromString(MESH_SERVICE_UUID_STR))).build()
                val scanResponseData = AdvertiseData.Builder().addManufacturerData(MESH_COMPANY_ID, manufacturerSpecificData).build()
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .build()

                try {
                    advertiserObj.startAdvertising(settings, advertiseData, scanResponseData, meshAdvertiseCallback)
                    delay(4000)
                } finally {
                    try {
                        advertiserObj.stopAdvertising(meshAdvertiseCallback)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to stop mesh advertising", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in advertiseMeshMessage coroutine", e)
            } finally {
                if (wasAdvertisingDiscovery && lastDisplayName.isNotEmpty()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        try {
                            delay(150)
                            startAdvertising(lastDisplayName)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to restart discovery advertising", e)
                        }
                    }
                }
            }
        }
    }

    private fun startGattServer() {
        if (bluetoothGattServer != null) return
        try {
            val server = bluetoothManager.openGattServer(context, gattServerCallback) ?: return
            bluetoothGattServer = server

            val characteristic = BluetoothGattCharacteristic(
                MESSAGE_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
            ).apply {
                addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ))
            }
            
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(characteristic)
            }
            server.addService(service)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting GATT Server", e)
        }
    }

    private fun stopGattServer() {
        try {
            bluetoothGattServer?.close()
            bluetoothGattServer = null
        } catch (e: Exception) {}
    }

    fun connectToPeer(device: BluetoothDevice) {
        connectToPeerInternal(device, false)
    }

    private fun refreshDeviceCache(gatt: BluetoothGatt) {
        try {
            val refreshMethod = gatt.javaClass.getMethod("refresh")
            refreshMethod.invoke(gatt)
        } catch (e: Exception) {}
    }

    private fun connectToPeerInternal(device: BluetoothDevice, isRetry: Boolean) {
        if (!hasPermissions()) return
        connectionTimeoutJob?.cancel()
        isCurrentConnectionRetry = isRetry
        
        val isClientConnected = bluetoothGatt?.device?.address == device.address
        val isServerConnected = connectedClients.any { it.address == device.address }
        val isAlreadyConnected = (isClientConnected || isServerConnected) &&
                (_connectionStatus.value == ConnectionStatus.CONNECTED ||
                 _connectionStatus.value == ConnectionStatus.SYNCHRONIZING ||
                 _connectionStatus.value == ConnectionStatus.CONNECTING)

        if (isAlreadyConnected) {
            Log.d(TAG, "Already connected/connecting to ${device.address}. Skipping reconnect.")
            return
        }

        synchronized(connectedClients) {
            val differentClients = connectedClients.filter { it.address != device.address }
            for (clientDev in differentClients) {
                Log.d(TAG, "Tearing down existing server connection to ${clientDev.address} before connecting to ${device.address}")
                try {
                    bluetoothGattServer?.cancelConnection(clientDev)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to cancel server connection to ${clientDev.address}", e)
                }
                handleServerDisconnect(clientDev)
            }
        }

        bluetoothGatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (e: Exception) {}
            bluetoothGatt = null
            isDisconnecting.set(false)
        }

        if (device.bondState != BluetoothDevice.BOND_NONE) {
            try { device.javaClass.getMethod("removeBond").invoke(device) } catch (e: Exception) {}
        }

        // Do not call global disconnect() to avoid kicking other GATT server clients.
        // Instead, only reset the local client states.
        connectedServerDevice = null
        messageCharacteristic = null

        updateDeviceStatus(device.address, ConnectionStatus.CONNECTING)
        _connectionStatus.value = ConnectionStatus.CONNECTING
        _isReady.value = false
        discoveryRetryCount = 0
        deviceMtus.remove(device.address)

        try {
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
            bluetoothGatt = gatt
        } catch (e: Exception) {
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            return
        }

        connectionTimeoutJob = scope.launch {
            try {
                delay(15000)
                if (_connectionStatus.value == ConnectionStatus.CONNECTING) {
                    if (!isRetry) {
                        disconnect()
                        delay(300)
                        connectToPeerInternal(device, true)
                    } else {
                        disconnect()
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                        showToast("Connection timed out. Please try again.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in connectionTimeoutJob launch", e)
            }
        }
    }

    fun disconnect() {
        connectionTimeoutJob?.cancel()
        cancelClientSyncTimeout()
        currentWriteDeferred?.complete(false)
        currentWriteDeferred = null
        deviceMtus.clear()

        synchronized(connectedClients) {
            connectedClients.forEach { device ->
                try {
                    bluetoothGattServer?.cancelConnection(device)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cancelling client connection from server", e)
                }
                updateDeviceStatus(device.address, ConnectionStatus.DISCONNECTED)
            }
            connectedClients.clear()
        }

        bluetoothGatt?.device?.address?.let {
            updateDeviceStatus(it, ConnectionStatus.DISCONNECTED)
        }

        val gatt = bluetoothGatt
        if (gatt == null) {
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            _isReady.value = false
            connectedServerDevice = null
            messageCharacteristic = null
            return
        }

        if (isDisconnecting.get()) return
        isDisconnecting.set(true)
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _isReady.value = false
        connectedServerDevice = null
        messageCharacteristic = null
        try {
            gatt.disconnect()
            scope.launch {
                try {
                    delay(100)
                    if (isDisconnecting.get()) {
                        try { gatt.close() } catch (e: Exception) {}
                        bluetoothGatt = null
                        isDisconnecting.set(false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in disconnect close launch", e)
                }
            }
        } catch (e: Exception) {
            try { gatt.close() } catch (ex: Exception) {}
            bluetoothGatt = null
            isDisconnecting.set(false)
        }
    }

    fun getConnectedDeviceAddress(): String? {
        synchronized(connectedClients) {
            connectedClients.firstOrNull()?.let { return it.address }
        }
        connectedServerDevice?.let { return it.address }
        return null
    }

    private fun uuidToBytes(uuid: UUID): ByteArray {
        return ByteBuffer.wrap(ByteArray(16)).apply {
            putLong(uuid.mostSignificantBits)
            putLong(uuid.leastSignificantBits)
        }.array()
    }

    private fun bytesToUuid(bytes: ByteArray): UUID {
        val byteBuffer = ByteBuffer.wrap(bytes)
        return UUID(byteBuffer.long, byteBuffer.long)
    }

    private fun parseAndDecompress(value: ByteArray): ByteArray? {
        if (value.size < 2) return null
        if (value[0] != 1.toByte()) return null
        
        val flags = value[1].toInt()
        val isEncrypted = (flags and 2) != 0
        val isCompressed = (flags and 1) != 0
        
        return if (isEncrypted) {
            if (value.size < 6) null else value.copyOfRange(2, value.size)
        } else {
            try {
                if (isCompressed) {
                    val compressedData = value.copyOfRange(2, value.size)
                    val decompressor = LZ4Factory.fastestInstance().safeDecompressor()
                    val tempDest = ByteArray(32768)
                    val decompressedLength = decompressor.decompress(compressedData, 0, compressedData.size, tempDest, 0, tempDest.size)
                    tempDest.copyOfRange(0, decompressedLength)
                } else {
                    value.copyOfRange(2, value.size)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun sendChunksInternal(chunks: List<ByteArray>, targetDevice: BluetoothDevice? = null): Boolean {
        val charClient = messageCharacteristic
        val gattClient = bluetoothGatt
        if (gattClient != null && charClient != null && (targetDevice == null || targetDevice.address == gattClient.device.address)) {
            var success = true
            for (chunk in chunks) {
                val deferred = CompletableDeferred<Boolean>()
                currentWriteDeferred = deferred
                val writeSuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gattClient.writeCharacteristic(charClient, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    charClient.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    charClient.value = chunk
                    @Suppress("DEPRECATION")
                    gattClient.writeCharacteristic(charClient)
                }
                if (!writeSuccess) {
                    currentWriteDeferred = null
                    success = false
                    break
                }
                val callbackResult = withTimeoutOrNull(2000) { deferred.await() }
                currentWriteDeferred = null
                if (callbackResult != true) {
                    success = false
                    break
                }
            }
            if (success) return true
        }

        val server = bluetoothGattServer
        val activeClient = if (targetDevice != null) {
            synchronized(connectedClients) { connectedClients.find { it.address == targetDevice.address } } ?: targetDevice
        } else {
            synchronized(connectedClients) {
                if (connectedClients.size == 1) connectedClients.first() else null
            }
        }
        if (server != null && activeClient != null) {
            val characteristic = server.getService(SERVICE_UUID)?.getCharacteristic(MESSAGE_CHARACTERISTIC_UUID)
            if (characteristic != null) {
                var success = true
                for (chunk in chunks) {
                    val notifySuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        server.notifyCharacteristicChanged(activeClient, characteristic, false, chunk) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        characteristic.value = chunk
                        @Suppress("DEPRECATION")
                        server.notifyCharacteristicChanged(activeClient, characteristic, false)
                    }
                    if (!notifySuccess) {
                        success = false
                        break
                    }
                    if (chunks.size > 1) {
                        delay(80)
                    }
                }
                if (success) return true
            }
        }
        return false
    }

    suspend fun sendMessage(text: String): Boolean {
        val bytes = encodePayload(System.currentTimeMillis(), text)
        return sendMessageSuspend(bytes)
    }

    suspend fun sendMessageSuspend(bytes: ByteArray, targetDevice: BluetoothDevice? = null): Boolean = gattWriteMutex.withLock {
        try {
            var payload: ByteArray
            try {
                val fastCompressor = LZ4Factory.fastestInstance().fastCompressor()
                val maxCompressedLength = fastCompressor.maxCompressedLength(bytes.size)
                val compressedBuffer = ByteArray(maxCompressedLength)
                val compressedSize = fastCompressor.compress(bytes, 0, bytes.size, compressedBuffer, 0, maxCompressedLength)
                
                if (compressedSize < bytes.size) {
                    payload = ByteArray(compressedSize + 2).apply {
                        this[0] = 1
                        this[1] = 1
                        System.arraycopy(compressedBuffer, 0, this, 2, compressedSize)
                    }
                } else {
                    payload = ByteArray(bytes.size + 2).apply {
                        this[0] = 1
                        this[1] = 0
                        System.arraycopy(bytes, 0, this, 2, bytes.size)
                    }
                }
            } catch (e: Exception) {
                payload = ByteArray(bytes.size + 2).apply {
                    this[0] = 1
                    this[1] = 0
                    System.arraycopy(bytes, 0, this, 2, bytes.size)
                }
            }

            val targetAddress = targetDevice?.address ?: getConnectedDeviceAddress()
            val deviceMtu = if (targetAddress != null) deviceMtus[targetAddress] ?: 23 else 23
            val maxPayloadSize = java.lang.Math.min(deviceMtu - 6, 509) // GATT attribute value is capped at 512 bytes, overhead is 3 bytes BLE + 3 bytes chunk header
            val rawChunks = payload.toList().chunked(maxPayloadSize).map { it.toByteArray() }
            val totalChunks = rawChunks.size
            val chunks = rawChunks.mapIndexed { index, data ->
                ByteArray(data.size + 3).apply {
                    this[0] = 1 // Message type
                    this[1] = index.toByte()
                    this[2] = totalChunks.toByte()
                    System.arraycopy(data, 0, this, 3, data.size)
                }
            }

            val success = sendChunksInternal(chunks, targetDevice)
            if (success) {
                val text = decodePayload(bytes)?.second ?: String(bytes, Charsets.UTF_8)
                _messages.tryEmit(ChatMessage(text, isFromMe = true, timestamp = System.currentTimeMillis()))
            }
            return@withLock success
        } catch (e: Exception) {
            Log.e(TAG, "Exception in sendMessageSuspend", e)
            false
        }
    }

    suspend fun sendMessage(bytes: ByteArray, targetDevice: BluetoothDevice? = null): Boolean = sendMessageSuspend(bytes, targetDevice)

    suspend fun sendAck(timestamp: Long, targetDevice: BluetoothDevice? = null): Boolean = gattWriteMutex.withLock {
        try {
            val payload = ByteArray(8).apply {
                ByteBuffer.wrap(this).putLong(timestamp)
            }
            val chunk = ByteArray(payload.size + 3).apply {
                this[0] = 3 // ACK type
                this[1] = 0 // chunk index 0
                this[2] = 1 // total chunks 1
                System.arraycopy(payload, 0, this, 3, payload.size)
            }

            return@withLock sendChunksInternal(listOf(chunk), targetDevice)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in sendAck", e)
            false
        }
    }

    suspend fun sendPublicKey(myUuidStr: String, publicKeyBytes: ByteArray, targetDevice: BluetoothDevice? = null): Boolean = gattWriteMutex.withLock {
        try {
            val uuid = try { UUID.fromString(myUuidStr) } catch(e: Exception) { return@withLock false }
            val payload = ByteArray(publicKeyBytes.size + 17).apply {
                this[0] = 2
                this[1] = 0
                System.arraycopy(uuidToBytes(uuid), 0, this, 1, 16)
                System.arraycopy(publicKeyBytes, 0, this, 17, publicKeyBytes.size)
            }

            val targetAddress = targetDevice?.address ?: getConnectedDeviceAddress()
            val deviceMtu = if (targetAddress != null) deviceMtus[targetAddress] ?: 23 else 23
            val maxPayloadSize = java.lang.Math.min(deviceMtu - 6, 509) // GATT attribute value is capped at 512 bytes, overhead is 3 bytes BLE + 3 bytes chunk header
            val rawChunks = payload.toList().chunked(maxPayloadSize).map { it.toByteArray() }
            val totalChunks = rawChunks.size
            val chunks = rawChunks.mapIndexed { index, data ->
                ByteArray(data.size + 3).apply {
                    this[0] = 2 // Handshake type
                    this[1] = index.toByte()
                    this[2] = totalChunks.toByte()
                    System.arraycopy(data, 0, this, 3, data.size)
                }
            }

            return@withLock sendChunksInternal(chunks, targetDevice)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in sendPublicKey", e)
            false
        }
    }

    suspend fun sendMessageEncrypted(ciphertext: ByteArray, messageId: Int, targetDevice: BluetoothDevice? = null): Boolean {
        val payload = ByteArray(ciphertext.size + 6).apply {
            this[0] = 1
            this[1] = 2
            val encryptedMessageId = messageId or java.lang.Integer.MIN_VALUE
            val idBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(encryptedMessageId).array()
            System.arraycopy(idBytes, 0, this, 2, 4)
            System.arraycopy(ciphertext, 0, this, 6, ciphertext.size)
        }
        // Reuse the serialized send path
        return sendMessage(payload, targetDevice)
    }

    fun isClient(): Boolean = bluetoothGatt != null

    fun getConnectedDeviceByAddress(address: String): BluetoothDevice? {
        synchronized(connectedClients) {
            connectedClients.find { it.address == address }?.let { return it }
        }
        val gattDevice = bluetoothGatt?.device
        if (gattDevice?.address == address) return gattDevice
        return null
    }

    fun updatePeerUuid(fullUuid: String, deviceAddress: String) {
        _discoveredPeers.update { current ->
            var found = false
            val updated = current.map { peer ->
                val matches = com.example.bluemesh.utils.uuidsMatch(peer.uuid, fullUuid) || peer.address == deviceAddress
                if (matches) {
                    found = true
                    peer.copy(uuid = fullUuid, address = deviceAddress, lastSeen = System.currentTimeMillis())
                } else peer
            }
            if (found) {
                updated
            } else {
                val device = getConnectedDeviceByAddress(deviceAddress)
                val name = device?.name ?: "Mesh Peer"
                updated + BluetoothPeer(
                    address = deviceAddress,
                    name = name,
                    device = device,
                    lastSeen = System.currentTimeMillis(),
                    uuid = fullUuid
                )
            }
        }
        val status = deviceConnectionStatuses[deviceAddress] ?: ConnectionStatus.DISCONNECTED
        onPeerConnectionStatusChanged?.invoke(fullUuid, status)
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    fun cleanUp() {
        stopScanning()
        stopAdvertising()
        disconnect()
        stopGattServer()
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(bluetoothStateReceiver)
                isReceiverRegistered = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister bluetooth state receiver", e)
            }
        }
    }
}
