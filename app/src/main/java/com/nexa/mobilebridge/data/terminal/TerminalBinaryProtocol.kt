package com.nexa.mobilebridge.data.terminal

import com.nexa.mobilebridge.data.logs.LogBinaryProtocol
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

/**
 * Connection B (/io/term) Binary Frame Encoder matching PRD §10.1-10.4
 * and Go sidecar nexa-io/internal/proto/frame.go.
 * All multi-byte integers are Big-Endian.
 */
object TerminalBinaryProtocol {
    const val CH_TERM: Int = LogBinaryProtocol.CH_TERM_DEFAULT // 0x10
    const val INITIAL_TERM_CREDIT_BYTES: Int = 64 * 1024 // 64 KiB (FR-TERM-10)
    const val CREDIT_REPLENISH_THRESHOLD: Int = 32 * 1024 // Half window (32 KiB)

    /**
     * Deterministically map server-issued base64url SID string to exact 16 bytes
     * (matches Go termstream.sidTo16Bytes).
     */
    fun sidTo16Bytes(sid: String): ByteArray {
        return try {
            val decoded = Base64.getUrlDecoder().decode(sid)
            if (decoded.size == 16) {
                decoded
            } else {
                MessageDigest.getInstance("SHA-256")
                    .digest(sid.toByteArray(Charsets.UTF_8))
                    .copyOf(16)
            }
        } catch (_: Exception) {
            MessageDigest.getInstance("SHA-256")
                .digest(sid.toByteArray(Charsets.UTF_8))
                .copyOf(16)
        }
    }

    /**
     * Encode OPEN frame for terminal channel (0x10):
     * [ch: u8][op=0x01][rows: u16 BE][cols: u16 BE][sid: 16 bytes]
     */
    fun encodeOpenFrame(
        rows: Int,
        cols: Int,
        sid16: ByteArray,
        channel: Int = CH_TERM
    ): ByteArray {
        require(sid16.size == 16) { "sid16 must be 16 bytes" }
        val payload = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN).apply {
            putShort((rows.coerceIn(1, 500) and 0xFFFF).toShort())
            putShort((cols.coerceIn(1, 500) and 0xFFFF).toShort())
            put(sid16)
        }.array()
        return LogBinaryProtocol.encodeFrame(channel, LogBinaryProtocol.OP_OPEN, payload)
    }

    /**
     * Encode DATA frame [ch: u8][op=0x02][raw stdin bytes].
     */
    fun encodeDataFrame(data: ByteArray, channel: Int = CH_TERM): ByteArray {
        return LogBinaryProtocol.encodeFrame(channel, LogBinaryProtocol.OP_DATA, data)
    }

    /**
     * Encode RESIZE frame [ch: u8][op=0x05][rows: u16 BE][cols: u16 BE].
     */
    fun encodeResizeFrame(rows: Int, cols: Int, channel: Int = CH_TERM): ByteArray {
        val payload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).apply {
            putShort((rows.coerceIn(1, 500) and 0xFFFF).toShort())
            putShort((cols.coerceIn(1, 500) and 0xFFFF).toShort())
        }.array()
        return LogBinaryProtocol.encodeFrame(channel, LogBinaryProtocol.OP_RESIZE, payload)
    }

    /**
     * Encode RESUME frame [ch: u8][op=0x09][sid: 16 bytes][received_offset: u64 BE] (FR-TERM-11, PRD §10.2).
     */
    fun encodeResumeFrame(
        sid16: ByteArray,
        receivedOffset: Long,
        channel: Int = CH_TERM
    ): ByteArray {
        require(sid16.size == 16) { "sid16 must be 16 bytes" }
        val payload = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {
            put(sid16)
            putLong(receivedOffset)
        }.array()
        return LogBinaryProtocol.encodeFrame(channel, LogBinaryProtocol.OP_RESUME, payload)
    }

    /**
     * Encode CREDIT frame [ch: u8][op=0x03][bytes: u32 BE].
     */
    fun encodeCreditFrame(bytesCredit: Int, channel: Int = CH_TERM): ByteArray {
        return LogBinaryProtocol.encodeCreditFrame(channel, bytesCredit)
    }

    /**
     * Encode CLOSE frame [ch: u8][op=0x04][reason: u8].
     */
    fun encodeCloseFrame(reason: Int = LogBinaryProtocol.CLOSE_CLIENT_EXIT, channel: Int = CH_TERM): ByteArray {
        return LogBinaryProtocol.encodeCloseFrame(channel, reason)
    }

    fun encodePingFrame(): ByteArray = LogBinaryProtocol.encodePingFrame()
}
