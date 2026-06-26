package com.example.bluemesh.bluetooth

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.example.bluemesh.data.models.ChatMessage
import net.jpountz.lz4.LZ4Factory
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MessageProcessor(private val tracker: ConnectionTracker) {

    var onKeyExchangeReceived: ((String, ByteArray, BluetoothDevice) -> Unit)? = null

    private val firstChunkTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun handleIncomingValue(device: BluetoothDevice, value: ByteArray) {
        val receiveTime = System.currentTimeMillis()
        Log.d(Constants.TAG, "RX value from ${device.address}: ${value.size} bytes, first=${value[0].toInt() and 0xFF}")
        val result = handleIncomingPacket(device.address, value)
        if (result == null) {
            Log.d(Constants.TAG, "RX handleIncomingPacket returned null (chunked/buffered) from ${device.address}")
        }
        result?.let { (type, assembled) ->
            if (assembled.isEmpty()) return
            Log.d(Constants.TAG, "RX assembled msgType=$type size=${assembled.size} from ${device.address}")

            if (type == 2.toByte()) {
                if (assembled.size >= 17) {
                    val senderUuid = bytesToUuid(assembled.copyOfRange(1, 17)).toString()
                    onKeyExchangeReceived?.invoke(senderUuid, assembled.copyOfRange(17, assembled.size), device)
                }
                firstChunkTimes.remove("${device.address}_${type}")
            } else if (type == 1.toByte()) {
                val chunkKey = "${device.address}_${type}"
                val firstChunkTime = firstChunkTimes.remove(chunkKey) ?: receiveTime
                processType1Payload(assembled, device, firstChunkTime)
            } else if (type == 3.toByte()) {
                if (assembled.size >= 8) {
                    firstChunkTimes.remove("${device.address}_${type}")
                    val ackTimestamp = ByteBuffer.wrap(assembled).long
                    val wireWriteTime = tracker.actualSendTimes.remove(ackTimestamp)
                    val latencyMs = if (wireWriteTime != null) {
                        val rtt = receiveTime - wireWriteTime
                        if (rtt < 0) 0L else rtt / 2
                    } else null
                    val originalCreationTime = tracker.otaToCreationTime.remove(ackTimestamp)
                    tracker.emitAck(Pair(originalCreationTime ?: ackTimestamp, latencyMs))
                }
            }
        }
    }

    fun handleIncomingPacket(senderAddress: String, packet: ByteArray): Pair<Byte, ByteArray>? {
        if (packet.size < 3) return null
        val type = packet[0]
        val chunkIndex = packet[1].toInt() and 0xFF
        val totalChunks = packet[2].toInt() and 0xFF
        val data = packet.copyOfRange(3, packet.size)

        val key = "${senderAddress}_${type}"

        if (totalChunks <= 1) {
            firstChunkTimes[key] = System.currentTimeMillis()
            return Pair(type, data)
        }

        tracker.lastChunkTimestamp[key] = System.currentTimeMillis()
        val chunksMap = tracker.incomingChunks.getOrPut(key) { java.util.concurrent.ConcurrentHashMap() }

        var isAssembled = false
        var assembledData: ByteArray? = null

        synchronized(chunksMap) {
            if (chunkIndex == 0) {
                chunksMap.clear()
                firstChunkTimes[key] = System.currentTimeMillis()
            }
            chunksMap[chunkIndex] = data
            if (chunksMap.size == totalChunks) {
                val allPresent = (0 until totalChunks).all { chunksMap.containsKey(it) }
                if (allPresent) {
                    isAssembled = true
                    tracker.incomingChunks.remove(key)
                    tracker.lastChunkTimestamp.remove(key)
                    firstChunkTimes.remove(key)
                    val totalSize = (0 until totalChunks).sumOf { chunksMap[it]!!.size }
                    val assembled = ByteArray(totalSize)
                    var offset = 0
                    for (i in 0 until totalChunks) {
                        val chunkData = chunksMap[i]!!
                        System.arraycopy(chunkData, 0, assembled, offset, chunkData.size)
                        offset += chunkData.size
                    }
                    assembledData = assembled
                }
            }
        }

        if (isAssembled && assembledData != null) {
            return Pair(type, assembledData!!)
        }
        return null
    }

    private fun processType1Payload(assembled: ByteArray, device: BluetoothDevice, receiveTime: Long) {
        val decompressed = parseAndDecompress(assembled)
        if (decompressed == null) {
            Log.e(Constants.TAG, "RX parseAndDecompress returned null from ${device.address}")
            return
        }
        val msg = ChatMessage(
            text = String(decompressed, Charsets.ISO_8859_1),
            isFromMe = false,
            timestamp = receiveTime,
            senderAddress = device.address
        )
        val emitted = tracker.tryEmitMessage(msg)
        Log.d(Constants.TAG, "RX emitted ChatMessage from ${device.address}: ${decompressed.size} bytes, success=$emitted")
    }

    fun parseAndDecompress(value: ByteArray): ByteArray? {
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

    fun bytesToUuid(bytes: ByteArray): java.util.UUID {
        val byteBuffer = ByteBuffer.wrap(bytes)
        return java.util.UUID(byteBuffer.long, byteBuffer.long)
    }

    companion object {
        const val TAG = "MessageProcessor"

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
}
