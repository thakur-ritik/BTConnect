package com.example.btconnect.bluetooth

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder

/**
 * Streams raw PCM audio over the existing Bluetooth connection during a call.
 * No internet or telephony involved - the mic is captured, sent as small
 * chunks over the RFCOMM socket, and played back on the other device.
 */
object AudioCallManager {

    private const val SAMPLE_RATE = 16000
    private val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    @Volatile private var isMuted = false
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start(audioManager: AudioManager) {
        if (running) return
        running = true
        isMuted = false
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        val minRecordBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, CHANNEL_IN, ENCODING, minRecordBuf * 2
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
            .setBufferSizeInBytes(minTrackBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        audioRecord?.startRecording()

        recordThread = Thread {
            val buffer = ByteArray(minRecordBuf.coerceAtLeast(1024))
            while (running) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0 && !isMuted) {
                    BluetoothService.sendAudioChunk(buffer.copyOf(read))
                }
            }
        }.also { it.start() }

        BluetoothService.onAudioChunkReceived = { chunk ->
            audioTrack?.write(chunk, 0, chunk.size)
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
    }

    fun isMuted(): Boolean = isMuted

    fun stop(audioManager: AudioManager) {
        running = false
        BluetoothService.onAudioChunkReceived = null
        recordThread?.join(200)
        recordThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }
}
