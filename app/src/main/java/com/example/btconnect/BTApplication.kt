package com.example.btconnect

import android.app.Application
import com.example.btconnect.bluetooth.BluetoothService

class BTApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BluetoothService.init(this)
    }
}
