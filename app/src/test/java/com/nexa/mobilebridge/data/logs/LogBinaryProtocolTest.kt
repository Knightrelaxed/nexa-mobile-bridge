package com.nexa.mobilebridge.data.logs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBinaryProtocolTest {

    @Test
    fun goldenLogRecordHeaderMatchesGoSidecarVector() {
        // Golden vector shared with Go nexa-io/internal/proto/frame_test.go:
        // prio=6 (INFO), unit_id=0 (nexa-server), cat_flags=0x01 (FINANCE),
        // timestamp_ms=1763620502000 (0x0000019aa09a29f0),
        // message="Mutasi rekening [ACC-****] Rp500.000 berhasil" (45 bytes)
        val record = ParsedLogRecord(
            prio = LogBinaryProtocol.PRIO_INFO,
            unitId = LogBinaryProtocol.UNIT_NEXA_SERVER,
            catFlags = LogBinaryProtocol.CAT_FINANCE,
            timestampMs = 1763620502000L,
            message = "Mutasi rekening [ACC-****] Rp500.000 berhasil"
        )

        val encoded = LogBinaryProtocol.encodeLogRecord(record)
        assertEquals(13 + 45, encoded.size)

        val headerHex = encoded.take(13).joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
        assertEquals("0600010000019a9ff939f0002d", headerHex)

        // Pack 2 records into one DATA payload and decode
        val record2 = ParsedLogRecord(
            prio = LogBinaryProtocol.PRIO_WARN,
            unitId = LogBinaryProtocol.UNIT_NEXA_IO,
            catFlags = LogBinaryProtocol.CAT_SYNTHETIC,
            timestampMs = 1763620460000L,
            message = "--- 42 baris dilewati karena lonjakan data ---"
        )
        val combined = encoded + LogBinaryProtocol.encodeLogRecord(record2)
        val decoded = LogBinaryProtocol.decodeLogRecords(combined)

        assertEquals(2, decoded.size)
        assertEquals(record, decoded[0])
        assertEquals("INFO", decoded[0].priorityBadge)
        assertEquals("FINANCE", decoded[0].categoryBadge)
        assertEquals("nexa-server", decoded[0].unitName)

        assertEquals(record2, decoded[1])
        assertTrue(decoded[1].isSynthetic)
        assertEquals("WARN", decoded[1].priorityBadge)
        assertEquals("SYNTHETIC", decoded[1].categoryBadge)
        assertEquals(42, LogBinaryProtocol.extractSkippedLinesCount(decoded[1]))
    }

    @Test
    fun openCreditAndCloseFramesFollowBigEndianSpec() {
        val openFrame = LogBinaryProtocol.encodeLogOpenFrame(
            unitMask = 0x0F,
            minPrio = LogBinaryProtocol.PRIO_INFO,
            catMask = LogBinaryProtocol.CAT_FINANCE,
            tailLines = 100
        )
        assertArrayEquals(
            byteArrayOf(0x03, 0x01, 0x0F, 0x06, 0x01, 0x00, 0x64),
            openFrame
        )

        val creditFrame = LogBinaryProtocol.encodeCreditFrame(
            channel = LogBinaryProtocol.CH_LOGS,
            bytesCredit = 32768
        )
        assertArrayEquals(
            byteArrayOf(0x03, 0x03, 0x00, 0x00, 0x80.toByte(), 0x00),
            creditFrame
        )

        val closeFrame = LogBinaryProtocol.encodeCloseFrame(
            channel = LogBinaryProtocol.CH_LOGS,
            reason = LogBinaryProtocol.CLOSE_CLIENT_EXIT
        )
        assertArrayEquals(byteArrayOf(0x03, 0x04, 0x01), closeFrame)
    }

    @Test
    fun circularLogBufferEvictsOldestAtCapacityAndSurvivesClearSafely() {
        val ring = CircularLogBuffer(capacity = 5)
        for (i in 1..7) {
            ring.push(
                ParsedLogRecord(
                    prio = LogBinaryProtocol.PRIO_INFO,
                    unitId = LogBinaryProtocol.UNIT_NEXA_SERVER,
                    catFlags = LogBinaryProtocol.CAT_BRIDGE,
                    timestampMs = 1000L + i,
                    message = "Line #$i"
                )
            )
        }

        assertEquals(5, ring.size())
        // Oldest 2 (Line #1, Line #2) evicted; index 0 is now Line #3 with stableId = 3
        val first = ring.getOrNull(0)
        val last = ring.getOrNull(4)
        assertEquals("Line #3", first?.message)
        assertEquals(3L, first?.stableId)
        assertEquals("Line #7", last?.message)
        assertEquals(7L, last?.stableId)

        // Synthetic record increments skippedLinesCount
        ring.push(
            ParsedLogRecord(
                prio = LogBinaryProtocol.PRIO_WARN,
                unitId = LogBinaryProtocol.UNIT_NEXA_IO,
                catFlags = LogBinaryProtocol.CAT_SYNTHETIC,
                timestampMs = 2000L,
                message = "--- 42 baris dilewati karena lonjakan data ---"
            )
        )
        assertEquals(42, ring.skippedLinesCount.value)

        // Clear buffer and verify getOrNull never throws IndexOutOfBoundsException (FR-LOG-09)
        ring.clear()
        assertEquals(0, ring.size())
        assertEquals(0, ring.skippedLinesCount.value)
        assertNull(ring.getOrNull(0))
    }

    @Test
    fun derivesSidecarLogsUrlFromCoreWsUrl() {
        assertEquals(
            "wss://nexa.domain-tuan.id/io/logs",
            SidecarLogsClient.deriveLogsEndpointUrl("wss://nexa.domain-tuan.id/ws")
        )
        assertEquals(
            "wss://nexa.domain-tuan.id:443/io/logs",
            SidecarLogsClient.deriveLogsEndpointUrl("wss://nexa.domain-tuan.id:443/ws/")
        )
    }

    @Test
    fun multiCategoryFinanceStepUpMatchesFinanceFilterAndGuardsUnitId() {
        val stepUpEntry = LogEntry(
            stableId = 1L,
            prio = LogBinaryProtocol.PRIO_ERROR,
            unitId = LogBinaryProtocol.UNIT_NEXA_SERVER,
            catFlags = LogBinaryProtocol.CAT_FINANCE or LogBinaryProtocol.CAT_AUTH,
            timestampMs = 1763620502000L,
            message = "[NEXA-AUTH] ACTION_REJECTED recipient not whitelisted"
        )
        assertEquals("FINANCE", stepUpEntry.categoryBadge)
        assertTrue(stepUpEntry.matchesFilter(LogFilterPreset.CORE))
        assertTrue(stepUpEntry.matchesFilter(LogFilterPreset.AUTH))
        assertTrue(stepUpEntry.matchesFilter(LogFilterPreset.ERRORS_ONLY))
        assertFalse(stepUpEntry.matchesFilter(LogFilterPreset.BRIDGE))

        val warnEntry = LogEntry(
            stableId = 2L,
            prio = LogBinaryProtocol.PRIO_WARN,
            unitId = LogBinaryProtocol.UNIT_NEXA_SERVER,
            catFlags = LogBinaryProtocol.CAT_FINANCE,
            timestampMs = 1763620503000L,
            message = "[FINANCE] Recovery resend failed"
        )
        assertTrue(warnEntry.matchesFilter(LogFilterPreset.CORE))
        assertFalse(warnEntry.matchesFilter(LogFilterPreset.ERRORS_ONLY))
        assertFalse(warnEntry.matchesFilter(LogFilterPreset.AUTH))

        val coreEntry = LogEntry(
            stableId = 4L,
            prio = LogBinaryProtocol.PRIO_INFO,
            unitId = LogBinaryProtocol.UNIT_NEXA_SERVER,
            catFlags = LogBinaryProtocol.CAT_CORE,
            timestampMs = 1763620505000L,
            message = "[ROUTER] Calendar & Gmail tools dispatched"
        )
        assertEquals("CORE", coreEntry.categoryBadge)
        assertTrue(coreEntry.matchesFilter(LogFilterPreset.CORE))
        assertFalse(coreEntry.matchesFilter(LogFilterPreset.BRIDGE))
        assertFalse(coreEntry.matchesFilter(LogFilterPreset.AUTH))

        val badUnit = LogEntry(
            stableId = 3L,
            prio = LogBinaryProtocol.PRIO_ERROR,
            unitId = 32, // 1 shl 32 wraps to 1 in JVM Int without guard
            catFlags = LogBinaryProtocol.CAT_FINANCE,
            timestampMs = 1763620504000L,
            message = "Corrupted unit"
        )
        assertFalse(badUnit.matchesFilter(LogFilterPreset.ALL))
    }
}
