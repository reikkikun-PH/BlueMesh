package com.example.bitchat_lite.bluetooth

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
import com.example.bitchat_lite.data.models.BluetoothPeer
import com.example.bitchat_lite.data.models.ChatMessage
import com.example.bitchat_lite.data.models.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jpountz.lz4.LZ4Factory
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CancellationException

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
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager.adapter

    private val scanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    private val advertiser: BluetoothLeAdvertiser?
        get() = bluetoothAdapter?.bluetoothLeAdvertiser

    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var messageCharacteristic: BluetoothGattCharacteristic? = null

    private var connectedClientDevice: BluetoothDevice? = null
    private var connectedServerDevice: BluetoothDevice? = null

    private val cccdStates = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    
    private var evictionJob: Job? = null
    private var scanTimeoutJob: Job? = null
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
            if (messageIdCache.contains(messageId)) {
                return true
            }
            if (messageIdCache.size >= CACHE_MAX_SIZE) {
                messageIdCache.removeAt(0)
            }
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
                    val buffer = ByteBuffer.wrap(manufacturerData)
                    buffer.order(ByteOrder.BIG_ENDIAN)
                    val messageId = buffer.int
                    val senderHash = buffer.int
                    val recipientHash = buffer.int
                    
                    val textBytes = manufacturerData.copyOfRange(12, manufacturerData.size)
                    val messageText = String(textBytes, Charsets.UTF_8)
                    
                    if (!isDuplicateMessage(messageId)) {
                        Log.i(TAG, "onScanResult (Mesh): Captured new message ID $messageId: $messageText")
                        _messages.tryEmit(
                            ChatMessage(
                                text = messageText,
                                isFromMe = false,
                                timestamp = System.currentTimeMillis(),
                                senderHash = senderHash,
                                recipientHash = recipientHash
                            )
                        )
                    }
                }
                return
            }
            
            val manufacturerData = scanRecord.getManufacturerSpecificData(SupportMenu.USER_MASK)
            if (manufacturerData != null && manufacturerData.size >= 16) {
                val peerUuid = bytesToUuid(manufacturerData.copyOfRange(0, 16)).toString()
                val hasPasscode = if (manufacturerData.size >= 17) manufacturerData[16] == 1.toByte() else false
                val nameOffset = if (manufacturerData.size >= 17) 17 else 16
                val displayName = String(manufacturerData, nameOffset, manufacturerData.size - nameOffset, Charsets.UTF_8).trim()
                Log.d(TAG, "onScanResult: Parsed BlueMesh display name '$displayName', hasPasscode=$hasPasscode, and UUID '$peerUuid' for ${device.address}")
                
                if (displayName.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val peer = BluetoothPeer(
                        address = device.address,
                        name = displayName,
                        device = device,
                        lastSeen = now,
                        uuid = peerUuid,
                        hasPasscode = hasPasscode
                    )
                    _discoveredPeers.update { current ->
                        val filtered = current.filterNot { it.address == peer.address || it.uuid == peer.uuid }
                        filtered + peer
                    }
                }
            } else {
                Log.d(TAG, "onScanResult: Discarding ${device.address} (missing User_UUID manufacturer data)")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "onScanFailed: BLE scanning failed with error code $errorCode")
            _isScanning.value = false
            val errorMsg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started."
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed."
                SCAN_FAILED_INTERNAL_ERROR -> "Internal scanning error."
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scanning feature unsupported."
                else -> "Scan failed with error $errorCode"
            }
            showToast(errorMsg)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "onStartSuccess: BLE advertising started successfully")
            _isAdvertising.value = true
        }

        override fun onStartFailure(errorCode: Int) {
            val adapter = bluetoothAdapter
            val adapterState = adapter?.state
            Log.e(TAG, "onStartFailure: BLE advertising failed with error code $errorCode. Adapter State: $adapterState, isEnabled: ${adapter?.isEnabled}")
            _isAdvertising.value = false
            val errorMsg = when (errorCode) {
                1 -> "Display name too long for BLE payload."
                2 -> "Too many active BLE advertisers. Close other apps."
                3 -> "Advertising already started."
                4 -> "Internal advertising error occurred."
                5 -> "BLE Advertising unsupported on this hardware."
                else -> "Advertising failed with error $errorCode"
            }
            showToast(errorMsg)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val bondStateStr = when (device.bondState) {
                BluetoothDevice.BOND_NONE -> "BOND_NONE"
                BluetoothDevice.BOND_BONDING -> "BOND_BONDING"
                BluetoothDevice.BOND_BONDED -> "BOND_BONDED"
                else -> "UNKNOWN"
            }
            Log.i(TAG, "Server connection state change for ${device.address}: state=$newState, status=$status, bondState=$bondStateStr")
            
            if (status != 0) {
                Log.e(TAG, "Server connection error for ${device.address}: status=$status. Resetting server-side connection.")
                if (connectedClientDevice?.address == device.address) {
                    connectedClientDevice = null
                }
                _isReady.value = false
                negotiatedMtu = 23
                cccdStates.remove(device.address)
                if (connectedServerDevice == null && connectedClientDevice == null) {
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                }
                return
            }

            when (newState) {
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedClientDevice?.address == device.address) {
                        connectedClientDevice = null
                    }
                    _isReady.value = false
                    negotiatedMtu = 23
                    cccdStates.remove(device.address)
                    Log.d(TAG, "Server: Cleared CCCD state for disconnected device ${device.address}")
                    if (connectedServerDevice == null && connectedClientDevice == null) {
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                    }
                }
                BluetoothProfile.STATE_CONNECTED -> {
                    if (device.bondState != BluetoothDevice.BOND_NONE) {
                        Log.d(TAG, "Server: Device ${device.address} has bond. Removing bond for pairless communication.")
                        try {
                            val method = device.javaClass.getMethod("removeBond")
                            method.invoke(device)
                        } catch (e: Exception) {
                            Log.e(TAG, "Server failed to remove bond", e)
                        }
                    }
                    connectedClientDevice = device
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                    _isReady.value = true
                    Log.i(TAG, "Server: Client connected and ready for messaging.")
                    
                    val peer = _discoveredPeers.value.find { it.address == device.address }
                    val peerUuid = peer?.uuid
                    if (peerUuid != null) {
                        onPeerReadyCallback?.invoke(peerUuid)
                    }
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                bluetoothGattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                )
            }
            if (characteristic.uuid == MESSAGE_CHARACTERISTIC_UUID && value != null) {
                val messageText = parseAndDecompress(value)
                if (messageText != null) {
                    Log.i(TAG, "Server received write request from ${device.address}: $messageText")
                    _messages.tryEmit(ChatMessage(messageText, isFromMe = false, timestamp = System.currentTimeMillis()))
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d(TAG, "Server characteristic read request for ${characteristic.uuid} from ${device.address}")
            val value = byteArrayOf()
            bluetoothGattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                value
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            Log.d(TAG, "Server descriptor write request for ${descriptor.uuid} from ${device.address}")
            if (descriptor.uuid == CCCD_UUID && value != null) {
                cccdStates[device.address] = value
                Log.d(TAG, "Server: Stored CCCD value for device ${device.address}: ${value.joinToString()}")
            }
            if (responseNeeded) {
                bluetoothGattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                )
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            Log.d(TAG, "Server descriptor read request for ${descriptor.uuid} from ${device.address}")
            val value = if (descriptor.uuid == CCCD_UUID) {
                cccdStates[device.address] ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            } else {
                byteArrayOf()
            }
            bluetoothGattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                value
            )
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            negotiatedMtu = mtu
            Log.i(TAG, "Server MTU negotiated to $mtu for device ${device.address}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val bondStateStr = when (gatt.device.bondState) {
                BluetoothDevice.BOND_NONE -> "BOND_NONE"
                BluetoothDevice.BOND_BONDING -> "BOND_BONDING"
                BluetoothDevice.BOND_BONDED -> "BOND_BONDED"
                else -> "UNKNOWN"
            }
            Log.i(TAG, "Client connection state change status=$status newState=$newState. Device bondState: $bondStateStr")
            
            when (newState) {
                BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_CONNECTED -> {
                    connectionTimeoutJob?.cancel()
                }
            }

            if (status != 0) {
                Log.e(TAG, "Client connection error status=$status newState=$newState. Aggressively resetting state.")
                try {
                    gatt.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing gatt in error callback", e)
                }
                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                }
                isDisconnecting = false
                connectedServerDevice = null
                _isReady.value = false
                negotiatedMtu = 23
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                return
            }

            when (newState) {
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Client STATE_DISCONNECTED received. Closing GATT.")
                    try {
                        gatt.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing gatt in callback", e)
                    }
                    if (bluetoothGatt == gatt) {
                        bluetoothGatt = null
                    }
                    isDisconnecting = false
                    connectedServerDevice = null
                    _isReady.value = false
                    negotiatedMtu = 23
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                    Log.i(TAG, "Client disconnected cleanly")
                }
                BluetoothProfile.STATE_CONNECTED -> {
                    isDisconnecting = false
                    connectedServerDevice = gatt.device
                    _connectionStatus.value = ConnectionStatus.SYNCHRONIZING
                    discoveryRetryCount = 0
                    Log.i(TAG, "Client BLE link established. Starting sequential handshake stabilization...")
                    
                    scope.launch {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        gatt.requestMtu(512)
                        delay(600)
                        Log.i(TAG, "Client starting service discovery after 600ms stabilization delay...")
                        gatt.discoverServices()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                Log.i(TAG, "Client MTU negotiated successfully to $mtu")
            } else {
                negotiatedMtu = 23
                Log.w(TAG, "Client MTU negotiation failed: status=$status. Falling back to default MTU.")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != 0) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(MESSAGE_CHARACTERISTIC_UUID)
            
            if (characteristic != null) {
                messageCharacteristic = characteristic
                gatt.setCharacteristicNotification(characteristic, true)
                Log.i(TAG, "Handshake Complete: Message pipe ready. Subscribing to notifications...")
                
                scope.launch {
                    delay(200)
                    val descriptor = characteristic.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        var writeSuccess = false
                        for (attempt in 1..3) {
                            writeSuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(descriptor)
                            }
                            Log.i(TAG, "CCCD descriptor write attempt $attempt: success=$writeSuccess")
                            if (writeSuccess) break
                            delay(300)
                        }
                    }
                }
            } else {
                if (discoveryRetryCount < 1) {
                    discoveryRetryCount++
                    Log.w(TAG, "BlueMesh service not found in discovery results. Retrying in 1s... (attempt $discoveryRetryCount)")
                    scope.launch {
                        delay(1000)
                        gatt.discoverServices()
                    }
                } else {
                    Log.e(TAG, "BlueMesh service not found after retry. Peer may not be advertising the service.")
                    showToast("Peer's chat service not found")
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val ready = (status == BluetoothGatt.GATT_SUCCESS)
                if (ready) {
                    Log.i(TAG, "Notification Subscription Confirmed. Connection fully ready.")
                } else {
                    Log.w(TAG, "Descriptor write callback failed: status=$status. Marking ready for one-way messaging.")
                }
                _isReady.value = true
                _connectionStatus.value = ConnectionStatus.CONNECTED
                
                val peer = _discoveredPeers.value.find { it.address == gatt.device.address }
                val peerUuid = peer?.uuid
                if (peerUuid != null) {
                    onPeerReadyCallback?.invoke(peerUuid)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == MESSAGE_CHARACTERISTIC_UUID) {
                @Suppress("DEPRECATION")
                val value = characteristic.value
                if (value != null) {
                    val messageText = parseAndDecompress(value)
                    if (messageText != null) {
                        Log.i(TAG, "Client received notification: $messageText")
                        _messages.tryEmit(ChatMessage(messageText, isFromMe = false, timestamp = System.currentTimeMillis()))
                    }
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
                }
            }
        }
    }

    private fun startScanTimeoutTimer() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(20000)
            if (_isScanning.value) {
                Log.d(TAG, "scanTimeoutTimer: 20 seconds elapsed, automatically stopping scan.")
                stopScanning()
            }
        }
    }

    fun hasPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun startScanning() {
        Log.d(TAG, "startScanning: Request received")
        val timeSinceLastScan = System.currentTimeMillis() - lastScanStartTime
        if (timeSinceLastScan < 3000) {
            Log.d(TAG, "startScanning: Ignored due to 3-second cooldown ($timeSinceLastScan ms since last scan started)")
            return
        }

        if (_isScanning.value) {
            Log.d(TAG, "startScanning: Scanner is already active. Restarting scan for Refresh.")
            stopScanning()
            _discoveredPeers.value = emptyList()
            if (bluetoothGattServer != null) {
                Log.d(TAG, "startScanning: Restarting GATT server on Refresh")
                stopGattServer()
                startGattServer()
            }
            scope.launch {
                delay(200)
                startScanning()
            }
            return
        }

        if (!hasPermissions()) {
            Log.e(TAG, "startScanning failed: Missing required runtime permissions")
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "startScanning failed: Bluetooth is disabled or unavailable")
            showToast("Please enable Bluetooth to scan for peers")
            return
        }

        if (!isLocationEnabled()) {
            Log.e(TAG, "startScanning failed: Location services are disabled")
            showToast("Please enable Location/GPS to scan for peers")
            return
        }

        _discoveredPeers.value = emptyList()
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(MESH_SERVICE_UUID_STR))).build()
        )

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isPowerSave = powerManager.isPowerSaveMode
        val scanMode = if (isPowerSave) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY
        Log.d(TAG, "startScanning: isPowerSaveMode=$isPowerSave, using scanMode=$scanMode")

        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            Log.d(TAG, "startScanning: Invoking system BLE scanner")
            scanner?.startScan(filters, settings, scanCallback)
            _isScanning.value = true
            lastScanStartTime = System.currentTimeMillis()
            startEvictionTimer()
            startScanTimeoutTimer()
            Log.i(TAG, "startScanning: System BLE scanner started")
        } catch (e: Exception) {
            Log.e(TAG, "startScanning: Exception starting BLE scan", e)
            _isScanning.value = false
            showToast("Failed to start scanning: ${e.localizedMessage}")
        }
    }

    fun stopScanning() {
        Log.d(TAG, "stopScanning: Request received")
        if (!hasPermissions() || !_isScanning.value) return
        try {
            scanner?.stopScan(scanCallback)
            _isScanning.value = false
            Log.i(TAG, "stopScanning: BLE Scanner stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "stopScanning: Exception stopping BLE scan", e)
        }
        evictionJob?.cancel()
        evictionJob = null
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
    }

    fun startAdvertising(displayName: String) {
        Log.d(TAG, "startAdvertising: Request received for name '$displayName'")
        lastDisplayName = displayName
        if (!hasPermissions()) {
            Log.e(TAG, "startAdvertising failed: Missing required runtime permissions")
            _isAdvertising.value = false
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "startAdvertising failed: Bluetooth is disabled or unavailable")
            showToast("Please enable Bluetooth to make your device discoverable")
            _isAdvertising.value = false
            return
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && !isLocationEnabled()) {
            Log.e(TAG, "startAdvertising failed: Location services are disabled")
            showToast("Please enable Location/GPS to make your device discoverable")
            _isAdvertising.value = false
            return
        }

        if (_isAdvertising.value) {
            Log.d(TAG, "startAdvertising: Advertising is already active")
            return
        }

        scope.launch {
            var advertiserObj = advertiser
            var retries = 3
            while (advertiserObj == null && retries > 0) {
                Log.d(TAG, "startAdvertising: advertiser is null, retrying fetch in 150ms... ($retries retries left)")
                delay(150)
                advertiserObj = advertiser
                retries--
            }

            if (advertiserObj == null) {
                Log.e(TAG, "startAdvertising failed: Hardware does not support BLE Advertising (advertiser is null)")
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
            val uuidBytes = uuidToBytes(userUuid)

            val truncatedName = if (displayName.length > 10) displayName.substring(0, 10) else displayName
            var nameBytes = truncatedName.toByteArray(Charsets.UTF_8)
            if (nameBytes.size > 10) {
                nameBytes = nameBytes.copyOfRange(0, 10)
            }

            val isPasscode = prefs.getBoolean("is_passcode_enabled", false)
            val passcodeFlag = if (isPasscode) 1.toByte() else 0.toByte()

            val manufacturerData = ByteArray(17 + nameBytes.size)
            System.arraycopy(uuidBytes, 0, manufacturerData, 0, 16)
            manufacturerData[16] = passcodeFlag
            System.arraycopy(nameBytes, 0, manufacturerData, 17, nameBytes.size)

            val data = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()

            val scanResponseData = AdvertiseData.Builder()
                .addManufacturerData(SupportMenu.USER_MASK, manufacturerData)
                .build()

            try {
                Log.d(TAG, "startAdvertising: Invoking system advertiser. Manufacturer ID: ${SupportMenu.USER_MASK}, total data length: ${manufacturerData.size}")
                advertiserObj.startAdvertising(settings, data, scanResponseData, advertiseCallback)
            } catch (e: Exception) {
                Log.e(TAG, "startAdvertising: Exception starting advertiser", e)
                _isAdvertising.value = false
                showToast("Failed to start advertising: ${e.localizedMessage}")
            }
        }
    }

    fun stopAdvertising() {
        Log.d(TAG, "stopAdvertising: Request received")
        if (!hasPermissions() || !_isAdvertising.value) return
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            _isAdvertising.value = false
            Log.i(TAG, "stopAdvertising: BLE Advertising stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "stopAdvertising: Exception stopping advertising", e)
        }
    }

    private val meshAdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "onStartSuccess: BLE mesh advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "onStartFailure: BLE mesh advertising failed with error code $errorCode")
        }
    }

    fun advertiseMeshMessage(messageId: Int, senderUuid: String, recipientUuid: String, messageText: String) {
        if (!hasPermissions()) {
            Log.e(TAG, "advertiseMeshMessage: Missing permissions")
            return
        }
        val advertiserObj = advertiser ?: return

        meshAdvertiseJob?.cancel()
        meshAdvertiseJob = scope.launch {
            val wasAdvertisingDiscovery = _isAdvertising.value
            if (wasAdvertisingDiscovery) {
                Log.d(TAG, "advertiseMeshMessage: Stopping peer discovery advertising temporarily")
                stopAdvertising()
                delay(150)
            }

            val senderHash = senderUuid.hashCode()
            val recipientHash = recipientUuid.hashCode()
            val messageBytes = messageText.toByteArray(Charsets.UTF_8)
            
            val maxTextBytes = 15
            val truncatedBytes = if (messageBytes.size > maxTextBytes) messageBytes.copyOfRange(0, maxTextBytes) else messageBytes

            val buffer = ByteBuffer.allocate(12 + truncatedBytes.size)
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(messageId)
            buffer.putInt(senderHash)
            buffer.putInt(recipientHash)
            buffer.put(truncatedBytes)

            val manufacturerSpecificData = buffer.array()

            val advertiseData = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(UUID.fromString(MESH_SERVICE_UUID_STR)))
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()

            val scanResponseData = AdvertiseData.Builder()
                .addManufacturerData(MESH_COMPANY_ID, manufacturerSpecificData)
                .build()

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build()

            try {
                Log.i(TAG, "advertiseMeshMessage: Starting BLE broadcast for 4 seconds. ID=$messageId, Text=$messageText")
                advertiserObj.startAdvertising(settings, advertiseData, scanResponseData, meshAdvertiseCallback)
                
                delay(4000) // 4 seconds blast window
                
                advertiserObj.stopAdvertising(meshAdvertiseCallback)
                Log.i(TAG, "advertiseMeshMessage: Stopped BLE broadcast")
            } catch (e: Exception) {
                Log.e(TAG, "advertiseMeshMessage: Error starting BLE advertising", e)
            }

            if (wasAdvertisingDiscovery && lastDisplayName.isNotEmpty()) {
                Log.d(TAG, "advertiseMeshMessage: Restarting peer discovery advertising")
                delay(150)
                startAdvertising(lastDisplayName)
            }
        }
    }

    private fun startGattServer() {
        if (bluetoothGattServer != null) {
            Log.d(TAG, "GATT Server already running, skipping.")
            return
        }
        try {
            val server = bluetoothManager.openGattServer(context, gattServerCallback)
            if (server == null) {
                Log.e(TAG, "openGattServer returned null — cannot start server")
                return
            }
            bluetoothGattServer = server

            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val characteristic = BluetoothGattCharacteristic(
                MESSAGE_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
            )

            val cccdDescriptor = BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
            )
            characteristic.addDescriptor(cccdDescriptor)
            service.addCharacteristic(characteristic)

            val added = server.addService(service)
            if (added) {
                Log.i(TAG, "GATT Server started and service added successfully")
            } else {
                Log.e(TAG, "GATT Server addService() returned false — service was NOT registered")
                showToast("Failed to register Bluetooth service")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting GATT Server", e)
        }
    }

    private fun stopGattServer() {
        try {
            bluetoothGattServer?.close()
            bluetoothGattServer = null
            Log.i(TAG, "GATT Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping GATT Server", e)
        }
    }

    fun connectToPeer(device: BluetoothDevice) {
        connectToPeerInternal(device, false)
    }

    private fun refreshDeviceCache(gatt: BluetoothGatt): Boolean {
        return try {
            val refreshMethod = gatt.javaClass.getMethod("refresh")
            val success = refreshMethod.invoke(gatt) as Boolean
            Log.d(TAG, "refreshDeviceCache (GATT cache clear) returned: $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh device cache via reflection", e)
            false
        }
    }

    private fun connectToPeerInternal(device: BluetoothDevice, isRetry: Boolean) {
        if (hasPermissions()) {
            connectionTimeoutJob?.cancel()
            
            val oldGatt = bluetoothGatt
            if (oldGatt != null) {
                Log.d(TAG, "connectToPeerInternal: Thoroughly closing existing GATT connection before new connect")
                try {
                    oldGatt.disconnect()
                    oldGatt.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing old GATT client", e)
                }
                bluetoothGatt = null
                isDisconnecting = false
            }

            if (device.bondState != BluetoothDevice.BOND_NONE) {
                Log.d(TAG, "connectToPeerInternal: Device ${device.address} has bond (state: ${device.bondState}). Removing bond to force pairless communication.")
                try {
                    val method = device.javaClass.getMethod("removeBond")
                    val result = method.invoke(device) as Boolean
                    Log.d(TAG, "removeBond result: $result")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove bond", e)
                }
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
                if (gatt != null) {
                    refreshDeviceCache(gatt)
                }
                
                scope.launch {
                    delay(500)
                    if (_connectionStatus.value == ConnectionStatus.CONNECTING) {
                        Log.d(TAG, "connectToPeerInternal: Connection still pending after 500ms, forcing gatt.connect()")
                        gatt?.connect()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to GATT", e)
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                return
            }

            connectionTimeoutJob = scope.launch {
                delay(5000)
                if (_connectionStatus.value == ConnectionStatus.CONNECTING) {
                    Log.w(TAG, "connectToPeerInternal: Connection timed out (5s) for ${device.address}")
                    if (!isRetry) {
                        Log.i(TAG, "connectToPeerInternal: Retrying connection in 300ms...")
                        disconnect()
                        delay(300)
                        connectToPeerInternal(device, true)
                    } else {
                        Log.e(TAG, "connectToPeerInternal: Retry connection failed. Reverting to DISCONNECTED.")
                        disconnect()
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                        showToast("Connection timed out. Please try again.")
                    }
                }
            }
        }
    }

    fun disconnect() {
        connectionTimeoutJob?.cancel()
        val gatt = bluetoothGatt
        if (gatt == null) {
            Log.d(TAG, "disconnect: already disconnected (gatt is null)")
            return
        }
        if (isDisconnecting) {
            Log.d(TAG, "disconnect: disconnect already in progress")
            return
        }
        isDisconnecting = true
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _isReady.value = false
        connectedServerDevice = null
        messageCharacteristic = null
        Log.d(TAG, "disconnect: Initiating GATT disconnect")
        try {
            gatt.disconnect()
            scope.launch {
                delay(100)
                if (isDisconnecting) {
                    try {
                        gatt.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing gatt in disconnect delay", e)
                    }
                    bluetoothGatt = null
                    isDisconnecting = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calling gatt.disconnect()", e)
            try {
                gatt.close()
            } catch (ex: Exception) {
                Log.e(TAG, "Error forcing gatt.close()", ex)
            }
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
        val bb = ByteBuffer.wrap(ByteArray(16))
        bb.putLong(uuid.mostSignificantBits)
        bb.putLong(uuid.leastSignificantBits)
        return bb.array()
    }

    private fun bytesToUuid(bytes: ByteArray): UUID {
        val byteBuffer = ByteBuffer.wrap(bytes)
        val high = byteBuffer.long
        val low = byteBuffer.long
        return UUID(high, low)
    }

    private fun parseAndDecompress(value: ByteArray): String? {
        if (value.size < 2) {
            Log.w(TAG, "parseAndDecompress: Packet too short (${value.size} bytes)")
            return null
        }
        val packetType = value[0]
        val compressionFlag = value[1]
        if (packetType != 1.toByte()) {
            Log.w(TAG, "parseAndDecompress: Unknown packet type ${packetType.toInt()}")
            return null
        }
        return try {
            if (compressionFlag == 1.toByte()) {
                val compressedData = value.copyOfRange(2, value.size)
                val factory = LZ4Factory.fastestInstance()
                val decompressor = factory.safeDecompressor()
                val tempDest = ByteArray(32768)
                val decompressedLength = decompressor.decompress(
                    compressedData, 0, compressedData.size,
                    tempDest, 0, tempDest.size
                )
                String(tempDest, 0, decompressedLength, Charsets.UTF_8)
            } else {
                String(value, 2, value.size - 2, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseAndDecompress failed to decompress/parse", e)
            null
        }
    }

    fun sendMessage(text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var payload: ByteArray
        try {
            val fastCompressor = LZ4Factory.fastestInstance().fastCompressor()
            val maxCompressedLength = fastCompressor.maxCompressedLength(bytes.size)
            val compressedBuffer = ByteArray(maxCompressedLength)
            val compressedSize = fastCompressor.compress(bytes, 0, bytes.size, compressedBuffer, 0, maxCompressedLength)
            
            if (compressedSize < bytes.size) {
                val compressedBytes = compressedBuffer.copyOfRange(0, compressedSize)
                payload = ByteArray(compressedSize + 2)
                payload[0] = 1
                payload[1] = 1
                System.arraycopy(compressedBytes, 0, payload, 2, compressedSize)
                Log.d(TAG, "sendMessage: Compressed message from ${bytes.size} to $compressedSize bytes")
            } else {
                payload = ByteArray(bytes.size + 2)
                payload[0] = 1
                payload[1] = 0
                System.arraycopy(bytes, 0, payload, 2, bytes.size)
                Log.d(TAG, "sendMessage: Sending uncompressed message (${bytes.size} bytes)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress message, falling back to uncompressed", e)
            payload = ByteArray(bytes.size + 2)
            payload[0] = 1
            payload[1] = 0
            System.arraycopy(bytes, 0, payload, 2, bytes.size)
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
                    gattClient.writeCharacteristic(
                        charClient,
                        chunk,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ) == BluetoothStatusCodes.SUCCESS
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
                    Log.e(TAG, "Failed to write chunk of size ${chunk.size}")
                    break
                }
                if (chunks.size > 1) {
                    try {
                        Thread.sleep(50)
                    } catch (e: InterruptedException) {
                        Log.e(TAG, "Chunk write sleep interrupted", e)
                    }
                }
            }
            if (success) {
                _messages.tryEmit(ChatMessage(text, isFromMe = true, timestamp = System.currentTimeMillis()))
                return true
            }
        }

        // Server notify
        val server = bluetoothGattServer
        val activeClient = connectedClientDevice
        if (server != null && activeClient != null) {
            val service = server.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(MESSAGE_CHARACTERISTIC_UUID)
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
                        Log.e(TAG, "Failed to notify chunk of size ${chunk.size}")
                        break
                    }
                    if (chunks.size > 1) {
                        try {
                            Thread.sleep(50)
                        } catch (e: InterruptedException) {
                            Log.e(TAG, "Chunk notify sleep interrupted", e)
                        }
                    }
                }
                if (success) {
                    _messages.tryEmit(ChatMessage(text, isFromMe = true, timestamp = System.currentTimeMillis()))
                    return true
                }
            }
        }

        Log.e(TAG, "No active connection or characteristic to send message")
        return false
    }

    fun cleanUp() {
        stopScanning()
        stopAdvertising()
        disconnect()
        stopGattServer()
    }
}
