package com.nexa.mobilebridge.data.logs

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class LogFilterPreset(
    val label: String,
    val unitMask: Int,
    val minPrio: Int,
    val catMask: Int
) {
    ALL("ALL", 0x0F, LogBinaryProtocol.PRIO_DEBUG, LogBinaryProtocol.CAT_NONE),
    FINANCE("FINANCE", 0x0F, LogBinaryProtocol.PRIO_DEBUG, LogBinaryProtocol.CAT_FINANCE),
    BRIDGE("BRIDGE", 0x0F, LogBinaryProtocol.PRIO_DEBUG, LogBinaryProtocol.CAT_BRIDGE),
    ERRORS_ONLY("ERRORS ONLY", 0x0F, LogBinaryProtocol.PRIO_ERROR, LogBinaryProtocol.CAT_NONE)
}

data class ParsedLogRecord(
    val prio: Int,
    val unitId: Int,
    val catFlags: Int,
    val timestampMs: Long,
    val message: String
) {
    val isSynthetic: Boolean
        get() = (catFlags and LogBinaryProtocol.CAT_SYNTHETIC) != 0

    val unitName: String
        get() = LogBinaryProtocol.unitNameFor(unitId)

    val priorityBadge: String
        get() = LogBinaryProtocol.priorityBadgeFor(prio)

    val categoryBadge: String?
        get() = LogBinaryProtocol.categoryBadgeFor(catFlags)

    fun matchesFilter(preset: LogFilterPreset): Boolean {
        if (isSynthetic) return true
        // A-L2: Guard bit-shift wraparound (1 shl 32 == 1 in JVM Int)
        if (unitId !in 0..7) return false
        val unitBit = 1 shl unitId
        if (preset.unitMask != 0 && (preset.unitMask and unitBit) == 0) return false
        if (preset.minPrio > 0 && prio > preset.minPrio) return false
        if (preset.catMask != 0 && (catFlags and preset.catMask) == 0) return false
        return true
    }
}

/**
 * Connection B (/io/logs & /io/term) Binary Frame & 13-Byte Log Record Encoder/Decoder
 * matching PRD §10.1-10.3 and Go sidecar nexa-io/internal/proto/frame.go.
 * All multi-byte integers are Big-Endian.
 */
object LogBinaryProtocol {
    const val CH_CONTROL: Int = 0x00
    const val CH_TELEMETRY: Int = 0x01
    const val CH_CHAT: Int = 0x02
    const val CH_LOGS: Int = 0x03
    const val CH_TERM_DEFAULT: Int = 0x10

    const val OP_OPEN: Int = 0x01
    const val OP_DATA: Int = 0x02
    const val OP_CREDIT: Int = 0x03
    const val OP_CLOSE: Int = 0x04
    const val OP_RESIZE: Int = 0x05
    const val OP_ERR: Int = 0x06
    const val OP_PING: Int = 0x07
    const val OP_PONG: Int = 0x08
    const val OP_RESUME: Int = 0x09

    const val CLOSE_NORMAL: Int = 0
    const val CLOSE_CLIENT_EXIT: Int = 1
    const val CLOSE_IDLE: Int = 2

    const val UNIT_NEXA_SERVER: Int = 0x00
    const val UNIT_CADDY: Int = 0x01
    const val UNIT_SYSTEMD: Int = 0x02
    const val UNIT_NEXA_IO: Int = 0x03

    const val PRIO_ERROR: Int = 3
    const val PRIO_WARN: Int = 4
    const val PRIO_INFO: Int = 6
    const val PRIO_DEBUG: Int = 7

    const val CAT_NONE: Int = 0x00
    const val CAT_FINANCE: Int = 0x01
    const val CAT_BRIDGE: Int = 0x02
    const val CAT_AUTH: Int = 0x04
    const val CAT_SYNTHETIC: Int = 0x80

    const val RECORD_HEADER_BYTES: Int = 13
    const val INITIAL_LOG_CREDIT_BYTES: Int = 32 * 1024 // 32 KiB
    const val CREDIT_REPLENISH_THRESHOLD: Int = 16 * 1024 // Half window (16 KiB)

    private val SKIPPED_COUNT_REGEX = Regex("""(\d+)\s+baris\s+dilewati""")

    fun unitNameFor(unitId: Int): String = when (unitId) {
        UNIT_NEXA_SERVER -> "nexa-server"
        UNIT_CADDY -> "caddy"
        UNIT_SYSTEMD -> "systemd"
        UNIT_NEXA_IO -> "nexa-io"
        else -> "unit-$unitId"
    }

    fun priorityBadgeFor(prio: Int): String = when {
        prio <= PRIO_ERROR -> "ERROR"
        prio == PRIO_WARN -> "WARN"
        prio <= PRIO_INFO -> "INFO"
        else -> "DEBUG"
    }

    /**
     * Separate category slot badge from priority slot badge (Stitch Blueprint v2 Slide 3 / PRD §12.1).
     */
    fun categoryBadgeFor(catFlags: Int): String? = when {
        (catFlags and CAT_SYNTHETIC) != 0 -> "SYNTHETIC"
        (catFlags and CAT_FINANCE) != 0 -> "FINANCE"
        (catFlags and CAT_BRIDGE) != 0 -> "BRIDGE"
        (catFlags and CAT_AUTH) != 0 -> "AUTH"
        else -> null
    }

    fun extractSkippedLinesCount(record: ParsedLogRecord): Int {
        if (!record.isSynthetic) return 0
        val match = SKIPPED_COUNT_REGEX.find(record.message) ?: return 0
        return match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
    }

    /**
     * Pack [ch: u8][op: u8][payload: N bytes].
     */
    fun encodeFrame(channel: Int, opcode: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(2 + payload.size)
        out[0] = (channel and 0xFF).toByte()
        out[1] = (opcode and 0xFF).toByte()
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, out, 2, payload.size)
        }
        return out
    }

    /**
     * Encode OPEN frame for channel 0x03 (LOGS):
     * [ch=0x03][op=0x01][unit_mask: u8][min_prio: u8][cat_mask: u8][tail_lines: u16 BE]
     */
    fun encodeLogOpenFrame(
        unitMask: Int = 0x0F,
        minPrio: Int = PRIO_DEBUG,
        catMask: Int = CAT_NONE,
        tailLines: Int = 100
    ): ByteArray {
        val payload = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN).apply {
            put((unitMask and 0xFF).toByte())
            put((minPrio and 0xFF).toByte())
            put((catMask and 0xFF).toByte())
            putShort((tailLines.coerceIn(0, 500) and 0xFFFF).toShort())
        }.array()
        return encodeFrame(CH_LOGS, OP_OPEN, payload)
    }

    /**
     * Encode CREDIT frame [ch][op=0x03][bytes: u32 BE].
     * Sending bytesCredit = 0 signals protocol-level pause (FR-LOG-10).
     */
    fun encodeCreditFrame(channel: Int = CH_LOGS, bytesCredit: Int): ByteArray {
        val payload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(bytesCredit)
        }.array()
        return encodeFrame(channel, OP_CREDIT, payload)
    }

    /**
     * Encode CLOSE frame [ch][op=0x04][reason: u8].
     */
    fun encodeCloseFrame(channel: Int = CH_LOGS, reason: Int = CLOSE_CLIENT_EXIT): ByteArray {
        return byteArrayOf(
            (channel and 0xFF).toByte(),
            (OP_CLOSE and 0xFF).toByte(),
            (reason and 0xFF).toByte()
        )
    }

    /**
     * Encode PING frame on control channel [0x00][0x07].
     */
    fun encodePingFrame(): ByteArray {
        return byteArrayOf(CH_CONTROL.toByte(), OP_PING.toByte())
    }

    /**
     * Encode single 13-byte header + UTF-8 message log record (used in golden-file tests).
     */
    fun encodeLogRecord(record: ParsedLogRecord): ByteArray {
        val msgBytes = record.message.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(RECORD_HEADER_BYTES + msgBytes.size).order(ByteOrder.BIG_ENDIAN)
        buf.put((record.prio and 0xFF).toByte())
        buf.put((record.unitId and 0xFF).toByte())
        buf.put((record.catFlags and 0xFF).toByte())
        buf.putLong(record.timestampMs)
        buf.putShort((msgBytes.size and 0xFFFF).toShort())
        buf.put(msgBytes)
        return buf.array()
    }

    /**
     * Unpack one or more 13-byte header log records from a channel 0x03 DATA frame payload (PRD §10.3).
     */
    fun decodeLogRecords(payload: ByteArray): List<ParsedLogRecord> {
        val result = ArrayList<ParsedLogRecord>()
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)

        while (buf.remaining() >= RECORD_HEADER_BYTES) {
            val prio = buf.get().toInt() and 0xFF
            val unitId = buf.get().toInt() and 0xFF
            val catFlags = buf.get().toInt() and 0xFF
            val tsMs = buf.long
            val msgLen = buf.short.toInt() and 0xFFFF

            if (msgLen > buf.remaining()) {
                throw IllegalArgumentException("Malformed 13-byte log record: msgLen=$msgLen > remaining=${buf.remaining()}")
            }

            val msgBytes = ByteArray(msgLen)
            buf.get(msgBytes)
            val msg = String(msgBytes, Charsets.UTF_8)

            result.add(
                ParsedLogRecord(
                    prio = prio,
                    unitId = unitId,
                    catFlags = catFlags,
                    timestampMs = tsMs,
                    message = msg
                )
            )
        }

        if (buf.hasRemaining()) {
            throw IllegalArgumentException("Trailing ${buf.remaining()} bytes < 13-byte log record header")
        }
        return result
    }
}
