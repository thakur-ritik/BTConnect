package com.example.btconnect.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.btconnect.model.ChatMessage
import com.example.btconnect.model.DeviceInfo
import com.example.btconnect.model.MessageKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

sealed class ConnectionState {
    data object Idle : ConnectionState()
    data class Connecting(val peerName: String, val peerAddress: String = "") : ConnectionState()
    data class AwaitingApproval(val peerName: String, val peerAddress: String = "") : ConnectionState()
    data class Connected(val peerName: String, val peerAddress: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

sealed class CallState {
    data object Idle : CallState()
    data class Outgoing(val peerName: String) : CallState()
    data class Incoming(val peerName: String) : CallState()
    data class InCall(val peerName: String, val startedAtMs: Long) : CallState()
}

/**
 * Singleton managing Bluetooth state, discovery, dual secure/insecure RFCOMM sockets,
 * chat messages, voice notes, file transfers, and voice calls.
 */
object BluetoothService {

    private var appContext: Context? = null
    private var adapter: BluetoothAdapter? = null

    private val _discoveredDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<DeviceInfo>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _fileProgress = MutableStateFlow<Float?>(null)
    val fileProgress: StateFlow<Float?> = _fileProgress.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    var lastTargetDevice: DeviceInfo? = null
        private set

    /** Invoked whenever a live audio chunk arrives during a call. */
    var onAudioChunkReceived: ((ByteArray) -> Unit)? = null

    private var secureServerThread: AcceptThread? = null
    private var insecureServerThread: AcceptThread? = null
    private var connectedThread: ConnectedThread? = null
    private var pendingIncomingThread: ConnectedThread? = null
    private var pendingFile: PendingFile? = null

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        ?: return
                    val majorClass = try { device.bluetoothClass?.majorDeviceClass ?: 0 } catch (e: Exception) { 0 }
                    val info = DeviceInfo(
                        name = device.name ?: "Unknown Device",
                        address = device.address,
                        device = device,
                        bonded = device.bondState == BluetoothDevice.BOND_BONDED,
                        majorClass = majorClass
                    )
                    _discoveredDevices.update { list ->
                        if (list.any { it.address == info.address }) {
                            list.map { if (it.address == info.address) info else it }
                        } else {
                            list + info
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isScanning.value = false
                }
            }
        }
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        adapter = BluetoothAdapter.getDefaultAdapter()
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(appContext!!, discoveryReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        startServers()
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun getDeviceName(): String {
        return try {
            adapter?.name ?: "My Phone"
        } catch (e: SecurityException) {
            "My Phone"
        }
    }

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<DeviceInfo> {
        val a = adapter ?: return emptyList()
        return try {
            a.bondedDevices.map {
                val major = try { it.bluetoothClass?.majorDeviceClass ?: 0 } catch (e: Exception) { 0 }
                DeviceInfo(name = it.name ?: "Paired Device", address = it.address, device = it, bonded = true, majorClass = major)
            }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val a = adapter ?: return
        _discoveredDevices.value = emptyList()
        if (a.isDiscovering) {
            a.cancelDiscovery()
        }
        _isScanning.value = a.startDiscovery()
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        adapter?.let { if (it.isDiscovering) it.cancelDiscovery() }
        _isScanning.value = false
    }

    // ---------------------------------------------------------------------
    // Dual Servers: Secure + Insecure listening
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startServers() {
        secureServerThread?.cancel()
        insecureServerThread?.cancel()

        secureServerThread = AcceptThread(isInsecure = false).also { it.start() }
        insecureServerThread = AcceptThread(isInsecure = true).also { it.start() }
    }

    private class AcceptThread(private val isInsecure: Boolean) : Thread() {
        private var serverSocket: BluetoothServerSocket? = null
        @Volatile private var running = true

        @SuppressLint("MissingPermission")
        override fun run() {
            while (running) {
                serverSocket = try {
                    if (isInsecure) {
                        adapter?.listenUsingInsecureRfcommWithServiceRecord("BTConnectInsecure", BluetoothProtocol.INSECURE_APP_UUID)
                    } else {
                        adapter?.listenUsingRfcommWithServiceRecord("BTConnect", BluetoothProtocol.APP_UUID)
                    }
                } catch (e: IOException) {
                    null
                }

                val socket = try {
                    serverSocket?.accept()
                } catch (e: IOException) {
                    null
                } ?: continue

                try { serverSocket?.close() } catch (e: IOException) {}

                if (_connectionState.value !is ConnectionState.Idle && _connectionState.value !is ConnectionState.Error) {
                    try { socket.close() } catch (e: IOException) {}
                    continue
                }
                handleIncomingSocket(socket)
            }
        }

        fun cancel() {
            running = false
            try { serverSocket?.close() } catch (e: IOException) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleIncomingSocket(socket: BluetoothSocket) {
        val thread = ConnectedThread(socket, isInitiator = false)
        pendingIncomingThread = thread
        thread.start()
    }

    // ---------------------------------------------------------------------
    // Client Connection: 3-Tier Fallback (Secure -> Insecure -> Channel Reflection)
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun requestConnection(target: DeviceInfo) {
        lastTargetDevice = target
        _errorMessage.value = null
        stopDiscovery()
        _connectionState.value = ConnectionState.Connecting(target.name, target.address)
        ConnectThread(target.device, target.name, target.address).start()
    }

    fun reconnect() {
        lastTargetDevice?.let { requestConnection(it) }
    }

    private class ConnectThread(
        private val device: BluetoothDevice,
        private val peerName: String,
        private val peerAddress: String
    ) : Thread() {
        @SuppressLint("MissingPermission")
        override fun run() {
            adapter?.cancelDiscovery()
            var socket: BluetoothSocket? = null

            // Tier 1: Try Secure RFCOMM
            try {
                socket = device.createRfcommSocketToServiceRecord(BluetoothProtocol.APP_UUID)
                socket.connect()
            } catch (e: Exception) {
                try { socket?.close() } catch (ex: Exception) {}
                socket = null
            }

            // Tier 2: Try Insecure RFCOMM Fallback
            if (socket == null) {
                try {
                    socket = device.createInsecureRfcommSocketToServiceRecord(BluetoothProtocol.INSECURE_APP_UUID)
                    socket.connect()
                } catch (e: Exception) {
                    try { socket?.close() } catch (ex: Exception) {}
                    socket = null
                }
            }

            // Tier 3: Try Channel 1 Reflection Fallback (Standard SPP fallback for problematic OEMs)
            if (socket == null) {
                try {
                    val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    socket = m.invoke(device, 1) as BluetoothSocket
                    socket.connect()
                } catch (e: Exception) {
                    try { socket?.close() } catch (ex: Exception) {}
                    socket = null
                }
            }

            if (socket == null) {
                val err = "Could not connect to $peerName. Make sure BTConnect is open on both devices and discoverable."
                _errorMessage.value = err
                _connectionState.value = ConnectionState.Error(err)
                return
            }

            val thread = ConnectedThread(socket, isInitiator = true, initialPeerName = peerName, initialPeerAddress = peerAddress)
            thread.start()
        }
    }

    // ---------------------------------------------------------------------
    // Approve / Reject Incoming Connection
    // ---------------------------------------------------------------------

    fun approveIncomingConnection() {
        val thread = pendingIncomingThread ?: return
        thread.sendConnectAccept()
        connectedThread = thread
        pendingIncomingThread = null
    }

    fun rejectIncomingConnection() {
        val thread = pendingIncomingThread ?: return
        thread.sendConnectReject()
        thread.close()
        pendingIncomingThread = null
        _connectionState.value = ConnectionState.Idle
    }

    fun disconnect() {
        try { connectedThread?.sendFrame(BluetoothProtocol.TYPE_DISCONNECT, ByteArray(0)) } catch (e: Exception) {}
        connectedThread?.close()
        connectedThread = null
        pendingIncomingThread?.close()
        pendingIncomingThread = null
        _connectionState.value = ConnectionState.Idle
        _callState.value = CallState.Idle
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }

    fun clearError() {
        _errorMessage.value = null
        if (_connectionState.value is ConnectionState.Error) {
            _connectionState.value = ConnectionState.Idle
        }
    }

    fun resetError() = clearError()

    // ---------------------------------------------------------------------
    // Messaging, Files & Voice Notes
    // ---------------------------------------------------------------------

    fun sendText(text: String) {
        val thread = connectedThread ?: return
        thread.sendFrame(BluetoothProtocol.TYPE_TEXT, text)
        _messages.update { it + ChatMessage(isMine = true, kind = MessageKind.TEXT, text = text) }
    }

    fun sendFile(
        inputStream: InputStream,
        name: String,
        size: Long,
        mime: String,
        localPath: String? = null,
        localImage: Bitmap? = null
    ) {
        val thread = connectedThread ?: return
        Thread {
            try {
                _fileProgress.value = 0f
                thread.sendFrame(BluetoothProtocol.TYPE_FILE_META, "$name|$size|$mime|0")
                val buffer = ByteArray(8192)
                var sent = 0L
                inputStream.use { stream ->
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                        thread.sendFrame(BluetoothProtocol.TYPE_FILE_CHUNK, chunk)
                        sent += read
                        if (size > 0) _fileProgress.value = (sent.toFloat() / size.toFloat()).coerceIn(0f, 1f)
                    }
                }
                thread.sendFrame(BluetoothProtocol.TYPE_FILE_END, ByteArray(0))
                val isImage = mime.startsWith("image/")
                _messages.update {
                    it + ChatMessage(
                        isMine = true,
                        kind = if (isImage) MessageKind.IMAGE else MessageKind.FILE,
                        image = localImage,
                        fileName = name,
                        fileSize = size,
                        filePath = localPath,
                        mimeType = mime
                    )
                }
            } catch (e: Exception) {
            } finally {
                _fileProgress.value = null
            }
        }.start()
    }

    fun sendVoiceNote(file: File, durationMs: Long) {
        val thread = connectedThread ?: return
        Thread {
            try {
                _fileProgress.value = 0f
                val name = file.name
                val size = file.length()
                thread.sendFrame(BluetoothProtocol.TYPE_FILE_META, "$name|$size|audio/mp4|$durationMs")
                val buffer = ByteArray(8192)
                file.inputStream().use { stream ->
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                        thread.sendFrame(BluetoothProtocol.TYPE_FILE_CHUNK, chunk)
                    }
                }
                thread.sendFrame(BluetoothProtocol.TYPE_FILE_END, ByteArray(0))
                _messages.update {
                    it + ChatMessage(
                        isMine = true,
                        kind = MessageKind.VOICE_NOTE,
                        fileName = name,
                        fileSize = size,
                        filePath = file.absolutePath,
                        durationMs = durationMs,
                        mimeType = "audio/mp4"
                    )
                }
            } catch (e: Exception) {
            } finally {
                _fileProgress.value = null
            }
        }.start()
    }

    // ---------------------------------------------------------------------
    // Call Signaling
    // ---------------------------------------------------------------------

    fun startCall() {
        val state = _connectionState.value as? ConnectionState.Connected ?: return
        val thread = connectedThread ?: return
        thread.sendFrame(BluetoothProtocol.TYPE_CALL_REQUEST, ByteArray(0))
        _callState.value = CallState.Outgoing(state.peerName)
    }

    fun acceptCall() {
        val state = _callState.value as? CallState.Incoming ?: return
        AudioCallManager.stopRinging()
        connectedThread?.sendFrame(BluetoothProtocol.TYPE_CALL_ACCEPT, ByteArray(0))
        _callState.value = CallState.InCall(state.peerName, System.currentTimeMillis())
    }

    fun rejectCall() {
        AudioCallManager.stopRinging()
        connectedThread?.sendFrame(BluetoothProtocol.TYPE_CALL_REJECT, ByteArray(0))
        _callState.value = CallState.Idle
    }

    fun endCall() {
        AudioCallManager.stopRinging()
        connectedThread?.sendFrame(BluetoothProtocol.TYPE_CALL_END, ByteArray(0))
        _callState.value = CallState.Idle
    }

    fun sendAudioChunk(bytes: ByteArray) {
        connectedThread?.sendFrame(BluetoothProtocol.TYPE_AUDIO_CHUNK, bytes)
    }

    // ---------------------------------------------------------------------
    // Connected Socket Worker Thread
    // ---------------------------------------------------------------------

    private class ConnectedThread(
        private val socket: BluetoothSocket,
        private val isInitiator: Boolean,
        initialPeerName: String? = null,
        initialPeerAddress: String? = null
    ) : Thread("BTConnect-IOThread") {
        private val input = DataInputStream(socket.inputStream)
        private val output = DataOutputStream(socket.outputStream)
        private var peerName: String = initialPeerName ?: "Nearby Device"
        private var peerAddress: String = initialPeerAddress ?: (socket.remoteDevice?.address ?: "")
        @Volatile private var running = true

        @SuppressLint("MissingPermission")
        override fun run() {
            if (isInitiator) {
                val myName = adapter?.name ?: "Android Device"
                BluetoothProtocol.writeFrame(output, BluetoothProtocol.TYPE_CONNECT_REQUEST, myName)
            }

            while (running) {
                val frame = BluetoothProtocol.readFrame(input) ?: break
                handleFrame(frame)
            }
            close()
            if (connectedThread === this) {
                connectedThread = null
                _connectionState.value = ConnectionState.Idle
                _callState.value = CallState.Idle
                AudioCallManager.stopRinging()
            }
            if (pendingIncomingThread === this) {
                pendingIncomingThread = null
                _connectionState.value = ConnectionState.Idle
            }
        }

        @SuppressLint("MissingPermission")
        private fun handleFrame(frame: BluetoothProtocol.Frame) {
            when (frame.type) {
                BluetoothProtocol.TYPE_CONNECT_REQUEST -> {
                    peerName = frame.text().ifBlank { "Nearby Device" }
                    peerAddress = socket.remoteDevice?.address ?: ""
                    _connectionState.value = ConnectionState.AwaitingApproval(peerName, peerAddress)
                }
                BluetoothProtocol.TYPE_CONNECT_ACCEPT -> {
                    connectedThread = this
                    val address = socket.remoteDevice?.address ?: peerAddress
                    _connectionState.value = ConnectionState.Connected(peerName, address)
                }
                BluetoothProtocol.TYPE_CONNECT_REJECT -> {
                    running = false
                    val err = "$peerName declined the connection request."
                    _errorMessage.value = err
                    _connectionState.value = ConnectionState.Error(err)
                }
                BluetoothProtocol.TYPE_DISCONNECT -> {
                    running = false
                    _connectionState.value = ConnectionState.Idle
                    _callState.value = CallState.Idle
                    AudioCallManager.stopRinging()
                }
                BluetoothProtocol.TYPE_TEXT -> {
                    _messages.update { it + ChatMessage(isMine = false, kind = MessageKind.TEXT, text = frame.text()) }
                }
                BluetoothProtocol.TYPE_FILE_META -> {
                    val parts = frame.text().split("|")
                    val name = parts.getOrElse(0) { "file" }
                    val size = parts.getOrElse(1) { "0" }.toLongOrNull() ?: 0L
                    val mime = parts.getOrElse(2) { "application/octet-stream" }
                    val durationMs = parts.getOrElse(3) { "0" }.toLongOrNull() ?: 0L
                    pendingFile = PendingFile(name, size, mime, durationMs, ByteArrayOutputStream())
                }
                BluetoothProtocol.TYPE_FILE_CHUNK -> {
                    pendingFile?.buffer?.write(frame.payload)
                }
                BluetoothProtocol.TYPE_FILE_END -> {
                    finishIncomingFile()
                }
                BluetoothProtocol.TYPE_CALL_REQUEST -> {
                    _callState.value = CallState.Incoming(peerName)
                    appContext?.let { AudioCallManager.startRinging(it) }
                }
                BluetoothProtocol.TYPE_CALL_ACCEPT -> {
                    AudioCallManager.stopRinging()
                    _callState.value = CallState.InCall(peerName, System.currentTimeMillis())
                }
                BluetoothProtocol.TYPE_CALL_REJECT, BluetoothProtocol.TYPE_CALL_END -> {
                    AudioCallManager.stopRinging()
                    _callState.value = CallState.Idle
                }
                BluetoothProtocol.TYPE_AUDIO_CHUNK -> {
                    onAudioChunkReceived?.invoke(frame.payload)
                }
            }
        }

        private fun finishIncomingFile() {
            val file = pendingFile ?: return
            pendingFile = null
            val bytes = file.buffer.toByteArray()

            if (file.mime.startsWith("image/")) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val dir = File(appContext?.getExternalFilesDir(null), "received").apply { mkdirs() }
                val outFile = File(dir, file.name)
                try { FileOutputStream(outFile).use { it.write(bytes) } } catch (e: Exception) {}
                _messages.update {
                    it + ChatMessage(
                        isMine = false,
                        kind = MessageKind.IMAGE,
                        image = bitmap,
                        fileName = file.name,
                        fileSize = file.size,
                        filePath = outFile.absolutePath,
                        mimeType = file.mime
                    )
                }
            } else if (file.mime == "audio/mp4" || file.name.endsWith(".m4a")) {
                val dir = File(appContext?.getExternalFilesDir(null), "voice_notes").apply { mkdirs() }
                val outFile = File(dir, file.name)
                try {
                    FileOutputStream(outFile).use { it.write(bytes) }
                    _messages.update {
                        it + ChatMessage(
                            isMine = false,
                            kind = MessageKind.VOICE_NOTE,
                            fileName = file.name,
                            fileSize = file.size,
                            filePath = outFile.absolutePath,
                            durationMs = file.durationMs,
                            mimeType = file.mime
                        )
                    }
                } catch (e: Exception) {}
            } else {
                val dir = File(appContext?.getExternalFilesDir(null), "received").apply { mkdirs() }
                val outFile = File(dir, file.name)
                try {
                    FileOutputStream(outFile).use { it.write(bytes) }
                    _messages.update {
                        it + ChatMessage(
                            isMine = false,
                            kind = MessageKind.FILE,
                            fileName = file.name,
                            fileSize = file.size,
                            filePath = outFile.absolutePath,
                            mimeType = file.mime
                        )
                    }
                } catch (e: Exception) {}
            }
        }

        fun sendConnectAccept() {
            val socketAddress = socket.remoteDevice?.address ?: peerAddress
            BluetoothProtocol.writeFrame(output, BluetoothProtocol.TYPE_CONNECT_ACCEPT, ByteArray(0))
            _connectionState.value = ConnectionState.Connected(peerName, socketAddress)
        }

        fun sendConnectReject() {
            BluetoothProtocol.writeFrame(output, BluetoothProtocol.TYPE_CONNECT_REJECT, ByteArray(0))
        }

        fun sendFrame(type: Int, text: String) = BluetoothProtocol.writeFrame(output, type, text)
        fun sendFrame(type: Int, bytes: ByteArray) = BluetoothProtocol.writeFrame(output, type, bytes)

        fun close() {
            running = false
            try { socket.close() } catch (e: IOException) {}
        }
    }

    private data class PendingFile(
        val name: String,
        val size: Long,
        val mime: String,
        val durationMs: Long,
        val buffer: ByteArrayOutputStream
    )
}
