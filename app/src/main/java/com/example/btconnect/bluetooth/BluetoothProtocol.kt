package com.example.btconnect.bluetooth

import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * Every message sent over the Bluetooth RFCOMM socket is framed as:
 *   [1 byte type][4 byte big-endian length][payload bytes]
 *
 * This lets a single socket carry text chat, file chunks, call control
 * messages and live audio chunks, all multiplexed on one stream.
 */
object BluetoothProtocol {

    // App-specific UUIDs both sides use to find the RFCOMM channel.
    val APP_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    val INSECURE_APP_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")

    const val TYPE_CONNECT_REQUEST: Int = 1
    const val TYPE_CONNECT_ACCEPT: Int = 2
    const val TYPE_CONNECT_REJECT: Int = 3

    const val TYPE_TEXT: Int = 4

    const val TYPE_FILE_META: Int = 5   // payload: "name|size|mime"
    const val TYPE_FILE_CHUNK: Int = 6  // payload: raw bytes
    const val TYPE_FILE_END: Int = 7

    const val TYPE_CALL_REQUEST: Int = 8
    const val TYPE_CALL_ACCEPT: Int = 9
    const val TYPE_CALL_REJECT: Int = 10
    const val TYPE_CALL_END: Int = 11

    const val TYPE_AUDIO_CHUNK: Int = 12
    const val TYPE_VOICE_NOTE: Int = 13
    const val TYPE_DISCONNECT: Int = 14

    /**
     * Thread-safe frame write. Synchronized so chat / file / audio threads can share one socket.
     * Swallows IOExceptions (e.g. the peer disconnected mid-write) so a dropped connection never
     * crashes the caller - the read loop on the other side will notice the closed socket and
     * reset connection state on its own.
     */
    @Synchronized
    fun writeFrame(out: DataOutputStream, type: Int, payload: ByteArray) {
        try {
            out.writeByte(type)
            out.writeInt(payload.size)
            if (payload.isNotEmpty()) out.write(payload)
            out.flush()
        } catch (e: Exception) {
            // Connection dropped - ignore, the read loop will handle cleanup.
        }
    }

    fun writeFrame(out: DataOutputStream, type: Int, text: String) {
        writeFrame(out, type, text.toByteArray(Charsets.UTF_8))
    }

    /** Blocking read of exactly one frame. Returns null on stream close/error. */
    fun readFrame(input: DataInputStream): Frame? {
        return try {
            val type = input.readUnsignedByte()
            val length = input.readInt()
            val payload = ByteArray(length)
            if (length > 0) input.readFully(payload)
            Frame(type, payload)
        } catch (e: Exception) {
            null
        }
    }

    data class Frame(val type: Int, val payload: ByteArray) {
        fun text(): String = String(payload, Charsets.UTF_8)
    }
}
