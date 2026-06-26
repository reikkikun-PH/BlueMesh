package com.example.bluemesh.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.bluemesh.data.models.BluetoothPeer
import com.example.bluemesh.data.models.ChatMessage
import com.example.bluemesh.data.models.ConnectionStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@SuppressLint("MissingPermission")
class BluetoothHandler(private val context: Context) {

    companion object {
        fun encodePayload(timestamp: Long, text: String): ByteArray = MessageProcessor.encodePayload(timestamp, text)
        fun decodePayload(payload: ByteArray): Pair<Long, String>? = MessageProcessor.decodePayload(payload)
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private val tracker = ConnectionTracker(context, scope)
    private val messageProcessor = MessageProcessor(tracker)
    private val gattServerManager = GattServerManager(context, scope, tracker, messageProcessor)
    private val gattClientManager = GattClientManager(context, scope, tracker, messageProcessor)
    private val packetBroadcaster = PacketBroadcaster(scope, tracker, gattServerManager, gattClientManager)

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner
    private val advertiser: BluetoothLeAdvertiser? get() = bluetoothAdapter?.bluetoothLeAdvertiser

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    val discoveredPeers: StateFlow<List<BluetoothPeer>> = tracker.discoveredPeers
    val messages: SharedFlow<ChatMessage> = tracker.messages
    val acks: SharedFlow<Pair<Long, Long?>> = tracker.acks

    var onPeerConnectionStatusChanged: ((String, ConnectionStatus) -> Unit)? = null
    var onPeerReadyCallback: ((String, BluetoothDevice) -> Unit)? = null
    var onKeyExchangeReceived: ((String, ByteArray, BluetoothDevice) -> Unit)? = null
        set(value) {
            field = value
            messageProcessor.onKeyExchangeReceived = value
        }
    var onPeerDisconnectedCallback: ((String) -> Unit)? = null
    var onBluetoothStateOn: (() -> Unit)? = null
    var onMeshAckReceived: ((originalMessageId: Int, senderHash: Int, recipientHash: Int) -> Unit)? = null

    @Volatile private var lastDisplayName: String = ""
    @Volatile private var isStartingAdvertising = false
    private var lastScanStartTime: Long = 0
    private var meshAdvertiseJob: Job? = null
    private var scanLoopJob: Job? = null

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED == intent.action) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    Log.d(Constants.TAG, "Bluetooth turned ON. Restarting GATT server.")
                    scope.launch {
                        try {
                            delay(Constants.BLE_RESET_DELAY_MS)
                            var ready = startGattServer()
                            var retries = 0
                            while (!ready && retries < Constants.GATT_SERVER_RETRIES) {
                                delay(500)
                                ready = startGattServer()
                                retries++
                            }
                            if (!ready) {
                                Log.e(Constants.TAG, "Failed to start GATT server after ${retries + 1} attempts")
                            }
                            onBluetoothStateOn?.invoke()
                        } catch (e: Exception) {
                            Log.e(Constants.TAG, "Error invoking onBluetoothStateOn", e)
                        }
                    }
                } else if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    Log.d(Constants.TAG, "Bluetooth turned OFF. Cleaning up.")
                    try {
                        stopScanning()
                        disconnect()
                        gattServerManager.stopGattServer()
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                        _isReady.value = false
                        tracker.clearAll()
                    } catch (e: Exception) {
                        Log.e(Constants.TAG, "Error cleaning up on Bluetooth state off", e)
                    }
                }
            }
        }
    }

    init {
        tracker.onPeerConnectionStatusChanged = { uuid, status ->
            _isReady.value = tracker.isPeerReady(uuid)
            if (status == ConnectionStatus.CONNECTED) {
                _connectionStatus.value = ConnectionStatus.CONNECTED
            } else if (status == ConnectionStatus.SYNCHRONIZING) {
                _connectionStatus.value = ConnectionStatus.SYNCHRONIZING
            } else if (status == ConnectionStatus.DISCONNECTED) {
                if (tracker.clientConnections.isEmpty() && tracker.connectedClients.isEmpty()) {
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                }
            }
            onPeerConnectionStatusChanged?.invoke(uuid, status)
        }
        tracker.onPeerDisconnectedCallback = onPeerDisconnectedCallback
        gattServerManager.onPeerReadyCallback = { uuid, device ->
            onPeerReadyCallback?.invoke(uuid, device)
        }
        gattServerManager.onPeerDisconnectedCallback = { uuid ->
            onPeerDisconnectedCallback?.invoke(uuid)
        }
        gattClientManager.onPeerReadyCallback = { uuid, device ->
            onPeerReadyCallback?.invoke(uuid, device)
        }
        gattClientManager.onPeerDisconnectedCallback = { uuid ->
            onPeerDisconnectedCallback?.invoke(uuid)
        }
        gattClientManager.onDisconnectedCallback {
            if (tracker.connectedClients.isEmpty() && tracker.clientConnections.isEmpty()) {
                _isReady.value = false
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
            }
        }

        try {
            context.registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Failed to register bluetooth state receiver", e)
        }
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

    private fun getMyShortUuidHex(): String {
        val prefs = context.getSharedPreferences("bluemesh_prefs", Context.MODE_PRIVATE)
        val uuidStr = prefs.getString("user_uuid", "") ?: ""
        if (uuidStr.isEmpty()) return ""
        return try {
            val userUuid = UUID.fromString(uuidStr)
            uuidToBytes(userUuid).copyOfRange(0, 8).toHexString()
        } catch (_: Exception) { "" }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val scanRecord = result.scanRecord ?: return
            val serviceUuids = scanRecord.serviceUuids

            if (serviceUuids != null && serviceUuids.contains(Constants.MESH_SERVICE_UUID_PARCEL)) {
                val manufacturerData = scanRecord.getManufacturerSpecificData(Constants.MESH_COMPANY_ID)
                if (manufacturerData != null && manufacturerData.size >= 12) {
                    val buffer = ByteBuffer.wrap(manufacturerData).order(ByteOrder.BIG_ENDIAN)
                    val messageId = buffer.int
                    val senderHash = buffer.int
                    val recipientHash = buffer.int
                    val messageText = String(manufacturerData.copyOfRange(12, manufacturerData.size), Charsets.UTF_8)
                    // Check for mesh ACK: text starts with "ACK|"
                    if (messageText.startsWith("ACK|")) {
                        val originalMessageId = messageText.substringAfter("ACK|").toIntOrNull()
                        if (originalMessageId != null) {
                            onMeshAckReceived?.invoke(originalMessageId, senderHash, recipientHash)
                        }
                        return
                    }
                    if (!tracker.isDuplicateMessage(messageId)) {
                        tracker.tryEmitMessage(ChatMessage(text = messageText, isFromMe = false, timestamp = System.currentTimeMillis(), senderHash = senderHash, recipientHash = recipientHash, messageId = messageId, viaRelay = true))
                    }
                    tracker.updateDiscoveredPeers { current ->
                        current.map { peer ->
                            if (com.example.bluemesh.utils.normalizeUuid(peer.uuid).hashCode() == senderHash && !peer.isRelayed) {
                                peer.copy(isRelayed = true)
                            } else peer
                        }
                    }
                }
                return
            }

            val manufacturerData = scanRecord.getManufacturerSpecificData(Constants.PEER_MANUFACTURER_ID)
            if (manufacturerData == null || manufacturerData.size < 8) {
                if (serviceUuids != null && serviceUuids.contains(Constants.SERVICE_UUID_PARCEL)) {
                    tracker.updateDiscoveredPeers { current ->
                        val index = current.indexOfFirst { it.address == device.address }
                        if (index < 0) {
                            // Unknown device without identity data — don't add
                            current
                        } else {
                            // Device exists but no longer advertising identity. Update device ref
                            // but do NOT refresh lastSeen — let it expire naturally from peer list
                            current.mapIndexed { i, peer ->
                                if (i == index) peer.copy(device = device) else peer
                            }
                        }
                    }
                }
                return
            }
            if (manufacturerData.size >= 8) {
                try {
                    val peerUuid = manufacturerData.copyOfRange(0, 8).toHexString()
                    if (peerUuid.equals(getMyShortUuidHex(), ignoreCase = true)) return
                    val passcodeByte = if (manufacturerData.size >= 9) manufacturerData[8].toInt() else 0
                    val isGoodbye = (passcodeByte and 0x08) != 0
                    if (isGoodbye) {
                        tracker.updateDiscoveredPeers { current ->
                            current.filterNot { it.address == device.address || com.example.bluemesh.utils.uuidsMatch(it.uuid, peerUuid) }
                        }
                        return
                    }
                    val hasPasscode = (passcodeByte and 0x03) != 0
                    val isOfficial = (passcodeByte and 0x02) != 0
                    val allowTracking = (passcodeByte and 0x04) != 0
                    val isRelayed = (passcodeByte and 0x10) != 0
                    val isRelayBeacon = isRelayed && peerUuid == "0000000000000000"
                    // Skip adding relay beacon to peer list (it's not a real user)
                    if (isRelayBeacon) return
                    val nameOffset = if (manufacturerData.size >= 9) 9 else 8
                    val displayName = String(manufacturerData, nameOffset, manufacturerData.size - nameOffset, Charsets.UTF_8).trim()
                    if (displayName.isNotEmpty()) {
                        tracker.setPeerDisplayName(peerUuid, displayName)
                        tracker.updateDiscoveredPeers { current ->
                            var found = false
                            val updated = current.map { existing ->
                                val matches = existing.address == device.address || com.example.bluemesh.utils.uuidsMatch(existing.uuid, peerUuid)
                                if (matches) {
                                    found = true
                                    val smoothedRssi = if (existing.rssi == -100) result.rssi else (0.25 * result.rssi + 0.75 * existing.rssi).toInt()
                                    // Don't overwrite address or device with relay MAC when receiving relayed ads,
                                    // and keep isRelayed sticky once set to prevent flickering
                                    existing.copy(address = if (isRelayed) existing.address else device.address,
                                        name = displayName, device = if (isRelayed) existing.device else device, lastSeen = System.currentTimeMillis(),
                                        uuid = if (com.example.bluemesh.utils.normalizeUuid(existing.uuid).length > 16) existing.uuid else peerUuid,
                                        hasPasscode = hasPasscode, isOfficial = isOfficial, rssi = smoothedRssi,
                                        allowTracking = allowTracking, isRelayed = existing.isRelayed || isRelayed)
                                } else existing
                            }
                            val finalResult = if (found) updated else updated + BluetoothPeer(device.address, displayName, if (isRelayed) null else device, System.currentTimeMillis(), peerUuid, hasPasscode, isOfficial, result.rssi, allowTracking, isRelayed)
                            finalResult.sortedWith(compareBy({ it.name.lowercase() }, { it.address }))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(Constants.TAG, "Error processing user advertisement", e)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
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
        }
    }

    private val meshAdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {}
        override fun onStartFailure(errorCode: Int) {}
    }

    fun startScanning() {
        if (_isScanning.value) return
        val timeSinceLastScan = System.currentTimeMillis() - lastScanStartTime
        if (timeSinceLastScan < Constants.SCAN_RATE_LIMIT_MS) return
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled || !isLocationEnabled()) return
        _isScanning.value = true
        lastScanStartTime = System.currentTimeMillis()
        scanLoopJob?.cancel()
        scanLoopJob = scope.launch {
            try {
                while (true) {
                    startPhysicalScan()
                    delay(Constants.SCAN_DURATION_MS)
                    stopPhysicalScan()
                    delay(Constants.SCAN_COOLDOWN_MS)
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

    private fun startPhysicalScan() {
        if (!hasPermissions()) return
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled || !isLocationEnabled()) return
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(Constants.SERVICE_UUID)).build(),
            ScanFilter.Builder().setManufacturerData(Constants.PEER_MANUFACTURER_ID, byteArrayOf()).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(Constants.MESH_SERVICE_UUID_STR))).build()
        )
        val isPowerSave = (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode
        val settings = ScanSettings.Builder()
            .setScanMode(if (isPowerSave) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            scanner?.startScan(filters, settings, scanCallback)
        } catch (e: Exception) {
            Log.e(Constants.TAG, "startPhysicalScan failed", e)
        }
    }

    private fun stopPhysicalScan() {
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    fun startAdvertising(displayName: String, isGoodbye: Boolean = false) {
        lastDisplayName = displayName
        if (!hasPermissions()) { _isAdvertising.value = false; return }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) { _isAdvertising.value = false; return }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && !isLocationEnabled()) { _isAdvertising.value = false; return }
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
                if (advertiserObj == null) { _isAdvertising.value = false; return@launch }
                startGattServer()
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true).build()
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
                val isOfficial = com.example.bluemesh.BuildConfig.IS_OFFICIAL_APP
                val isShareLocation = prefs.getBoolean("is_share_location_enabled", true)
                var passcodeFlag = ((if (isOfficial) 2 else if (isPasscode) 1 else 0) or (if (isShareLocation) 4 else 0)).toByte()
                if (isGoodbye) passcodeFlag = (passcodeFlag.toInt() or 0x08).toByte()
                val manufacturerData = ByteArray(9 + nameBytes.size).apply {
                    System.arraycopy(shortUuidBytes, 0, this, 0, 8)
                    this[8] = passcodeFlag
                    System.arraycopy(nameBytes, 0, this, 9, nameBytes.size)
                }
                val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(Constants.SERVICE_UUID)).build()
                val scanResponseData = AdvertiseData.Builder().addManufacturerData(Constants.PEER_MANUFACTURER_ID, manufacturerData).build()
                try {
                    advertiserObj.startAdvertising(settings, data, scanResponseData, advertiseCallback)
                } catch (e: Exception) {
                    _isAdvertising.value = false
                }
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Error in startAdvertising", e)
                _isAdvertising.value = false
            }
        }
    }

    fun stopAdvertising() {
        if (!hasPermissions()) return
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
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
                    delay(Constants.ADVERTISE_GOODBYE_DURATION_MS)
                    stopAdvertising()
                } catch (e: Exception) {
                    Log.e(Constants.TAG, "Error in stopAdvertisingWithGoodbye", e)
                }
            }
        } else {
            stopAdvertising()
        }
    }

    fun cancelMeshAdvertise() {
        meshAdvertiseJob?.cancel()
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
                val truncatedBytes = if (messageBytes.size > 17) messageBytes.copyOfRange(0, 17) else messageBytes
                val manufacturerSpecificData = ByteBuffer.allocate(12 + truncatedBytes.size).apply {
                    order(ByteOrder.BIG_ENDIAN)
                    putInt(messageId)
                    putInt(senderHash)
                    putInt(recipientHash)
                    put(truncatedBytes)
                }.array()
                val advertiseData = AdvertiseData.Builder().addServiceUuid(ParcelUuid(UUID.fromString(Constants.MESH_SERVICE_UUID_STR))).build()
                val scanResponseData = AdvertiseData.Builder().addManufacturerData(Constants.MESH_COMPANY_ID, manufacturerSpecificData).build()
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false).build()
                try {
                    advertiserObj.startAdvertising(settings, advertiseData, scanResponseData, meshAdvertiseCallback)
                    delay(Constants.MESH_ADVERTISE_DURATION_MS)
                } finally {
                    try { advertiserObj.stopAdvertising(meshAdvertiseCallback) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Exception in advertiseMeshMessage", e)
            } finally {
                if (wasAdvertisingDiscovery && lastDisplayName.isNotEmpty()) {
                    withContext(NonCancellable) {
                        try { delay(150); startAdvertising(lastDisplayName) } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    fun setMyUuid(uuid: String) {
        tracker.myUuid = uuid
    }

    fun connectToPeer(device: BluetoothDevice) {
        gattClientManager.connectToPeer(device)
    }

    fun connectToPeerByAddress(address: String) {
        try {
            val device = bluetoothAdapter?.getRemoteDevice(address)
            if (device != null) {
                gattClientManager.connectToPeer(device)
            }
        } catch (e: Exception) {
            Log.e(Constants.TAG, "connectToPeerByAddress failed for $address", e)
        }
    }

    fun disconnect() {
        gattClientManager.cancelAll()
        tracker.currentWriteDeferreds.values.forEach { it.complete(false) }
        tracker.currentWriteDeferreds.clear()
        tracker.currentNotificationDeferreds.values.forEach { it.complete(false) }
        tracker.currentNotificationDeferreds.clear()
        tracker.deviceMtus.clear()
        tracker.actualSendTimes.clear()
        tracker.otaToCreationTime.clear()
        tracker.connectedClients.forEach { device ->
            try { gattServerManager.bluetoothGattServer?.cancelConnection(device) } catch (_: Exception) {}
            tracker.updateDeviceStatus(device.address, ConnectionStatus.DISCONNECTED)
        }
        tracker.connectedClients.clear()
        val addresses = tracker.clientConnections.keys.toList()
        addresses.forEach { addr ->
            tracker.clientConnections.remove(addr)?.let { conn ->
                tracker.updateDeviceStatus(addr, ConnectionStatus.DISCONNECTED)
                try { conn.gatt.disconnect(); conn.gatt.close() } catch (_: Exception) {}
            }
        }
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _isReady.value = false
    }

    fun disconnectClient(deviceAddress: String) {
        gattClientManager.disconnectClient(deviceAddress)
        if (tracker.clientConnections.isEmpty() && tracker.connectedClients.isEmpty()) {
            _isReady.value = false
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
        }
    }

    fun resetClientConnection() {
        gattClientManager.resetClientConnection()
        _isReady.value = false
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }

    fun disconnectFromPeer(uuid: String) {
        val addresses = mutableSetOf<String>()
        tracker.addressToUuid.entries.forEach { (addr, trackedUuid) ->
            if (com.example.bluemesh.utils.uuidsMatch(trackedUuid, uuid)) addresses.add(addr)
        }
        tracker.uuidToServerAddress.entries.forEach { (trackedUuid, addr) ->
            if (com.example.bluemesh.utils.uuidsMatch(trackedUuid, uuid)) addresses.add(addr)
        }
        tracker.discoveredPeers.value.filter { com.example.bluemesh.utils.uuidsMatch(it.uuid, uuid) }.forEach {
            addresses.add(it.address)
        }
        if (addresses.isEmpty()) {
            Log.d(Constants.TAG, "disconnectFromPeer: no address found for UUID $uuid")
            return
        }
        Log.d(Constants.TAG, "disconnectFromPeer $uuid at addresses $addresses")
        addresses.forEach { addr ->
            gattClientManager.disconnectClient(addr)
            gattServerManager.disconnectServerClient(addr)
        }
    }

    suspend fun sendMessageSuspend(bytes: ByteArray, targetDevice: BluetoothDevice? = null): Boolean {
        return packetBroadcaster.sendMessageSuspend(bytes, targetDevice)
    }

    suspend fun sendAck(timestamp: Long, targetDevice: BluetoothDevice? = null): Boolean {
        return packetBroadcaster.sendAck(timestamp, targetDevice)
    }

    suspend fun sendPublicKey(myUuidStr: String, publicKeyBytes: ByteArray, targetDevice: BluetoothDevice? = null): Boolean {
        return packetBroadcaster.sendPublicKey(myUuidStr, publicKeyBytes, targetDevice)
    }

    fun isClient(): Boolean = tracker.isClient()

    fun clearDiscoveredPeers() {
        tracker.clearDiscoveredPeers()
        stopPhysicalScan()
        startPhysicalScan()
    }

    fun clearAllTracker() = tracker.clearAll()

    fun stopGattServer() = gattServerManager.stopGattServer()

    fun getConnectionStatusForAddress(address: String): ConnectionStatus = tracker.getConnectionStatusForAddress(address)

    fun isAlreadyServerConnected(device: BluetoothDevice): Boolean = tracker.isAlreadyServerConnected(device)

    fun getConnectionStatusForPeer(peerUuid: String): ConnectionStatus = tracker.getConnectionStatusForPeer(peerUuid)

    fun isPeerConnected(peerUuid: String): Boolean = tracker.isPeerConnected(peerUuid)

    fun isPeerReady(peerUuid: String): Boolean = tracker.isPeerReady(peerUuid)

    fun isStuckInSynchronizing(address: String, timeoutMs: Long = 15000): Boolean = tracker.isStuckInSynchronizing(address, timeoutMs)

    fun getConnectedDeviceAddress(): String? = tracker.getConnectedDeviceAddress()

    fun getConnectedDeviceByPeerUuid(peerUuid: String): BluetoothDevice? = tracker.getConnectedDeviceByPeerUuid(peerUuid)

    fun getConnectedDeviceByAddress(address: String): BluetoothDevice? = tracker.getConnectedDeviceByAddress(address)

    fun getDeviceByUuid(uuid: String): BluetoothDevice? = tracker.getDeviceByUuid(uuid)

    fun getCachedAddressForUuid(uuid: String): String? = tracker.getCachedAddressForUuid(uuid)

    fun getUuidByAddress(deviceAddress: String): String? = tracker.getUuidByAddress(deviceAddress)

    fun getUuidMapDump(): String = tracker.getUuidMapDump()

    fun updatePeerUuid(fullUuid: String, deviceAddress: String) = tracker.updatePeerUuid(fullUuid, deviceAddress)

    fun refreshPeerFromServerAddress(uuid: String) = tracker.refreshPeerFromServerAddress(uuid)

    fun setPeerNameResolver(resolver: (String) -> String) {
        tracker.onResolvePeerName = resolver
    }

    fun ensureGattServerRunning(): Boolean {
        if (hasPermissions()) {
            return startGattServer()
        }
        return false
    }

    private fun startGattServer(): Boolean {
        val result = gattServerManager.startGattServer()
        if (result) {
            _connectionStatus.value = ConnectionStatus.CONNECTED
        }
        return result
    }

    private fun uuidToBytes(uuid: UUID): ByteArray {
        return ByteBuffer.wrap(ByteArray(16)).apply {
            putLong(uuid.mostSignificantBits)
            putLong(uuid.leastSignificantBits)
        }.array()
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}
