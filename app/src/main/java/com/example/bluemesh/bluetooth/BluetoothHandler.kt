package com.example.bluemesh.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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

    private var connectedClientDevice: BluetoothDevice? = null
    private var connectedServerDevice: BluetoothDevice? = null

    private val cccdStates = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    
    private var evictionJob: Job? = null
    private var scanLoopJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var isDisconnecting = false
    private var lastScanStartTime: Long = 0
    private var discoveryRetryCount = 0
    private var negotiatedMtu = 23
    
    private val messageIdCache = java.util.Collections.synchronizedList(mutableListOf<Int>())
    private var meshAdvertiseJob: Job? = null
    private var lastDisplayName: String = ""

    private val scope = CoroutineScope(Dispatchers.Default)

    private val _discoveredPeers = MutableStateFlow<List<BluetoothPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<BluetoothPeer>> = _discoveredPeers.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _messages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 128)
    val messages: SharedFlow<ChatMessage> = _messages.asSharedFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    var onPeerReadyCallback: ((String) -> Unit)? = null
    var onKeyExchangeReceived: ((String, ByteArray) -> Unit)? = null

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
            
            val manufacturerData = scanRecord.getManufacturerSpecificData(SupportMenu.USER_MASK) ?: return
            if (manufacturerData.size >= 8) {
                val peerUuid = manufacturerData.copyOfRange(0, 8).toHexString()
                val passcodeByte = if (manufacturerData.size >= 9) manufacturerData[8].toInt() else 0
                val hasPasscode = (passcodeByte and 0x03) != 0
                val isOfficial = (passcodeByte and 0x02) != 0
                val allowTracking = (passcodeByte and 0x04) != 0
                val nameOffset = if (manufacturerData.size >= 9) 9 else 8
                val displayName = String(manufacturerData, nameOffset, manufacturerData.size - nameOffset, Charsets.UTF_8).trim()
                
                if (displayName.isNotEmpty()) {
                    _discoveredPeers.update { current ->
                        var found = false
                        val updated = current.map { existing ->
                            val existingNorm = existing.uuid.replace("-", "").lowercase()
                            val peerNorm = peerUuid.replace("-", "").lowercase()
                            val matches = existing.address == device.address || existingNorm == peerNorm ||
                                    (existingNorm.length == 16 && peerNorm.startsWith(existingNorm)) ||
                                    (peerNorm.length == 16 && existingNorm.startsWith(peerNorm))
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
        }

        override fun onStartFailure(errorCode: Int) {
            _isAdvertising.value = false
            showToast("Advertising failed with error $errorCode")
        }
    }

    private fun handleServerDisconnect(device: BluetoothDevice) {
        if (connectedClientDevice?.address == device.address) {
            connectedClientDevice = null
        }
        _isReady.value = false
        negotiatedMtu = 23
        cccdStates.remove(device.address)
        if (connectedServerDevice == null && connectedClientDevice == null) {
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (status != 0 || newState == BluetoothProfile.STATE_DISCONNECTED) {
                handleServerDisconnect(device)
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (device.bondState != BluetoothDevice.BOND_NONE) {
                    try { device.javaClass.getMethod("removeBond").invoke(device) } catch (e: Exception) {}
                }
                connectedClientDevice = device
                _connectionStatus.value = ConnectionStatus.CONNECTED
                _isReady.value = true
                _discoveredPeers.value.find { it.address == device.address }?.uuid?.let { onPeerReadyCallback?.invoke(it) }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (responseNeeded) {
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            if (characteristic.uuid == MESSAGE_CHARACTERISTIC_UUID && value != null) {
                if (value.isNotEmpty() && value[0] == 2.toByte()) {
                    if (value.size >= 17) {
                        val senderUuid = bytesToUuid(value.copyOfRange(1, 17)).toString()
                        onKeyExchangeReceived?.invoke(senderUuid, value.copyOfRange(17, value.size))
                    }
                    return
                }
                parseAndDecompress(value)?.let {
                    _messages.tryEmit(ChatMessage(String(it, Charsets.ISO_8859_1), isFromMe = false, timestamp = System.currentTimeMillis()))
                }
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, byteArrayOf())
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD_UUID && value != null) {
                cccdStates[device.address] = value
            }
            if (responseNeeded) {
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val value = if (descriptor.uuid == CCCD_UUID) cccdStates[device.address] ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE else byteArrayOf()
            bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            negotiatedMtu = mtu
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            connectionTimeoutJob?.cancel()
            
            if (status != 0 || newState == BluetoothProfile.STATE_DISCONNECTED) {
                try { gatt.close() } catch (e: Exception) {}
                if (bluetoothGatt == gatt) bluetoothGatt = null
                isDisconnecting = false
                connectedServerDevice = null
                _isReady.value = false
                negotiatedMtu = 23
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isDisconnecting = false
                connectedServerDevice = gatt.device
                _connectionStatus.value = ConnectionStatus.SYNCHRONIZING
                discoveryRetryCount = 0
                scope.launch {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    gatt.requestMtu(512)
                    delay(600)
                    gatt.discoverServices()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != 0) return
            val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(MESSAGE_CHARACTERISTIC_UUID)
            
            if (characteristic != null) {
                messageCharacteristic = characteristic
                gatt.setCharacteristicNotification(characteristic, true)
                scope.launch {
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
                }
            } else {
                if (discoveryRetryCount < 1) {
                    discoveryRetryCount++
                    scope.launch {
                        delay(1000)
                        gatt.discoverServices()
                    }
                } else {
                    showToast("Peer's chat service not found")
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID) {
                _isReady.value = true
                _connectionStatus.value = ConnectionStatus.CONNECTED
                _discoveredPeers.value.find { it.address == gatt.device.address }?.uuid?.let { onPeerReadyCallback?.invoke(it) }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == MESSAGE_CHARACTERISTIC_UUID) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                if (value.isNotEmpty() && value[0] == 2.toByte()) {
                    if (value.size >= 17) {
                        val senderUuid = bytesToUuid(value.copyOfRange(1, 17)).toString()
                        onKeyExchangeReceived?.invoke(senderUuid, value.copyOfRange(17, value.size))
                    }
                    return
                }
                parseAndDecompress(value)?.let {
                    _messages.tryEmit(ChatMessage(String(it, Charsets.ISO_8859_1), isFromMe = false, timestamp = System.currentTimeMillis()))
                }
            }
        }
    }

    private fun startEvictionTimer() {
        evictionJob?.cancel()
        evictionJob = scope.launch {
            while (true) {
                delay(2000)
                val now = System.currentTimeMillis()
                _discoveredPeers.update { current ->
                    current.filter { now - it.lastSeen < 8000 }
                        .sortedWith(compareBy({ it.name.lowercase() }, { it.address }))
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
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        return permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun startScanning() {
        val timeSinceLastScan = System.currentTimeMillis() - lastScanStartTime
        if (timeSinceLastScan < 3000) return

        if (_isScanning.value) {
            stopScanning()
            _discoveredPeers.value = emptyList()
            if (bluetoothGattServer != null) {
                stopGattServer()
                startGattServer()
            }
            scope.launch {
                delay(200)
                startScanning()
            }
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            showToast("Please enable Bluetooth to scan for peers")
            return
        }
        if (!isLocationEnabled()) {
            showToast("Please enable Location/GPS to scan for peers")
            return
        }

        _discoveredPeers.value = emptyList()
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

    fun startAdvertising(displayName: String) {
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

        if (_isAdvertising.value) return

        scope.launch {
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
            val isShareLocation = prefs.getBoolean("is_share_location_enabled", false)
            
            val passcodeFlag = ((if (isOfficial) 2 else if (isPasscode) 1 else 0) or (if (isShareLocation) 4 else 0)).toByte()

            val manufacturerData = ByteArray(9 + nameBytes.size).apply {
                System.arraycopy(shortUuidBytes, 0, this, 0, 8)
                this[8] = passcodeFlag
                System.arraycopy(nameBytes, 0, this, 9, nameBytes.size)
            }

            val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).build()
            val scanResponseData = AdvertiseData.Builder().addManufacturerData(SupportMenu.USER_MASK, manufacturerData).build()

            try {
                advertiserObj.startAdvertising(settings, data, scanResponseData, advertiseCallback)
            } catch (e: Exception) {
                _isAdvertising.value = false
                showToast("Failed to start advertising: ${e.localizedMessage}")
            }
        }
    }

    fun stopAdvertising() {
        if (!hasPermissions() || !_isAdvertising.value) return
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            _isAdvertising.value = false
        } catch (e: Exception) {
            Log.e(TAG, "stopAdvertising failed", e)
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
            val wasAdvertisingDiscovery = _isAdvertising.value
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
                advertiserObj.stopAdvertising(meshAdvertiseCallback)
            } catch (e: Exception) {
                Log.e(TAG, "advertiseMeshMessage failed", e)
            }

            if (wasAdvertisingDiscovery && lastDisplayName.isNotEmpty()) {
                delay(150)
                startAdvertising(lastDisplayName)
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
        
        bluetoothGatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (e: Exception) {}
            bluetoothGatt = null
            isDisconnecting = false
        }

        if (device.bondState != BluetoothDevice.BOND_NONE) {
            try { device.javaClass.getMethod("removeBond").invoke(device) } catch (e: Exception) {}
        }

        disconnect()
        _connectionStatus.value = ConnectionStatus.CONNECTING
        _isReady.value = false
        discoveryRetryCount = 0
        negotiatedMtu = 23

        try {
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
            bluetoothGatt = gatt
            if (gatt != null) refreshDeviceCache(gatt)
            
            scope.launch {
                delay(500)
                if (_connectionStatus.value == ConnectionStatus.CONNECTING) gatt?.connect()
            }
        } catch (e: Exception) {
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            return
        }

        connectionTimeoutJob = scope.launch {
            delay(5000)
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
        }
    }

    fun disconnect() {
        connectionTimeoutJob?.cancel()
        val gatt = bluetoothGatt ?: return
        if (isDisconnecting) return
        isDisconnecting = true
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _isReady.value = false
        connectedServerDevice = null
        messageCharacteristic = null
        try {
            gatt.disconnect()
            scope.launch {
                delay(100)
                if (isDisconnecting) {
                    try { gatt.close() } catch (e: Exception) {}
                    bluetoothGatt = null
                    isDisconnecting = false
                }
            }
        } catch (e: Exception) {
            try { gatt.close() } catch (ex: Exception) {}
            bluetoothGatt = null
            isDisconnecting = false
        }
    }

    fun getConnectedDeviceAddress(): String? {
        connectedClientDevice?.let { return it.address }
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

    fun sendMessage(text: String): Boolean = sendMessage(encodePayload(System.currentTimeMillis(), text))

    fun sendMessage(bytes: ByteArray): Boolean {
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

        val maxPayloadSize = negotiatedMtu - 3
        val chunks = payload.toList().chunked(maxPayloadSize).map { it.toByteArray() }

        // Client send
        val charClient = messageCharacteristic
        val gattClient = bluetoothGatt
        if (gattClient != null && charClient != null) {
            var success = true
            for (chunk in chunks) {
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
                    success = false
                    break
                }
                if (chunks.size > 1) {
                    try { Thread.sleep(50) } catch (e: InterruptedException) {}
                }
            }
            if (success) {
                val text = decodePayload(bytes)?.second ?: String(bytes, Charsets.UTF_8)
                _messages.tryEmit(ChatMessage(text, isFromMe = true, timestamp = System.currentTimeMillis()))
                return true
            }
        }

        // Server notify
        val server = bluetoothGattServer
        val activeClient = connectedClientDevice
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
                        try { Thread.sleep(50) } catch (e: InterruptedException) {}
                    }
                }
                if (success) {
                    val text = decodePayload(bytes)?.second ?: String(bytes, Charsets.UTF_8)
                    _messages.tryEmit(ChatMessage(text, isFromMe = true, timestamp = System.currentTimeMillis()))
                    return true
                }
            }
        }
        return false
    }

    fun sendPublicKey(myUuidStr: String, publicKeyBytes: ByteArray): Boolean {
        val uuid = try { UUID.fromString(myUuidStr) } catch(e: Exception) { return false }
        val payload = ByteArray(publicKeyBytes.size + 17).apply {
            this[0] = 2
            this[1] = 0
            System.arraycopy(uuidToBytes(uuid), 0, this, 1, 16)
            System.arraycopy(publicKeyBytes, 0, this, 17, publicKeyBytes.size)
        }

        val charClient = messageCharacteristic
        val gattClient = bluetoothGatt
        if (gattClient != null && charClient != null) {
            @Suppress("DEPRECATION")
            charClient.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            charClient.value = payload
            return gattClient.writeCharacteristic(charClient)
        }

        val server = bluetoothGattServer
        val activeClient = connectedClientDevice
        if (server != null && activeClient != null) {
            val characteristic = server.getService(SERVICE_UUID)?.getCharacteristic(MESSAGE_CHARACTERISTIC_UUID)
            if (characteristic != null) {
                @Suppress("DEPRECATION")
                characteristic.value = payload
                return server.notifyCharacteristicChanged(activeClient, characteristic, false)
            }
        }
        return false
    }

    fun sendMessageEncrypted(ciphertext: ByteArray, messageId: Int): Boolean {
        val payload = ByteArray(ciphertext.size + 6).apply {
            this[0] = 1
            this[1] = 2
            val encryptedMessageId = messageId or java.lang.Integer.MIN_VALUE
            val idBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(encryptedMessageId).array()
            System.arraycopy(idBytes, 0, this, 2, 4)
            System.arraycopy(ciphertext, 0, this, 6, ciphertext.size)
        }

        val maxPayloadSize = negotiatedMtu - 3
        val chunks = payload.toList().chunked(maxPayloadSize).map { it.toByteArray() }

        // Client send
        val charClient = messageCharacteristic
        val gattClient = bluetoothGatt
        if (gattClient != null && charClient != null) {
            var success = true
            for (chunk in chunks) {
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
                    success = false
                    break
                }
                if (chunks.size > 1) {
                    try { Thread.sleep(50) } catch(e: Exception) {}
                }
            }
            if (success) return true
        }

        // Server notify
        val server = bluetoothGattServer
        val activeClient = connectedClientDevice
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
                        try { Thread.sleep(50) } catch(e: Exception) {}
                    }
                }
                if (success) return true
            }
        }
        return false
    }

    fun updatePeerUuid(fullUuid: String) {
        val normalized = fullUuid.replace("-", "").lowercase()
        val shortUuid = if (normalized.length >= 16) normalized.take(16) else normalized
        _discoveredPeers.update { current ->
            current.map { peer ->
                val peerNorm = peer.uuid.replace("-", "").lowercase()
                if (peerNorm == shortUuid || peerNorm == normalized) peer.copy(uuid = fullUuid) else peer
            }
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    fun cleanUp() {
        stopScanning()
        stopAdvertising()
        disconnect()
        stopGattServer()
    }
}
