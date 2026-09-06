package com.example.btconnect.model

import android.bluetooth.BluetoothDevice

/**
 * Lightweight wrapper around a BluetoothDevice so the UI never has to
 * touch the raw Android Bluetooth API directly.
 */
data class DeviceInfo(
    val name: String,
    val address: String,
    val device: BluetoothDevice,
    val bonded: Boolean = false
)
