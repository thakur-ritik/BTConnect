package com.example.btconnect

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.btconnect.bluetooth.AudioCallManager
import com.example.btconnect.bluetooth.BluetoothService
import com.example.btconnect.bluetooth.CallState
import com.example.btconnect.bluetooth.ConnectionState
import com.example.btconnect.ui.screens.CallScreen
import com.example.btconnect.ui.screens.CallScreenMode
import com.example.btconnect.ui.screens.ChatScreen
import com.example.btconnect.ui.screens.DeviceListScreen
import com.example.btconnect.ui.theme.BTConnectTheme

class MainActivity : ComponentActivity() {

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.RECORD_AUDIO
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.RECORD_AUDIO
            )
        }
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
                ) { /* result ignored; UI just re-reads adapter state */ }

                LaunchedEffect(Unit) {
                    if (!permissionsGranted) permissionLauncher.launch(requiredPermissions())
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!permissionsGranted) {
                        PermissionGate(onGrantClick = { permissionLauncher.launch(requiredPermissions()) })
                    } else if (!BluetoothService.isBluetoothEnabled()) {
                        BluetoothOffGate(onEnableClick = {
                            enableBtLauncher.launch(android.content.Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
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
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("BTConnect needs Bluetooth and microphone permissions to find nearby devices and make calls.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGrantClick) { Text("Grant permissions") }
    }
}

@Composable
private fun BluetoothOffGate(onEnableClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Bluetooth is turned off.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onEnableClick) { Text("Turn on Bluetooth") }
    }
}

private fun queryFileName(context: Context, uri: Uri): Pair<String, Long> {
    var name = "file"
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

@Composable
private fun AppContent() {
    val context = LocalContext.current
    val connectionState by BluetoothService.connectionState.collectAsState()
    val callState by BluetoothService.callState.collectAsState()
    val messages by BluetoothService.messages.collectAsState()
    val fileProgress by BluetoothService.fileProgress.collectAsState()
    val discovered by BluetoothService.discoveredDevices.collectAsState()
    val isScanning by BluetoothService.isScanning.collectAsState()

    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val (name, size) = queryFileName(context, uri)
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            context.contentResolver.openInputStream(uri)?.let { stream ->
                BluetoothService.sendFile(stream, name, size, mime)
            }
        }
    }

    // Start/stop the audio pipeline whenever a call becomes active/inactive.
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

    // Incoming connection request dialog.
    (connectionState as? ConnectionState.AwaitingApproval)?.let { state ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Connection request") },
            text = { Text("${state.peerName} wants to connect with you over Bluetooth.") },
            confirmButton = {
                TextButton(onClick = { BluetoothService.approveIncomingConnection() }) { Text("Accept") }
            },
            dismissButton = {
                TextButton(onClick = { BluetoothService.rejectIncomingConnection() }) { Text("Decline") }
            }
        )
    }

    when (val state = connectionState) {
        is ConnectionState.Connected -> {
            ChatScreen(
                peerName = state.peerName,
                messages = messages,
                fileProgress = fileProgress,
                onBack = { BluetoothService.disconnect() },
                onCallClick = { BluetoothService.startCall() },
                onAttachClick = { filePicker.launch("*/*") },
                onSend = { BluetoothService.sendText(it) }
            )
        }
        else -> {
            val statusText = when (state) {
                is ConnectionState.Connecting -> "Connecting to ${state.peerName}…"
                is ConnectionState.AwaitingApproval -> "Incoming request from ${state.peerName}"
                else -> null
            }
            DeviceListScreen(
                pairedDevices = BluetoothService.pairedDevices(),
                nearbyDevices = discovered,
                isScanning = isScanning,
                connectionStatusText = statusText,
                onScanClick = { BluetoothService.startDiscovery() },
                onDeviceClick = { BluetoothService.requestConnection(it) }
            )
        }
    }

    // Call UI floats above whatever screen is currently showing.
    when (val cs = callState) {
        is CallState.Incoming -> CallScreen(
            peerName = cs.peerName, mode = CallScreenMode.INCOMING, isMuted = isMuted, isSpeakerOn = isSpeakerOn,
            callStartedAtMs = null,
            onAccept = { BluetoothService.acceptCall() },
            onReject = { BluetoothService.rejectCall() },
            onEndCall = { BluetoothService.endCall() },
            onToggleMute = { }, onToggleSpeaker = { }
        )
        is CallState.Outgoing -> CallScreen(
            peerName = cs.peerName, mode = CallScreenMode.OUTGOING, isMuted = isMuted, isSpeakerOn = isSpeakerOn,
            callStartedAtMs = null,
            onAccept = { }, onReject = { }, onEndCall = { BluetoothService.endCall() },
            onToggleMute = { }, onToggleSpeaker = { }
        )
        is CallState.InCall -> {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            CallScreen(
                peerName = cs.peerName, mode = CallScreenMode.IN_CALL, isMuted = isMuted, isSpeakerOn = isSpeakerOn,
                callStartedAtMs = cs.startedAtMs,
                onAccept = { }, onReject = { },
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
