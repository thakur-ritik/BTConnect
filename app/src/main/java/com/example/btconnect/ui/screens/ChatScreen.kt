@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.btconnect.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.btconnect.model.ChatMessage
import com.example.btconnect.model.MessageKind
import com.example.btconnect.ui.theme.BubbleMine
import com.example.btconnect.ui.theme.BubbleTheirs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    peerName: String,
    messages: List<ChatMessage>,
    fileProgress: Float?,
    onBack: () -> Unit,
    onCallClick: () -> Unit,
    onAttachClick: () -> Unit,
    onSend: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(peerName, fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onCallClick) {
                        Icon(Icons.Filled.Call, contentDescription = "Call", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        },
        bottomBar = {
            Column {
                fileProgress?.let {
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                }
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onAttachClick) {
                            Icon(Icons.Filled.AttachFile, contentDescription = "Attach")
                        }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message") },
                            shape = RoundedCornerShape(24.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            maxLines = 4
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = {
                                if (input.isNotBlank()) {
                                    onSend(input.trim())
                                    input = ""
                                }
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
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
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(message)
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val alignment = if (message.isMine) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (message.isMine) BubbleMine else BubbleTheirs
    val textColor = if (message.isMine) Color.White else Color.Black
    val shape = if (message.isMine)
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    else
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)

    Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .background(bubbleColor, shape)
                .padding(if (message.kind == MessageKind.IMAGE) 4.dp else 12.dp)
        ) {
            when (message.kind) {
                MessageKind.TEXT -> Text(message.text.orEmpty(), color = textColor)
                MessageKind.IMAGE -> {
                    message.image?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = message.fileName,
                            modifier = Modifier
                                .widthIn(max = 240.dp)
                                .clip(RoundedCornerShape(14.dp))
                        )
                    } ?: Text("Photo: ${message.fileName}", color = textColor)
                }
                MessageKind.FILE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.InsertDriveFile, contentDescription = null, tint = textColor)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(message.fileName ?: "File", color = textColor, fontWeight = FontWeight.Medium)
                            Text(
                                formatSize(message.fileSize ?: 0),
                                color = textColor.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                MessageKind.SYSTEM -> Text(message.text.orEmpty(), color = textColor)
            }
            Text(
                timeString(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
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
