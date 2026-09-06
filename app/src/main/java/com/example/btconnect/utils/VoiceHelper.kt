package com.example.btconnect.utils

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startTimeMs: Long = 0L

    var isRecording by mutableStateOf(false)
        private set

    fun start(): Boolean {
        return try {
            val dir = File(context.cacheDir, "voice_recordings").apply { mkdirs() }
            val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")
            currentFile = file
            startTimeMs = System.currentTimeMillis()

            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(64000)
            mr.setAudioSamplingRate(44100)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            isRecording = true
            true
        } catch (e: Exception) {
            cancel()
            false
        }
    }

    fun stop(): Pair<File, Long>? {
        val mr = recorder ?: return null
        val file = currentFile ?: return null
        val duration = System.currentTimeMillis() - startTimeMs
        return try {
            mr.stop()
            mr.release()
            recorder = null
            isRecording = false
            if (duration >= 500 && file.exists() && file.length() > 0) {
                file to duration
            } else {
                file.delete()
                null
            }
        } catch (e: Exception) {
            cancel()
            null
        }
    }

    fun cancel() {
        try {
            recorder?.stop()
        } catch (e: Exception) {}
        try {
            recorder?.release()
        } catch (e: Exception) {}
        recorder = null
        isRecording = false
        currentFile?.delete()
        currentFile = null
    }
}

class VoicePlayer {
    private var player: MediaPlayer? = null
    var currentPlayingPath by mutableStateOf<String?>(null)
        private set

    fun play(path: String, onFinished: () -> Unit = {}) {
        if (currentPlayingPath == path) {
            stop()
            return
        }
        stop()
        try {
            val mp = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                setOnCompletionListener {
                    stop()
                    onFinished()
                }
                start()
            }
            player = mp
            currentPlayingPath = path
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        try {
            player?.stop()
            player?.release()
        } catch (e: Exception) {}
        player = null
        currentPlayingPath = null
    }
}
