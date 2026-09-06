package com.example.btconnect.model

import android.graphics.Bitmap

enum class MessageKind { TEXT, IMAGE, FILE, VOICE_NOTE, SYSTEM }

data class ChatMessage(
    val id: Long = System.nanoTime(),
    val isMine: Boolean,
    val kind: MessageKind,
    val text: String? = null,
    val image: Bitmap? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val filePath: String? = null,
    val durationMs: Long? = null,
    val mimeType: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
