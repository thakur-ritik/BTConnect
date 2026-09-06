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
    data class Connecting(val peerName: String) : ConnectionState()
    data class AwaitingApproval(val peerName: String) : ConnectionState() // we are the acceptor
    data class Connected(val peerName: String, val peerAddress: String) : ConnectionState()
}

sealed class CallState {
    data object Idle : CallState()
    data class Outgoing(val peerName: String) : CallState()
    data class Incoming(val peerName: String) : CallState()
    data class InCall(val peerName: String, val startedAtMs: Long) : CallState()
}

/**
 * Singleton that owns the Bluetooth adapter, device discovery, the single
 * active RFCOMM connection, and everything sent over it (chat text, files,
 * call signaling, live call audio). Call [init] once with an Application
 * context before use.
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

    /** Invoked on the IO thread whenever a live audio chunk arrives during a call. */
    var onAudioChunkReceived: ((ByteArray) -> Unit)? = null

    private var serverThread: AcceptThread? = null
    private var connectedThread: ConnectedThread? = null
    private var pendingIncomingThread: ConnectedThread? = null // held while AwaitingApproval

    private var pendingFile: PendingFile? = null

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        ?: return
                    val info = DeviceInfo(
                        name = device.name ?: "Unknown device",
                        address = device.address,
                        device = device,
                        bonded = device.bondState == BluetoothDevice.BOND_BONDED
                    )
                    _discoveredDevices.update { list ->
                        if (list.any { it.address == info.address }) list
                        else list + info
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
        startServer()
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<DeviceInfo> {
        val a = adapter ?: return emptyList()
        return try {
            a.bondedDevices.map {
                DeviceInfo(name = it.name ?: "Unknown device", address = it.address, device = it, bonded = true)
            }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val a = adapter ?: return
        _discoveredDevices.value = emptyList()
        if (a.isDiscovering) a.cancelDiscovery()
        _isScanning.value = a.startDiscovery()
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        adapter?.let { if (it.isDiscovering) it.cancelDiscovery() }
        _isScanning.value = false
    }

    // ---------------------------------------------------------------------
    // Server side: always listening so nearby devices can send us a request
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startServer() {
        serverThread?.cancel()
        serverThread = AcceptThread().also { it.start() }
    }

    private class AcceptThread : Thread() {
        private var serverSocket: BluetoothServerSocket? = null

        @SuppressLint("MissingPermission")
        override fun run() {
            while (true) {
                serverSocket = try {
                    adapter?.listenUsingRfcommWithServiceRecord("BTConnect", BluetoothProtocol.APP_UUID)
                } catch (e: IOException) {
                    null
                }
                val socket = try {
                    serverSocket?.accept()
                } catch (e: IOException) {
                    null
                } ?: continue

                serverSocket?.close()

                // Only accept a new incoming request if we're not already busy.
                if (_connectionState.value !is ConnectionState.Idle) {
                    try { socket.close() } catch (e: IOException) {}
                    continue
                }
                handleIncomingSocket(socket)
            }
        }

        fun cancel() {
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
    // Client side: user picked a device from the list and wants to connect
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun requestConnection(target: DeviceInfo) {
        if (_connectionState.value !is ConnectionState.Idle) return
        stopDiscovery()
        _connectionState.value = ConnectionState.Connecting(target.name)
        ConnectThread(target.device, target.name).start()
    }

    private class ConnectThread(
        private val device: BluetoothDevice,
        private val peerName: String
    ) : Thread() {
        @SuppressLint("MissingPermission")
        override fun run() {
            val socket = try {
                device.createRfcommSocketToServiceRecord(BluetoothProtocol.APP_UUID)
            } catch (e: IOException) {
                null
            }
            if (socket == null) {
                _connectionState.value = ConnectionState.Idle
                return
            }
            try {
                socket.connect()
            } catch (e: IOException) {
                try { socket.close() } catch (e2: IOException) {}
                _connectionState.value = ConnectionState.Idle
                return
            }
            val thread = ConnectedThread(socket, isInitiator = true, initialPeerName = peerName)
            thread.start()
        }
    }

    // ---------------------------------------------------------------------
    // Approve / reject an incoming connection request from the UI
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
        connectedThread?.close()
        connectedThread = null
        _connectionState.value = ConnectionState.Idle
        _callState.value = CallState.Idle
        _messages.value = emptyList()
    }

    // ---------------------------------------------------------------------
    // Chat + file sending
    // ---------------------------------------------------------------------

    fun sendText(text: String) {
        val thread = connectedThread ?: return
        thread.sendFrame(BluetoothProtocol.TYPE_TEXT, text)
        _messages.update { it + ChatMessage(isMine = true, kind = MessageKind.TEXT, text = text) }
    }

    /** Streams a file (e.g. picked from the gallery) to the connected peer in chunks. */
    fun sendFile(inputStream: InputStream, name: String, size: Long, mime: String) {
        val thread = connectedThread ?: return
        Thread {
            try {
                _fileProgress.value = 0f
                thread.sendFrame(BluetoothProtocol.TYPE_FILE_META, "$name|$size|$mime")
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
                        fileName = name,
                        fileSize = size
                    )
                }
            } catch (e: Exception) {
                // Transfer failed (e.g. disconnected mid-send) - nothing more to do.
            } finally {
                _fileProgress.value = null
            }
        }.start()
    }

    // ---------------------------------------------------------------------
    // Call signaling
    // ---------------------------------------------------------------------

    fun startCall() {
        val state = _connectionState.value as? ConnectionState.Connected ?: return
        val thread = connectedThread ?: return
        thread.sendFrame(BluetoothProtocol.TYPE_CALL_REQUEST, ByteArray(0))
        _callState.value = CallState.Outgoing(state.peerName)
    }

    fun acceptCall() {
        val state = _callState.value as? CallState.Incoming ?: return
        connectedThread?.sendFrame(BluetoothProtocol.TYPE_CALL_ACCEPT, ByteArray(0))
        _callState.value = CallState.InCall(state.peerName, System.currentTimeMillis())
    }

    fun rejectCall() {
        connectedThread?.sendFrame(BluetoothProtocol.TYPE_CALL_REJECT, ByteArray(0))
        _callState.value = CallState.Idle
    }

    fun endCall() {
        connectedThread?.sendFrame(BluetoothProtocol.TYPE_CALL_END, ByteArray(0))
        _callState.value = CallState.Idle
    }

    fun sendAudioChunk(bytes: ByteArray) {
        connectedThread?.sendFrame(BluetoothProtocol.TYPE_AUDIO_CHUNK, bytes)
    }

    // ---------------------------------------------------------------------
    // The single active connection: reads frames and dispatches them
    // ---------------------------------------------------------------------

    private class ConnectedThread(
        private val socket: BluetoothSocket,
        private val isInitiator: Boolean,
        initialPeerName: String? = null
    ) : Thread() {
        private val input = DataInputStream(socket.inputStream)
        private val output = DataOutputStream(socket.outputStream)
        private var peerName: String = initialPeerName ?: "Unknown"
        @Volatile private var running = true

        @SuppressLint("MissingPermission")
        override fun run() {
            if (isInitiator) {
                val myName = adapter?.name ?: "My phone"
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
            }
        }

        @SuppressLint("MissingPermission")
        private fun handleFrame(frame: BluetoothProtocol.Frame) {
            when (frame.type) {
                BluetoothProtocol.TYPE_CONNECT_REQUEST -> {
                    peerName = frame.text()
                    _connectionState.value = ConnectionState.AwaitingApproval(peerName)
                }
                BluetoothProtocol.TYPE_CONNECT_ACCEPT -> {
                    connectedThread = this
                    val socketAddress = socket.remoteDevice?.address ?: ""
                    _connectionState.value = ConnectionState.Connected(peerName, socketAddress)
                }
                BluetoothProtocol.TYPE_CONNECT_REJECT -> {
                    running = false
                    _connectionState.value = ConnectionState.Idle
                }
                BluetoothProtocol.TYPE_TEXT -> {
                    _messages.update { it + ChatMessage(isMine = false, kind = MessageKind.TEXT, text = frame.text()) }
                }
                BluetoothProtocol.TYPE_FILE_META -> {
                    val parts = frame.text().split("|")
                    val name = parts.getOrElse(0) { "file" }
                    val size = parts.getOrElse(1) { "0" }.toLongOrNull() ?: 0L
                    val mime = parts.getOrElse(2) { "application/octet-stream" }
                    pendingFile = PendingFile(name, size, mime, ByteArrayOutputStream())
                }
                BluetoothProtocol.TYPE_FILE_CHUNK -> {
                    pendingFile?.buffer?.write(frame.payload)
                }
                BluetoothProtocol.TYPE_FILE_END -> {
                    finishIncomingFile()
                }
                BluetoothProtocol.TYPE_CALL_REQUEST -> {
                    _callState.value = CallState.Incoming(peerName)
                }
                BluetoothProtocol.TYPE_CALL_ACCEPT -> {
                    _callState.value = CallState.InCall(peerName, System.currentTimeMillis())
                }
                BluetoothProtocol.TYPE_CALL_REJECT, BluetoothProtocol.TYPE_CALL_END -> {
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
                _messages.update {
                    it + ChatMessage(isMine = false, kind = MessageKind.IMAGE, image = bitmap, fileName = file.name, fileSize = file.size)
                }
            } else {
                val dir = File(appContext?.getExternalFilesDir(null), "received")
                dir.mkdirs()
                val outFile = File(dir, file.name)
                FileOutputStream(outFile).use { it.write(bytes) }
                _messages.update {
                    it + ChatMessage(
                        isMine = false, kind = MessageKind.FILE,
                        fileName = file.name, fileSize = file.size, filePath = outFile.absolutePath
                    )
                }
            }
        }

        fun sendConnectAccept() {
            val socketAddress = socket.remoteDevice?.address ?: ""
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

    private data class PendingFile(val name: String, val size: Long, val mime: String, val buffer: ByteArrayOutputStream)
}
