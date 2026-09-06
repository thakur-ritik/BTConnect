@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.btconnect.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.btconnect.model.ChatMessage
import com.example.btconnect.model.MessageKind
import com.example.btconnect.ui.theme.BubbleMine
import com.example.btconnect.ui.theme.BubbleTheirs
import com.example.btconnect.ui.theme.OnlineGreen
import com.example.btconnect.utils.VoicePlayer
import com.example.btconnect.utils.VoiceRecorder
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    peerName: String,
    peerAddress: String = "",
    messages: List<ChatMessage>,
    fileProgress: Float?,
    onBack: () -> Unit,
    onCallClick: () -> Unit,
    onAttachClick: () -> Unit,
    onSendText: (String) -> Unit,
    onSendVoiceNote: (File, Long) -> Unit,
    onOpenFile: (path: String, mime: String?) -> Unit,
    onClearChat: () -> Unit,
    onReconnect: () -> Unit
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var menuExpanded by remember { mutableStateOf(false) }

    val recorder = remember { VoiceRecorder(context) }
    val player = remember { VoicePlayer() }
    var recordingSeconds by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            recorder.cancel()
            player.stop()
        }
    }

    LaunchedEffect(recorder.isRecording) {
        if (recorder.isRecording) {
            recordingSeconds = 0
            while (recorder.isRecording) {
                delay(1000)
                recordingSeconds++
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(peerName, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(OnlineGreen, CircleShape)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Connected • Bluetooth",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onCallClick) {
                        Icon(Icons.Filled.Call, contentDescription = "Call", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Clear Chat") },
                            leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onClearChat()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Reconnect") },
                            leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onReconnect()
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Disconnect") },
                            leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onBack()
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                fileProgress?.let { progress ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Sending file…", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                        )
                    }
                }

                Surface(
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (recorder.isRecording) {
                        RecordingBar(
                            seconds = recordingSeconds,
                            onCancel = { recorder.cancel() },
                            onSend = {
                                val result = recorder.stop()
                                if (result != null) {
                                    onSendVoiceNote(result.first, result.second)
                                } else {
                                    Toast.makeText(context, "Voice note too short", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    } else {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onAttachClick) {
                                Icon(
                                    Icons.Filled.AttachFile,
                                    contentDescription = "Attach File",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Message…") },
                                shape = RoundedCornerShape(24.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(
                                    onSend = {
                                        if (input.isNotBlank()) {
                                            onSendText(input.trim())
                                            input = ""
                                        }
                                    }
                                ),
                                maxLines = 4,
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            if (input.isNotBlank()) {
                                FilledIconButton(
                                    onClick = {
                                        onSendText(input.trim())
                                        input = ""
                                    }
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                                }
                            } else {
                                FilledIconButton(
                                    onClick = {
                                        val started = recorder.start()
                                        if (!started) {
                                            Toast.makeText(context, "Cannot start microphone recording", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                ) {
                                    Icon(Icons.Filled.Mic, contentDescription = "Record Voice Note")
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    player = player,
                    onOpenFile = onOpenFile
                )
            }
        }
    }
}

@Composable
private fun RecordingBar(
    seconds: Int,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "recPulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "recPulse"
    )

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .scale(scale)
                    .background(Color.Red, CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(Modifier.width(10.dp))
            Text("Recording…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Delete, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = onSend) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send Voice Note")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    player: VoicePlayer,
    onOpenFile: (path: String, mime: String?) -> Unit
) {
    val context = LocalContext.current
    val alignment = if (message.isMine) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (message.isMine) BubbleMine else BubbleTheirs
    val textColor = if (message.isMine) Color.White else Color.Black
    val shape = if (message.isMine)
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    else
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)

    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = alignment
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(bubbleColor, shape)
                .combinedClickable(
                    onClick = {
                        if (message.filePath != null) {
                            onOpenFile(message.filePath, message.mimeType)
                        }
                    },
                    onLongClick = {
                        val textToCopy = when {
                            !message.text.isNullOrBlank() -> message.text
                            !message.fileName.isNullOrBlank() -> message.fileName
                            else -> null
                        }
                        if (textToCopy != null) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("BTConnect", textToCopy))
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                .padding(if (message.kind == MessageKind.IMAGE && message.image != null) 4.dp else 12.dp)
        ) {
            when (message.kind) {
                MessageKind.TEXT -> {
                    Text(message.text.orEmpty(), color = textColor, fontSize = 15.sp)
                }

                MessageKind.IMAGE -> {
                    message.image?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = message.fileName,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                        )
                    } ?: run {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Image, contentDescription = null, tint = textColor)
                            Spacer(Modifier.width(8.dp))
                            Text(message.fileName ?: "Photo", color = textColor, fontWeight = FontWeight.Medium)
                        }
                    }
                    if (message.filePath != null) {
                        Text(
                            "Tap to view",
                            color = textColor.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    }
                }

                MessageKind.FILE -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = textColor.copy(alpha = 0.15f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    tint = textColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                message.fileName ?: "File",
                                color = textColor,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                fontSize = 14.sp
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    formatSize(message.fileSize ?: 0),
                                    color = textColor.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                                if (message.filePath != null) {
                                    Text(
                                        " • Tap to open",
                                        color = textColor.copy(alpha = 0.7f),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }

                MessageKind.VOICE_NOTE -> {
                    val isPlaying = player.currentPlayingPath == message.filePath
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(4.dp)
                    ) {
                        IconButton(
                            onClick = {
                                message.filePath?.let { path ->
                                    player.play(path)
                                }
                            },
                            modifier = Modifier
                                .size(42.dp)
                                .background(textColor.copy(alpha = 0.15f), CircleShape)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = textColor
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                if (isPlaying) "Playing audio…" else "Voice note",
                                color = textColor,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp
                            )
                            Text(
                                formatDuration(message.durationMs ?: 0),
                                color = textColor.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                MessageKind.SYSTEM -> {
                    Text(
                        message.text.orEmpty(),
                        color = textColor.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            }

            Text(
                timeString(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 2.dp)
            )
        }
    }
}

private fun timeString(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}

private fun formatDuration(millis: Long): String {
    val sec = (millis / 1000).coerceAtLeast(1)
    val m = sec / 60
    val s = sec % 60
    return String.format(Locale.getDefault(), "%d:%02d", m, s)
}
