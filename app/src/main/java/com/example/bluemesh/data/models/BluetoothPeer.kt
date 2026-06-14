package com.example.bluemesh.data.models

import android.bluetooth.BluetoothDevice

data class BluetoothPeer(
    val address: String,
    val name: String,
    val device: BluetoothDevice?,
    val lastSeen: Long = System.currentTimeMillis(),
    val uuid: String = "",
    val hasPasscode: Boolean = false,
    val isOfficial: Boolean = false,
    val rssi: Int = -100,
    val allowTracking: Boolean = false
) {
    val estimatedDistance: Double
        get() {
            if (rssi == 0 || rssi == -100) return -1.0
            // Log-distance path loss model: d = 10^((P0 - RSSI) / (10 * n))
            // P0: measured RSSI at 1 meter (approx. -59 dBm)
            // n: path loss exponent (approx. 2.2 for typical indoor / outdoor)
            val p0 = -59.0
            val n = 2.2
            return Math.pow(10.0, (p0 - rssi) / (10.0 * n))
        }
}
