package com.example.bluemesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.bluemesh.data.models.ConnectionStatus
import kotlinx.coroutines.*

@SuppressLint("MissingPermission")
class GattClientManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val tracker: ConnectionTracker,
    private val messageProcessor: MessageProcessor
) {
    private var connectionTimeoutJob: Job? = null
    private var clientSyncTimeoutJob: Job? = null
    private var mtuFallbackJob: Job? = null
    @Volatile var isCurrentConnectionRetry = false
    private var discoveryRetryCount = 0
    var pendingConnectUuid: String? = null

    var onPeerReadyCallback: ((String, BluetoothDevice) -> Unit)? = null
    var onPeerDisconnectedCallback: ((String) -> Unit)? = null

    val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            try {
                connectionTimeoutJob?.cancel()
                if (status != 0 || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    pendingConnectUuid = null
                    cancelClientSyncTimeout()
                    val disconnectedAddress = gatt.device.address
                    tracker.deviceMtus.remove(disconnectedAddress)
                    val peerWasAttemptingConnection = (tracker.getConnectionStatusForAddress(disconnectedAddress) == ConnectionStatus.CONNECTING ||
                            tracker.getConnectionStatusForAddress(disconnectedAddress) == ConnectionStatus.SYNCHRONIZING)
                    if (status != 0 && peerWasAttemptingConnection) {
                        Log.w(Constants.TAG, "GATT connection failure status $status for $disconnectedAddress. Healing and retrying.")
                        refreshDeviceCache(gatt)
                    }

                    try { gatt.close() } catch (_: Exception) {}
                    val isStillServerConnected = tracker.connectedClients.any { it.address == disconnectedAddress }
                    if (!isStillServerConnected) {
                        tracker.updateDeviceStatus(disconnectedAddress, ConnectionStatus.DISCONNECTED)
                    }
                    tracker.clientConnections.remove(disconnectedAddress)
                    val peerUuid = tracker.getPeerList().find { it.address == disconnectedAddress }?.uuid
                        ?: tracker.uuidToServerAddress.entries.firstOrNull { it.value == disconnectedAddress }?.key
                    if (peerUuid != null) {
                        onPeerDisconnectedCallback?.invoke(peerUuid)
                    }
                    onDisconnected()
                    tracker.currentWriteDeferreds.remove(disconnectedAddress)?.complete(false)
                    if (status != 0 && peerWasAttemptingConnection && !isCurrentConnectionRetry) {
                        scope.launch {
                            delay(500)
                            Log.d(Constants.TAG, "Triggering connection retry for $disconnectedAddress")
                            connectToPeerInternal(gatt.device, true)
                        }
                    }
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    val gattAddress = gatt.device.address
                    if (tracker.clientConnections.size >= Constants.MAX_CLIENT_CONNECTIONS) {
                        Log.w(Constants.TAG, "Client connection limit reached (${Constants.MAX_CLIENT_CONNECTIONS})")
                        val oldest = tracker.clientConnections.entries.minByOrNull { it.value.connectedAt }
                        oldest?.let { (addr, conn) ->
                            Log.d(Constants.TAG, "Evicting oldest client connection: $addr")
                            tracker.currentWriteDeferreds.remove(addr)?.complete(false)
                            try { conn.gatt.disconnect(); conn.gatt.close() } catch (_: Exception) {}
                            tracker.clientConnections.remove(addr)
                        }
                    }
                    tracker.clientConnections[gattAddress] = ClientConnection(gatt = gatt, deviceAddress = gattAddress)
                    tracker.updateDeviceStatus(gattAddress, ConnectionStatus.SYNCHRONIZING)
                    discoveryRetryCount = 0
                    startClientSyncTimeout(gatt)
                    if (pendingConnectUuid != null) {
                        tracker.uuidToServerAddress[pendingConnectUuid!!] = gattAddress
                    } else {
                        val fallbackPeer = tracker.discoveredPeers.value.find { it.address == gattAddress || it.device?.address == gattAddress }
                        if (fallbackPeer != null && fallbackPeer.uuid.isNotEmpty()) {
                            tracker.uuidToServerAddress[fallbackPeer.uuid] = gattAddress
                        }
                    }
                    pendingConnectUuid = null
                    scope.launch {
                        try {
                            delay(300)
                            gatt.requestMtu(512)
                            mtuFallbackJob = scope.launch {
                                delay(2000)
                                Log.d(Constants.TAG, "MTU callback fallback — starting service discovery directly")
                                gatt.discoverServices()
                            }
                        } catch (e: Exception) {
                            Log.e(Constants.TAG, "Error requesting connection configuration", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Error in gattCallback.onConnectionStateChange", e)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            try {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    tracker.deviceMtus[gatt.device.address] = mtu
                }
                mtuFallbackJob?.cancel()
                mtuFallbackJob = null
                gatt.discoverServices()
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Error in onMtuChanged callback", e)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            try {
                if (status != 0) {
                    if (discoveryRetryCount < Constants.DISCOVERY_RETRIES) {
                        discoveryRetryCount++
                        scope.launch {
                            try {
                                Log.w(Constants.TAG, "onServicesDiscovered error status $status. Refreshing cache and retrying.")
                                refreshDeviceCache(gatt)
                                delay(1000)
                                gatt.discoverServices()
                            } catch (e: Exception) {
                                Log.e(Constants.TAG, "Error retrying discoverServices", e)
                            }
                        }
                    }
                    return
                }
                val characteristic = gatt.getService(Constants.SERVICE_UUID)?.getCharacteristic(Constants.MESSAGE_CHARACTERISTIC_UUID)
                if (characteristic != null) {
                    val addr = gatt.device.address
                    tracker.clientConnections.compute(addr) { _, existing ->
                        if (existing != null) {
                            existing.characteristic = characteristic
                            existing
                        } else ClientConnection(gatt = gatt, characteristic = characteristic, deviceAddress = addr)
                    }
                    try {
                        gatt.setCharacteristicNotification(characteristic, true)
                    } catch (e: Exception) {
                        Log.e(Constants.TAG, "setCharacteristicNotification failed", e)
                    }
                    scope.launch {
                        try {
                            delay(200)
                            characteristic.getDescriptor(Constants.CCCD_UUID)?.let { descriptor ->
                                for (attempt in 1..Constants.CCCD_WRITE_RETRIES) {
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
                            Log.e(Constants.TAG, "Error writing CCCD descriptor", e)
                        }
                    }
                } else {
                    if (discoveryRetryCount < Constants.DISCOVERY_RETRIES) {
                        discoveryRetryCount++
                        scope.launch {
                            try {
                                Log.w(Constants.TAG, "Chat service not found. Refreshing cache and retrying.")
                                refreshDeviceCache(gatt)
                                delay(1000)
                                gatt.discoverServices()
                            } catch (e: Exception) {
                                Log.e(Constants.TAG, "Error retrying discoverServices", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Error in onServicesDiscovered", e)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            try {
                if (descriptor.uuid == Constants.CCCD_UUID) {
                    val addr = gatt.device.address
                    tracker.clientConnections.compute(addr) { _, existing ->
                        if (existing != null) {
                            existing.cccdReady = true
                            existing
                        } else ClientConnection(gatt = gatt, deviceAddress = addr, cccdReady = true)
                    }
                    tracker.cccdStates[addr] = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    tracker.updateDeviceStatus(addr, ConnectionStatus.CONNECTED)
                    val peerUuid = tracker.getPeerList().find { it.address == addr }?.uuid ?: ""
                    onPeerReadyCallback?.invoke(peerUuid, gatt.device)
                }
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Error in onDescriptorWrite", e)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            try {
                tracker.currentWriteDeferreds.remove(gatt.device.address)?.complete(status == BluetoothGatt.GATT_SUCCESS)
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Error in onCharacteristicWrite", e)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            try {
                val isMatch = characteristic.uuid == Constants.MESSAGE_CHARACTERISTIC_UUID
                Log.d(Constants.TAG, "CLIENT_RX changed from ${gatt.device.address} isMatch=$isMatch")
                if (isMatch) {
                    @Suppress("DEPRECATION")
                    val value = characteristic.value
                    if (value != null) {
                        messageProcessor.handleIncomingValue(gatt.device, value)
                    } else {
                        Log.w(Constants.TAG, "CLIENT_RX changed with null value")
                    }
                }
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Error in onCharacteristicChanged", e)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            try {
                val isMatch = characteristic.uuid == Constants.MESSAGE_CHARACTERISTIC_UUID
                Log.d(Constants.TAG, "CLIENT_RX changed(Tiramisu) from ${gatt.device.address} isMatch=$isMatch size=${value.size}")
                if (isMatch) {
                    messageProcessor.handleIncomingValue(gatt.device, value)
                }
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Error in onCharacteristicChanged (Tiramisu)", e)
            }
        }
    }

    fun connectToPeer(device: BluetoothDevice) {
        connectToPeerInternal(device, false)
    }

    fun connectToPeerInternal(device: BluetoothDevice, isRetry: Boolean) {
        connectionTimeoutJob?.cancel()
        isCurrentConnectionRetry = isRetry
        val isServerConnected = tracker.isAlreadyServerConnected(device)
        if (isServerConnected) {
            tracker.updateDeviceStatus(device.address, ConnectionStatus.CONNECTED)
            val deviceUuid = tracker.getPeerList().find { it.address == device.address || it.device?.address == device.address }?.uuid
                ?: tracker.uuidToServerAddress.entries.firstOrNull { it.value == device.address }?.key
            val trackedDevice = tracker.connectedClients.find { it.address == device.address }
                ?: deviceUuid?.let { uuid ->
                    val mappedAddr = tracker.uuidToServerAddress.entries.firstOrNull { com.example.bluemesh.utils.uuidsMatch(it.key, uuid) }?.value
                    if (mappedAddr != null) tracker.connectedClients.find { it.address == mappedAddr } else null
                }
            if (trackedDevice != null && tracker.clientConnections.containsKey(device.address)) {
                Log.d(Constants.TAG, "Peer ${device.address} fully connected (server + client). Skipping client connect.")
                scope.launch {
                    delay(300)
                    val peerUuid = tracker.getPeerList().find { it.address == trackedDevice.address }?.uuid
                        ?: tracker.uuidToServerAddress.entries.firstOrNull { it.value == trackedDevice.address }?.key ?: ""
                    if (peerUuid.isNotEmpty()) {
                        onPeerReadyCallback?.invoke(peerUuid, trackedDevice)
                    }
                }
                return
            }
        }
        if (tracker.clientConnections.containsKey(device.address)) {
            Log.d(Constants.TAG, "Already client-connected to ${device.address}. Skipping reconnect.")
            return
        }
        if (tracker.clientConnections.size >= Constants.MAX_CLIENT_CONNECTIONS ||
            (tracker.clientConnections.size + tracker.connectedClients.size) >= Constants.MAX_CONNECTIONS_OVERALL) {
            Log.w(Constants.TAG, "Connection limit reached, cannot connect to ${device.address}")
            return
        }
        if (device.bondState != BluetoothDevice.BOND_NONE) {
            try { device.javaClass.getMethod("removeBond").invoke(device) } catch (_: Exception) {}
        }
        tracker.updateDeviceStatus(device.address, ConnectionStatus.CONNECTING)
        discoveryRetryCount = 0
        tracker.deviceMtus.remove(device.address)
        pendingConnectUuid = tracker.getPeerList().find { it.address == device.address }?.uuid
            ?: tracker.getPeerList().find { it.device?.address == device.address }?.uuid
        try {
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
            if (gatt != null) {
                tracker.clientConnections[device.address] = ClientConnection(gatt = gatt, deviceAddress = device.address)
            }
        } catch (e: Exception) {
            tracker.clientConnections.remove(device.address)
            tracker.updateDeviceStatus(device.address, ConnectionStatus.DISCONNECTED)
            return
        }
        connectionTimeoutJob = scope.launch {
            try {
                delay(Constants.CONNECTION_TIMEOUT_MS)
                val conn = tracker.clientConnections[device.address]
                if (conn != null && tracker.getConnectionStatusForAddress(device.address) == ConnectionStatus.CONNECTING) {
                    if (!isRetry) {
                        disconnectClient(device.address)
                        delay(300)
                        connectToPeerInternal(device, true)
                    } else {
                        disconnectClient(device.address)
                        tracker.updateDeviceStatus(device.address, ConnectionStatus.DISCONNECTED)
                    }
                }
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Error in connectionTimeoutJob", e)
            }
        }
    }

    fun disconnectClient(deviceAddress: String) {
        tracker.clientConnections.remove(deviceAddress)?.let { conn ->
            conn.sessionScope.cancel()
            tracker.currentWriteDeferreds.remove(deviceAddress)?.complete(false)
            tracker.currentNotificationDeferreds.remove(deviceAddress)?.complete(false)
            val isStillServerConnected = tracker.connectedClients.any { it.address == deviceAddress }
            if (!isStillServerConnected) {
                tracker.updateDeviceStatus(deviceAddress, ConnectionStatus.DISCONNECTED)
            }
            try {
                conn.gatt.disconnect()
                conn.gatt.close()
            } catch (_: Exception) {}
        }
        if (tracker.clientConnections.isEmpty() && tracker.connectedClients.isEmpty()) {
            onDisconnected()
        }
    }

    fun resetClientConnection() {
        connectionTimeoutJob?.cancel()
        mtuFallbackJob?.cancel()
        mtuFallbackJob = null
        cancelClientSyncTimeout()
        val allAddresses = tracker.clientConnections.keys.toList()
        allAddresses.forEach { addr -> disconnectClient(addr) }
        tracker.currentWriteDeferreds.values.forEach { it.complete(false) }
        tracker.currentWriteDeferreds.clear()
        tracker.currentNotificationDeferreds.values.forEach { it.complete(false) }
        tracker.currentNotificationDeferreds.clear()
        tracker.clientConnections.clear()
        onDisconnected()
    }

    private fun startClientSyncTimeout(gatt: BluetoothGatt) {
        clientSyncTimeoutJob?.cancel()
        clientSyncTimeoutJob = scope.launch {
            try {
                delay(Constants.CLIENT_SYNC_TIMEOUT_MS)
                if (tracker.getConnectionStatusForAddress(gatt.device.address) == ConnectionStatus.SYNCHRONIZING) {
                    Log.w(Constants.TAG, "Client synchronization timeout for ${gatt.device.address}. Healing connection.")
                    refreshDeviceCache(gatt)
                    disconnectClient(gatt.device.address)
                    delay(300)
                    if (!isCurrentConnectionRetry) {
                        connectToPeerInternal(gatt.device, true)
                    } else {
                        tracker.updateDeviceStatus(gatt.device.address, ConnectionStatus.DISCONNECTED)
                    }
                }
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Error in clientSyncTimeoutJob", e)
            }
        }
    }

    private fun cancelClientSyncTimeout() {
        clientSyncTimeoutJob?.cancel()
        clientSyncTimeoutJob = null
    }

    private fun refreshDeviceCache(gatt: BluetoothGatt) {
        try {
            val refreshMethod = gatt.javaClass.getMethod("refresh")
            refreshMethod.invoke(gatt)
        } catch (_: Exception) {}
    }

    private var _onDisconnected: (() -> Unit)? = null
    fun onDisconnectedCallback(callback: () -> Unit) {
        _onDisconnected = callback
    }
    private fun onDisconnected() {
        _onDisconnected?.invoke()
    }

    fun cancelAll() {
        connectionTimeoutJob?.cancel()
        mtuFallbackJob?.cancel()
        mtuFallbackJob = null
        cancelClientSyncTimeout()
        connectionTimeoutJob = null
    }
}
