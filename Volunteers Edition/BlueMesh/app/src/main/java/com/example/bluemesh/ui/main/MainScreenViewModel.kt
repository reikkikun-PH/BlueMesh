package com.example.bluemesh.ui.main

import androidx.lifecycle.ViewModel
import com.example.bluemesh.data.DataRepository
import com.example.bluemesh.data.models.BluetoothPeer
import kotlinx.coroutines.flow.StateFlow

class MainScreenViewModel(private val dataRepository: DataRepository) : ViewModel() {
    val discoveredPeers: StateFlow<List<BluetoothPeer>> = dataRepository.discoveredPeers
    val isScanning: StateFlow<Boolean> = dataRepository.isScanning
    val isAdvertising: StateFlow<Boolean> = dataRepository.isAdvertising

    val displayName: String
        get() = dataRepository.getDisplayName()

    fun startScanning() {
        dataRepository.startScan()
    }

    fun stopScanning() {
        dataRepository.stopScan()
    }

    fun toggleDiscoverability(enable: Boolean) {
        if (enable) {
            dataRepository.startAdvertising(displayName)
        } else {
            dataRepository.stopAdvertising()
        }
    }
}
