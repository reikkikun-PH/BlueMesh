package com.example.bluemesh.bluetooth

import android.os.ParcelUuid
import java.util.UUID

object Constants {
    const val TAG = "BlueMesh-BT"

    val SERVICE_UUID: UUID = UUID.fromString("b17c8a70-8bde-4d76-bc3e-1b32d2f7881c")
    val MESSAGE_CHARACTERISTIC_UUID: UUID = UUID.fromString("b17c8a71-8bde-4d76-bc3e-1b32d2f7881c")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val MESH_SERVICE_UUID_STR = "12345678-1234-5678-1234-567890abcdef"
    const val MESH_COMPANY_ID = 0xFFFF
    const val PEER_MANUFACTURER_ID = 0xFFFF
    val SERVICE_UUID_PARCEL = ParcelUuid(SERVICE_UUID)
    val MESH_SERVICE_UUID_PARCEL = ParcelUuid(UUID.fromString(MESH_SERVICE_UUID_STR))

    const val CACHE_MAX_SIZE = 100
    const val MAX_CLIENT_CONNECTIONS = 8
    const val MAX_CONNECTIONS_OVERALL = 8

    const val CLIENT_SYNC_TIMEOUT_MS = 10000L
    const val SERVER_CCCD_TIMEOUT_MS = 20000L
    const val CONNECTION_TIMEOUT_MS = 15000L
    const val CHUNK_WRITE_TIMEOUT_MS = 6000L
    const val CHUNK_REASSEMBLY_TIMEOUT_MS = 5000L
    const val PEER_EXPIRY_MS = 25000L
    const val EVICTION_INTERVAL_MS = 2000L
    const val SCAN_DURATION_MS = 300000L
    const val SCAN_COOLDOWN_MS = 100L
    const val ADVERTISE_GOODBYE_DURATION_MS = 800L
    const val MESH_ADVERTISE_DURATION_MS = 10000L
    const val CCCD_WRITE_RETRIES = 3
    const val DISCOVERY_RETRIES = 1
    const val GATT_SERVER_RETRIES = 5
    const val BLE_RESET_DELAY_MS = 1000L
    const val GATT_WRITE_RETRIES = 3
    const val NOTIFICATION_RETRIES = 3

    const val SCAN_MODE_BALANCED_INTERVAL_MS = 3000L
    const val SCAN_RATE_LIMIT_MS = 3000L
}
