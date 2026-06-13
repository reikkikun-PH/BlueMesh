package com.example.bluemesh.data.models

import android.bluetooth.BluetoothDevice

data class BluetoothPeer(
    val address: String,
    val name: String,
    val device: BluetoothDevice?,
    val lastSeen: Long = System.currentTimeMillis(),
    val uuid: String = "",
    val hasPasscode: Boolean = false,
    val isOfficial: Boolean = false
)
