package com.example.btconnect

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.btconnect.bluetooth.AudioCallManager
import com.example.btconnect.bluetooth.BluetoothService
import com.example.btconnect.bluetooth.CallState
import com.example.btconnect.bluetooth.ConnectionState
import com.example.btconnect.ui.screens.CallScreen
import com.example.btconnect.ui.screens.CallScreenMode
import com.example.btconnect.ui.screens.ChatScreen
import com.example.btconnect.ui.screens.DeviceListScreen
import com.example.btconnect.ui.theme.BTConnectTheme
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        perms.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms.toTypedArray()
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BTConnectTheme {
                var permissionsGranted by remember { mutableStateOf(hasAllPermissions()) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    permissionsGranted = result.values.all { it }
                }

                val enableBtLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { /* result handled by state check */ }

                LaunchedEffect(Unit) {
                    if (!permissionsGranted) permissionLauncher.launch(requiredPermissions())
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!permissionsGranted) {
                        PermissionGate(onGrantClick = { permissionLauncher.launch(requiredPermissions()) })
                    } else if (!BluetoothService.isBluetoothEnabled()) {
                        BluetoothOffGate(onEnableClick = {
                            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        })
                    } else {
                        AppContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionGate(onGrantClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "BTConnect needs Bluetooth, Location, and Microphone permissions to discover nearby phones, chat, and make voice calls.",
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onGrantClick) {
            Text("Grant Permissions")
        }
    }
}

@Composable
private fun BluetoothOffGate(onEnableClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Bluetooth is turned off.\nPlease turn on Bluetooth to connect with nearby devices.",
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onEnableClick) {
            Text("Turn On Bluetooth")
        }
    }
}

private fun queryFileName(context: Context, uri: Uri): Pair<String, Long> {
    var name = "file_${System.currentTimeMillis()}"
    var size = 0L
    val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIdx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = it.getColumnIndex(OpenableColumns.SIZE)
            if (nameIdx >= 0) name = it.getString(nameIdx) ?: name
            if (sizeIdx >= 0) size = it.getLong(sizeIdx)
        }
    }
    return name to size
}

private fun openFile(context: Context, path: String, mimeType: String?) {
    try {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(context, "File not found on device", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Open with"))
    } catch (e: Exception) {
        Toast.makeText(context, "No app available to open this file", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun AppContent() {
    val context = LocalContext.current
    val connectionState by BluetoothService.connectionState.collectAsState()
    val callState by BluetoothService.callState.collectAsState()
    val messages by BluetoothService.messages.collectAsState()
    val fileProgress by BluetoothService.fileProgress.collectAsState()
    val discovered by BluetoothService.discoveredDevices.collectAsState()
    val isScanning by BluetoothService.isScanning.collectAsState()
    val errorMessage by BluetoothService.errorMessage.collectAsState()

    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(false) }

    val discoverableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* Activity result handled */ }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val (name, size) = queryFileName(context, uri)
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            try {
                val cacheDir = File(context.cacheDir, "sent_files").apply { mkdirs() }
                val cacheFile = File(cacheDir, name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
                var bitmap: Bitmap? = null
                if (mime.startsWith("image/") && cacheFile.exists()) {
                    bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                }
                cacheFile.inputStream().let { stream ->
                    BluetoothService.sendFile(
                        inputStream = stream,
                        name = name,
                        size = cacheFile.length(),
                        mime = mime,
                        localPath = cacheFile.absolutePath,
                        localImage = bitmap
                    )
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error sending file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Start/stop audio VoIP engine during active call
    LaunchedEffect(callState) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (callState is CallState.InCall) {
            AudioCallManager.start(audioManager)
            isMuted = false
            isSpeakerOn = false
        } else {
            AudioCallManager.stop(audioManager)
        }
    }

    // Incoming connection request dialog
    (connectionState as? ConnectionState.AwaitingApproval)?.let { state ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Connection Request") },
            text = { Text("${state.peerName} wants to connect with you via Bluetooth.") },
            confirmButton = {
                Button(onClick = { BluetoothService.approveIncomingConnection() }) {
                    Text("Accept")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { BluetoothService.rejectIncomingConnection() }) {
                    Text("Decline")
                }
            }
        )
    }

    when (val state = connectionState) {
        is ConnectionState.Connected -> {
            ChatScreen(
                peerName = state.peerName,
                peerAddress = state.peerAddress,
                messages = messages,
                fileProgress = fileProgress,
                onBack = { BluetoothService.disconnect() },
                onCallClick = { BluetoothService.startCall() },
                onAttachClick = { filePicker.launch("*/*") },
                onSendText = { BluetoothService.sendText(it) },
                onSendVoiceNote = { file, durationMs -> BluetoothService.sendVoiceNote(file, durationMs) },
                onOpenFile = { path, mime -> openFile(context, path, mime) },
                onClearChat = { BluetoothService.clearMessages() },
                onReconnect = { BluetoothService.reconnect() }
            )
        }
        else -> {
            val statusText = when (state) {
                is ConnectionState.Connecting -> "Connecting to ${state.peerName}…"
                is ConnectionState.AwaitingApproval -> "Waiting for ${state.peerName}…"
                else -> null
            }
            DeviceListScreen(
                myDeviceName = BluetoothService.getDeviceName(),
                pairedDevices = BluetoothService.pairedDevices(),
                nearbyDevices = discovered,
                isScanning = isScanning,
                connectionStatusText = statusText,
                errorMessage = errorMessage,
                onScanClick = { BluetoothService.startDiscovery() },
                onMakeDiscoverableClick = {
                    val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                        putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                    }
                    discoverableLauncher.launch(intent)
                },
                onDeviceClick = { BluetoothService.requestConnection(it) },
                onDismissError = { BluetoothService.clearError() }
            )
        }
    }

    // Full-screen overlay for active/incoming/outgoing calls
    when (val cs = callState) {
        is CallState.Incoming -> CallScreen(
            peerName = cs.peerName,
            mode = CallScreenMode.INCOMING,
            isMuted = isMuted,
            isSpeakerOn = isSpeakerOn,
            callStartedAtMs = null,
            onAccept = { BluetoothService.acceptCall() },
            onReject = { BluetoothService.rejectCall() },
            onEndCall = { BluetoothService.endCall() },
            onToggleMute = { },
            onToggleSpeaker = { }
        )
        is CallState.Outgoing -> CallScreen(
            peerName = cs.peerName,
            mode = CallScreenMode.OUTGOING,
            isMuted = isMuted,
            isSpeakerOn = isSpeakerOn,
            callStartedAtMs = null,
            onAccept = { },
            onReject = { },
            onEndCall = { BluetoothService.endCall() },
            onToggleMute = { },
            onToggleSpeaker = { }
        )
        is CallState.InCall -> {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            CallScreen(
                peerName = cs.peerName,
                mode = CallScreenMode.IN_CALL,
                isMuted = isMuted,
                isSpeakerOn = isSpeakerOn,
                callStartedAtMs = cs.startedAtMs,
                onAccept = { },
                onReject = { },
                onEndCall = { BluetoothService.endCall() },
                onToggleMute = {
                    isMuted = !isMuted
                    AudioCallManager.setMuted(isMuted)
                },
                onToggleSpeaker = {
                    isSpeakerOn = !isSpeakerOn
                    audioManager.isSpeakerphoneOn = isSpeakerOn
                }
            )
        }
        else -> { /* no call in progress */ }
    }
}
