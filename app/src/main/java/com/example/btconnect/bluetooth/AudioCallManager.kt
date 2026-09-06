package com.example.btconnect.bluetooth

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.LinkedBlockingQueue

/**
 * Streams raw PCM audio over the Bluetooth connection during a call.
 * Uses an isolated playback thread and queue so audio playback never blocks
 * the Bluetooth socket frame reading loop.
 */
object AudioCallManager {

    private const val SAMPLE_RATE = 16000
    private val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val CHUNK_SIZE = 640 // 20ms @ 16kHz 16-bit mono

    private var recordThread: Thread? = null
    private var playThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val playbackQueue = LinkedBlockingQueue<ByteArray>(40)

    @Volatile private var isMuted = false
    @Volatile private var running = false

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    @SuppressLint("MissingPermission")
    fun start(audioManager: AudioManager) {
        if (running) return
        stopRinging()
        running = true
        isMuted = false
        playbackQueue.clear()

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Exception) {}

        val minRecordBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, CHANNEL_IN, ENCODING, minRecordBuf.coerceAtLeast(CHUNK_SIZE * 4)
        )

        val minTrackBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_OUT)
                    .setEncoding(ENCODING)
                    .build()
            )
            .setBufferSizeInBytes(minTrackBuf.coerceAtLeast(CHUNK_SIZE * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            audioTrack?.play()
            audioRecord?.startRecording()
        } catch (e: Exception) {}

        // Dedicated playback consumer thread: takes chunks from queue and writes to audioTrack
        playThread = Thread({
            while (running) {
                try {
                    val chunk = playbackQueue.take()
                    audioTrack?.write(chunk, 0, chunk.size)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {}
            }
        }, "BTConnect-AudioPlay").also { it.start() }

        // Dedicated mic recording thread: captures 20ms chunks and sends them over Bluetooth
        recordThread = Thread({
            val buffer = ByteArray(CHUNK_SIZE)
            while (running) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0 && !isMuted) {
                    BluetoothService.sendAudioChunk(buffer.copyOf(read))
                }
            }
        }, "BTConnect-AudioRecord").also { it.start() }

        // Fast producer: push to queue without blocking Bluetooth reader thread
        BluetoothService.onAudioChunkReceived = { chunk ->
            if (running) {
                if (!playbackQueue.offer(chunk)) {
                    playbackQueue.poll() // drop oldest chunk to maintain low latency
                    playbackQueue.offer(chunk)
                }
            }
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
    }

    fun isMuted(): Boolean = isMuted

    fun stop(audioManager: AudioManager) {
        running = false
        BluetoothService.onAudioChunkReceived = null
        playbackQueue.clear()

        recordThread?.interrupt()
        playThread?.interrupt()
        recordThread = null
        playThread = null

        try { audioRecord?.stop() } catch (e: Exception) {}
        try { audioRecord?.release() } catch (e: Exception) {}
        audioRecord = null

        try { audioTrack?.stop() } catch (e: Exception) {}
        try { audioTrack?.release() } catch (e: Exception) {}
        audioTrack = null

        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        } catch (e: Exception) {}
    }

    fun startRinging(context: Context) {
        try {
            val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context.applicationContext, alertUri)?.apply {
                play()
            }
        } catch (e: Exception) {}

        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            val pattern = longArrayOf(0, 1000, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {}
    }

    fun stopRinging() {
        try { ringtone?.stop() } catch (e: Exception) {}
        ringtone = null
        try { vibrator?.cancel() } catch (e: Exception) {}
        vibrator = null
    }
}
