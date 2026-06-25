package com.example.bluemesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.example.bluemesh.data.models.ConnectionStatus
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class GattServerManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val tracker: ConnectionTracker,
    private val messageProcessor: MessageProcessor
) {
    var bluetoothGattServer: BluetoothGattServer? = null
    private val serverCccdTimeoutJobs = ConcurrentHashMap<String, Job>()

    var onPeerReadyCallback: ((String, BluetoothDevice) -> Unit)? = null
    var onPeerDisconnectedCallback: ((String) -> Unit)? = null

    val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            try {
                if (status != 0 || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    handleServerDisconnect(device)
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (device.bondState != BluetoothDevice.BOND_NONE) {
                        try { device.javaClass.getMethod("removeBond").invoke(device) } catch (_: Exception) {}
                    }
                    tracker.connectedClients.add(device)
                    tracker.updateDeviceStatus(device.address, ConnectionStatus.SYNCHRONIZING)
                    startServerCccdTimeout(device)
                }
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Error in gattServerCallback.onConnectionStateChange", e)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            try {
                val charUuid = characteristic.uuid
                val isMatch = charUuid == Constants.MESSAGE_CHARACTERISTIC_UUID
                val hasValue = value != null
                Log.d(Constants.TAG, "SERVER_RX writeRequest from ${device.address} charUuid=$charUuid isMatch=$isMatch hasValue=$hasValue valueSize=${value?.size ?: -1}")
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
                if (isMatch && hasValue) {
                    ensureConnectedClient(device)
                    messageProcessor.handleIncomingValue(device, value!!)
                }
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Error in gattServerCallback.onCharacteristicWriteRequest", e)
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            try {
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, byteArrayOf())
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Error in gattServerCallback.onCharacteristicReadRequest", e)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            try {
                if (descriptor.uuid == Constants.CCCD_UUID && value != null) {
                    ensureConnectedClient(device)
                    tracker.cccdStates[device.address] = value
                    val isNotificationEnabled = value.isNotEmpty() && (value[0].toInt() and 0x01 != 0)
                    if (isNotificationEnabled) {
                        cancelServerCccdTimeout(device.address)
                        tracker.deviceConnectionStatuses[device.address] = ConnectionStatus.CONNECTED
                        tracker.updateDeviceStatus(device.address, ConnectionStatus.CONNECTED)
                        val peerUuid = tracker.getPeerList().find { it.address == device.address }?.uuid ?: ""
                        onPeerReadyCallback?.invoke(peerUuid, device)
                    }
                }
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Error in gattServerCallback.onDescriptorWriteRequest", e)
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            try {
                val value = if (descriptor.uuid == Constants.CCCD_UUID)
                    tracker.cccdStates[device.address] ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                else byteArrayOf()
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Error in gattServerCallback.onDescriptorReadRequest", e)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            try {
                tracker.deviceMtus[device.address] = mtu
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Error in gattServerCallback.onMtuChanged", e)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            try {
                tracker.currentNotificationDeferreds.remove(device.address)?.complete(status == BluetoothGatt.GATT_SUCCESS)
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Error in gattServerCallback.onNotificationSent", e)
            }
        }
    }

    fun startGattServer(): Boolean {
        if (bluetoothGattServer != null) return true
        try {
            val freshManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val server = freshManager.openGattServer(context, gattServerCallback)
            if (server == null) {
                Log.w(Constants.TAG, "openGattServer returned null")
                return false
            }
            bluetoothGattServer = server
            val characteristic = BluetoothGattCharacteristic(
                Constants.MESSAGE_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
            ).apply {
                addDescriptor(BluetoothGattDescriptor(Constants.CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ))
            }
            val service = BluetoothGattService(Constants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(characteristic)
            }
            val addServiceResult = server.addService(service)
            Log.d(Constants.TAG, "SERVER addService=$addServiceResult")
            if (!addServiceResult) {
                Log.w(Constants.TAG, "SERVER addService FAILED — onCharacteristicWriteRequest will never fire")
            }
            return true
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Error starting GATT Server", e)
            return false
        }
    }

    fun stopGattServer() {
        try {
            bluetoothGattServer?.close()
        } catch (_: Exception) {}
        bluetoothGattServer = null
        tracker.connectedClients.clear()
        tracker.cccdStates.clear()
        tracker.clientConnections.clear()
    }

    fun handleServerDisconnect(device: BluetoothDevice) {
        try {
            cancelServerCccdTimeout(device.address)
            tracker.connectedClients.remove(device)
            tracker.deviceMtus.remove(device.address)
            tracker.cccdStates.remove(device.address)
            tracker.currentNotificationDeferreds.remove(device.address)?.complete(false)
            tracker.currentWriteDeferreds.remove(device.address)?.complete(false)
            tracker.clientConnections.remove(device.address)?.let { conn ->
                try {
                    conn.gatt.disconnect()
                    conn.gatt.close()
                } catch (_: Exception) {}
            }
            tracker.updateDeviceStatus(device.address, ConnectionStatus.DISCONNECTED)
            val peerUuid = tracker.getPeerList().find { it.address == device.address }?.uuid
            if (peerUuid != null) {
                onPeerDisconnectedCallback?.invoke(peerUuid)
                tracker.uuidToServerAddress.keys.toList().forEach { key ->
                    if (com.example.bluemesh.utils.uuidsMatch(key, peerUuid)) {
                        tracker.uuidToServerAddress.remove(key)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Error in handleServerDisconnect", e)
        }
    }

    private fun startServerCccdTimeout(device: BluetoothDevice) {
        serverCccdTimeoutJobs[device.address]?.cancel()
        serverCccdTimeoutJobs[device.address] = scope.launch {
            delay(Constants.SERVER_CCCD_TIMEOUT_MS)
            if (tracker.connectedClients.contains(device) && tracker.cccdStates[device.address] == null) {
                Log.w(Constants.TAG, "Server CCCD write timeout for ${device.address}. Disconnecting.")
                try {
                    bluetoothGattServer?.cancelConnection(device)
                } catch (e: Exception) {
                    Log.e(Constants.TAG, "Error cancelling connection on CCCD timeout", e)
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

    fun disconnectServerClient(address: String) {
        val device = tracker.connectedClients.find { it.address == address }
        if (device != null) {
            cancelServerCccdTimeout(address)
            try {
                bluetoothGattServer?.cancelConnection(device)
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Error cancelling server connection for $address", e)
            }
            handleServerDisconnect(device)
            Log.d(Constants.TAG, "disconnectServerClient removed $address")
        } else {
            // Device not tracked — try to find and force-disconnect via BLE manager
            Log.d(Constants.TAG, "disconnectServerClient: $address not in connectedClients, trying direct cancel")
            cancelServerCccdTimeout(address)
            try {
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
                val connectedDevices = manager.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT_SERVER) ?: emptyList()
                val bleDevice = connectedDevices.find { it.address == address }
                if (bleDevice != null) {
                    bluetoothGattServer?.cancelConnection(bleDevice)
                    Log.d(Constants.TAG, "disconnectServerClient force-cancelled $address")
                } else {
                    Log.d(Constants.TAG, "disconnectServerClient: $address not found in any BLE connection list")
                }
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Error force-disconnecting $address", e)
            }
        }
    }

    fun ensureConnectedClient(device: BluetoothDevice) {
        if (!tracker.connectedClients.any { it.address == device.address }) {
            tracker.connectedClients.add(device)
            Log.d(Constants.TAG, "ensureConnectedClient re-added ${device.address}")
            tracker.updateDeviceStatus(device.address, ConnectionStatus.CONNECTED)
        }
    }
}
