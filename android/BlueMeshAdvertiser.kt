import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class BlueMeshAdvertiser(private val bluetoothAdapter: BluetoothAdapter) {
    private var advertiser: BluetoothLeAdvertiser? = null
    
    // Fixed Service UUID filtering on the ESP32 relay and client apps
    private val SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-567890abcdef")
    
    // Custom Company Identifier (placeholder for Manufacturer Specific Data field)
    private val MANUFACTURER_ID = 0xFFFF 

    /**
     * Packs the Message ID and text payload and starts BLE advertising.
     *
     * @param messageId Unique uint32_t Message ID used by the relay for sliding window deduplication.
     * @param messageText Raw text chat message to be forwarded.
     * @param callback The advertising callback to receive success/failure status.
     */
    fun startAdvertisingMessage(messageId: Int, messageText: String, callback: AdvertiseCallback) {
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e("BlueMesh", "BLE Advertising not supported on this device")
            return
        }

        val messageBytes = messageText.toByteArray(Charsets.UTF_8)
        
        // Allocate buffer: 4 bytes for uint32_t Message ID + message text bytes length
        val buffer = ByteBuffer.allocate(4 + messageBytes.size)
        buffer.order(ByteOrder.BIG_ENDIAN) // Big-endian byte order matches C++ shifting on ESP32
        buffer.putInt(messageId)
        buffer.put(messageBytes)
        
        val manufacturerSpecificData = buffer.array()

        // 1. Primary advertisement packet (contains the Service UUID filter)
        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        // 2. Scan response packet (holds manufacturer data to maximize chat text length)
        val scanResponseData = AdvertiseData.Builder()
            .addManufacturerData(MANUFACTURER_ID, manufacturerSpecificData)
            .build()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false) // Flooding packet (non-connectable, broadcast only)
            .build()

        try {
            advertiser?.startAdvertising(settings, advertiseData, scanResponseData, callback)
            Log.i("BlueMesh", "Started advertising message: ID=$messageId, Text=$messageText")
        } catch (e: Exception) {
            Log.e("BlueMesh", "Error starting BLE advertising", e)
        }
    }

    /**
     * Stops the active advertising session.
     */
    fun stopAdvertising(callback: AdvertiseCallback) {
        try {
            advertiser?.stopAdvertising(callback)
            Log.i("BlueMesh", "Stopped advertising")
        } catch (e: Exception) {
            Log.e("BlueMesh", "Error stopping BLE advertising", e)
        }
    }
}
