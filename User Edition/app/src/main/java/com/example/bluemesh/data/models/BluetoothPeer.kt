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
    val allowTracking: Boolean = false,
    val isRelayed: Boolean = false
) {
    val estimatedDistance: Double
        get() {
            if (rssi == 0 || rssi == -100) return -1.0
            // Calibrated: -60 dBm = 1m, -80 dBm = 2m
            // d = 10^((P0 - RSSI) / (10 * n))  → n = 2 / log10(d2/d1)
            val p0 = -60.0
            val n = 6.64
            return Math.pow(10.0, (p0 - rssi) / (10.0 * n))
        }
}
