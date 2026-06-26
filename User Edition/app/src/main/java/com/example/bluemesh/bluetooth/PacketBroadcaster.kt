package com.example.bluemesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import android.util.Log
import com.example.bluemesh.data.models.ChatMessage
import net.jpountz.lz4.LZ4Factory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("MissingPermission")
class PacketBroadcaster(
    private val scope: CoroutineScope,
    private val tracker: ConnectionTracker,
    private val gattServerManager: GattServerManager,
    private val gattClientManager: GattClientManager
) {
    private val gattWriteMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val ackMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private fun getWriteMutex(address: String): Mutex = gattWriteMutexes.getOrPut(address) { Mutex() }
    private fun getAckMutex(address: String): Mutex = ackMutexes.getOrPut(address) { Mutex() }

    suspend fun sendMessageSuspend(bytes: ByteArray, targetDevice: BluetoothDevice? = null): Boolean {
        val addr = targetDevice?.address ?: return false
        return getWriteMutex(addr).withLock {
        try {
            if (targetDevice == null) {
                Log.e(Constants.TAG, "sendMessageSuspend: targetDevice is null, cannot send")
                return@withLock false
            }

            val actualSendTime = System.currentTimeMillis()
            var originalCreationTime: Long = 0
            val sendBytes = bytes.copyOf()
            if (sendBytes.size >= 14 && sendBytes[0] == 1.toByte() && (sendBytes[1] == 2.toByte() || sendBytes[1] == 3.toByte())) {
                originalCreationTime = ByteBuffer.wrap(sendBytes).order(ByteOrder.BIG_ENDIAN).getLong(6)
                ByteBuffer.wrap(sendBytes).order(ByteOrder.BIG_ENDIAN).putLong(6, actualSendTime)
            } else if (sendBytes.size >= 8) {
                originalCreationTime = ByteBuffer.wrap(sendBytes).order(ByteOrder.BIG_ENDIAN).getLong(0)
                ByteBuffer.wrap(sendBytes).order(ByteOrder.BIG_ENDIAN).putLong(0, actualSendTime)
            }

            var payload: ByteArray
            try {
                val fastCompressor = LZ4Factory.fastestInstance().fastCompressor()
                val maxCompressedLength = fastCompressor.maxCompressedLength(sendBytes.size)
                val compressedBuffer = ByteArray(maxCompressedLength)
                val compressedSize = fastCompressor.compress(sendBytes, 0, sendBytes.size, compressedBuffer, 0, maxCompressedLength)
                if (compressedSize < sendBytes.size) {
                    payload = ByteArray(compressedSize + 2).apply {
                        this[0] = 1
                        this[1] = 1
                        System.arraycopy(compressedBuffer, 0, this, 2, compressedSize)
                    }
                } else {
                    payload = ByteArray(sendBytes.size + 2).apply {
                        this[0] = 1
                        this[1] = 0
                        System.arraycopy(sendBytes, 0, this, 2, sendBytes.size)
                    }
                }
            } catch (e: Exception) {
                payload = ByteArray(sendBytes.size + 2).apply {
                    this[0] = 1
                    this[1] = 0
                    System.arraycopy(sendBytes, 0, this, 2, sendBytes.size)
                }
            }

            val targetAddress = targetDevice.address
            val deviceMtu = tracker.deviceMtus[targetAddress] ?: 23
            val maxPayloadSize = java.lang.Math.min(deviceMtu - 6, 509)
            val rawChunks = payload.toList().chunked(maxPayloadSize).map { it.toByteArray() }
            val totalChunks = rawChunks.size
            val chunks = rawChunks.mapIndexed { index, data ->
                ByteArray(data.size + 3).apply {
                    this[0] = 1
                    this[1] = index.toByte()
                    this[2] = totalChunks.toByte()
                    System.arraycopy(data, 0, this, 3, data.size)
                }
            }

            val wireWriteTime = System.currentTimeMillis()
            if (tracker.actualSendTimes.size > 500) {
                val sortedKeys = tracker.actualSendTimes.keys.toList()
                for (i in 0 until sortedKeys.size - 200) {
                    tracker.actualSendTimes.remove(sortedKeys[i])
                }
            }
            tracker.actualSendTimes[actualSendTime] = wireWriteTime
            if (tracker.otaToCreationTime.size > 500) {
                val sortedKeys = tracker.otaToCreationTime.keys.toList()
                for (i in 0 until sortedKeys.size - 200) {
                    tracker.otaToCreationTime.remove(sortedKeys[i])
                }
            }
            if (originalCreationTime != 0L) {
                tracker.otaToCreationTime[actualSendTime] = originalCreationTime
            }

            val success = sendChunksInternal(chunks, targetDevice)
            if (success) {
                delay(30)
                val text = if (sendBytes.size >= 14 && sendBytes[0] == 1.toByte() && (sendBytes[1] == 2.toByte() || sendBytes[1] == 3.toByte())) {
                    if (sendBytes[1] == 2.toByte()) "[Encrypted Message]"
                    else String(sendBytes, 14, sendBytes.size - 14, Charsets.UTF_8)
                } else {
                    MessageProcessor.decodePayload(sendBytes)?.second ?: String(sendBytes, Charsets.UTF_8)
                }
                tracker.tryEmitMessage(ChatMessage(text, isFromMe = true, timestamp = System.currentTimeMillis()))
            }
            return@withLock success
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Exception in sendMessageSuspend", e)
            false
        }
        }
    }

    suspend fun sendAck(timestamp: Long, targetDevice: BluetoothDevice? = null): Boolean {
        val addr = targetDevice?.address ?: return false
        return getAckMutex(addr).withLock {
            try {
                val payload = ByteArray(8).apply {
                    ByteBuffer.wrap(this).putLong(timestamp)
                }
                val chunk = ByteArray(payload.size + 3).apply {
                    this[0] = 3
                    this[1] = 0
                    this[2] = 1
                    System.arraycopy(payload, 0, this, 3, payload.size)
                }
                return@withLock sendChunksInternal(listOf(chunk), targetDevice)
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Exception in sendAck", e)
                false
            }
        }
    }

    suspend fun sendPublicKey(myUuidStr: String, publicKeyBytes: ByteArray, targetDevice: BluetoothDevice? = null): Boolean {
        val addr = targetDevice?.address ?: return false
        return getWriteMutex(addr).withLock {
            try {
                val uuid = try { java.util.UUID.fromString(myUuidStr) } catch (e: Exception) { return@withLock false }
                val payload = ByteArray(publicKeyBytes.size + 17).apply {
                    this[0] = 2
                    this[1] = 0
                    System.arraycopy(uuidToBytes(uuid), 0, this, 1, 16)
                    System.arraycopy(publicKeyBytes, 0, this, 17, publicKeyBytes.size)
                }
                val targetAddress = targetDevice.address
                val deviceMtu = tracker.deviceMtus[targetAddress] ?: 23
                val maxPayloadSize = java.lang.Math.min(deviceMtu - 6, 509)
                val rawChunks = payload.toList().chunked(maxPayloadSize).map { it.toByteArray() }
                val totalChunks = rawChunks.size
                val chunks = rawChunks.mapIndexed { index, data ->
                    ByteArray(data.size + 3).apply {
                        this[0] = 2
                        this[1] = index.toByte()
                        this[2] = totalChunks.toByte()
                        System.arraycopy(data, 0, this, 3, data.size)
                    }
                }
                return@withLock sendChunksInternal(chunks, targetDevice)
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Exception in sendPublicKey", e)
                false
            }
        }
    }

    private fun uuidToBytes(uuid: java.util.UUID): ByteArray {
        return ByteBuffer.allocate(16).apply {
            putLong(uuid.mostSignificantBits)
            putLong(uuid.leastSignificantBits)
        }.array()
    }

    private suspend fun getOrRefreshChar(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        gatt.getService(Constants.SERVICE_UUID)?.getCharacteristic(Constants.MESSAGE_CHARACTERISTIC_UUID)?.let { return it }
        Log.w(Constants.TAG, "Service/characteristic not found, triggering re-discovery")
        try { gatt.javaClass.getMethod("refresh").invoke(gatt) } catch (_: Exception) {}
        try { gatt.discoverServices() } catch (_: Exception) { return null }
        var waited = 0
        while (waited < 5000) {
            delay(200)
            waited += 200
            gatt.getService(Constants.SERVICE_UUID)?.getCharacteristic(Constants.MESSAGE_CHARACTERISTIC_UUID)?.let {
                Log.d(Constants.TAG, "Re-discovered characteristic after ${waited}ms")
                return it
            }
        }
        Log.e(Constants.TAG, "Failed to re-discover service/characteristic")
        return null
    }

    private suspend fun writeChunkWithRetry(gatt: BluetoothGatt, targetAddress: String, chunk: ByteArray, maxRetries: Int = 3): Boolean {
        for (attempt in 1..maxRetries) {
            val char = getOrRefreshChar(gatt) ?: run {
                if (attempt < maxRetries) delay(200)
                continue
            }
            val deferred = CompletableDeferred<Boolean>()
            tracker.currentWriteDeferreds[targetAddress] = deferred
            val writeSuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                char.value = chunk
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
            if (!writeSuccess) {
                tracker.currentWriteDeferreds.remove(targetAddress)
                if (attempt < maxRetries) { delay(100); continue }
                return false
            }
            val callbackResult = withTimeoutOrNull(Constants.CHUNK_WRITE_TIMEOUT_MS) { deferred.await() }
            tracker.currentWriteDeferreds.remove(targetAddress)
            if (callbackResult == true) return true
            if (attempt < maxRetries) delay(200)
        }
        return false
    }

    private suspend fun sendChunksInternal(chunks: List<ByteArray>, targetDevice: BluetoothDevice? = null): Boolean {
        val targetAddress = targetDevice?.address
        val targetUuid = targetAddress?.let { addr ->
            tracker.getPeerList().find { it.address == addr }?.uuid
                ?: tracker.getUuidByAddress(addr)
        } ?: ""

        // Path A: Send via GATT client connection (highly reliable — write with response)
        if (targetAddress != null) {
            val conn = tracker.clientConnections[targetAddress]
            if (conn != null) {
                var allSent = true
                for (chunk in chunks) {
                    if (!writeChunkWithRetry(conn.gatt, targetAddress, chunk)) {
                        allSent = false
                        break
                    }
                    if (chunks.size > 1) delay(30)
                }
                if (allSent) return true
                Log.w(Constants.TAG, "Path A (client write) failed for $targetAddress, falling back to Path B")
            }
        }

        // Path B: Send via GATT server notification
        val server = gattServerManager.bluetoothGattServer
        val activeClient = targetDevice?.let { targetDev ->
            val byAddress = tracker.connectedClients.find { it.address == targetDev.address }
            if (byAddress != null) byAddress
            else if (targetUuid.isNotEmpty()) tracker.getConnectedDeviceByPeerUuid(targetUuid)
            else null
        } ?: targetDevice?.address?.let { addr ->
            val uuid = tracker.getUuidByAddress(addr)
            if (uuid != null) tracker.getDeviceByUuid(uuid) else null
        } ?: targetDevice
        if (activeClient != null && server != null) {
            val characteristic = server.getService(Constants.SERVICE_UUID)?.getCharacteristic(Constants.MESSAGE_CHARACTERISTIC_UUID)
            if (characteristic != null) {
                var allSent = true
                val clientAddress = activeClient.address
                for (chunk in chunks) {
                    var chunkOk = false
                    for (attempt in 1..Constants.NOTIFICATION_RETRIES) {
                        val deferred = CompletableDeferred<Boolean>()
                        tracker.currentNotificationDeferreds[clientAddress] = deferred
                        val notifySuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            server.notifyCharacteristicChanged(activeClient, characteristic, false, chunk) == BluetoothStatusCodes.SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            characteristic.value = chunk
                            @Suppress("DEPRECATION")
                            server.notifyCharacteristicChanged(activeClient, characteristic, false)
                        }
                        if (!notifySuccess) {
                            tracker.currentNotificationDeferreds.remove(clientAddress)
                            delay(50)
                            continue
                        }
                        val callbackResult = withTimeoutOrNull(Constants.CHUNK_WRITE_TIMEOUT_MS) { deferred.await() }
                        tracker.currentNotificationDeferreds.remove(clientAddress)
                        if (callbackResult == true) {
                            chunkOk = true
                            break
                        }
                        delay(50)
                    }
                    if (!chunkOk) { allSent = false; break }
                    if (chunks.size > 1) delay(30)
                }
                if (allSent) return true
            }
        }
        Log.e(Constants.TAG, "Both send paths failed for $targetAddress")
        return false
    }

}
