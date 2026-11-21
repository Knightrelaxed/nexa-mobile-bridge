package com.nexa.mobilebridge.data.logs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LogEntry(
    val stableId: Long = 0L,
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
        // Guard against JVM Int shift wraparound (1 shl 32 == 1)
        if (unitId !in 0..7) return false
        val unitBit = 1 shl unitId
        if (preset.unitMask != 0 && (preset.unitMask and unitBit) == 0) return false
        if (preset.minPrio > 0 && prio > preset.minPrio) return false
        if (preset.catMask != 0 && (catFlags and preset.catMask) == 0) return false
        return true
    }
}

/**
 * Crash-resistant fixed-capacity ring buffer (2,000 entries max) for on-demand server logs
 * (FR-LOG-09, Plan Section 3 Pilar 2.D):
 * - Monotonically increasing stableId prevents LazyColumn key collisions on eviction
 * - Thread-safe getOrNull(index) and snapshot() ensure clear() mid-composition never crashes
 */
class CircularLogBuffer(val capacity: Int = 2000) {
    private val buffer = ArrayDeque<LogEntry>(capacity + 1)
    private var nextId: Long = 0L
    private var skippedTotal: Int = 0

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private val _skippedLinesCount = MutableStateFlow(0)
    val skippedLinesCount: StateFlow<Int> = _skippedLinesCount.asStateFlow()

    @Synchronized
    fun push(record: ParsedLogRecord): LogEntry {
        if (buffer.size >= capacity) {
            buffer.removeFirst()
        }
        val assigned = LogEntry(
            stableId = ++nextId,
            prio = record.prio,
            unitId = record.unitId,
            catFlags = record.catFlags,
            timestampMs = record.timestampMs,
            message = record.message
        )
        buffer.addLast(assigned)

        val deltaSkipped = LogBinaryProtocol.extractSkippedLinesCount(record)
        if (deltaSkipped > 0) {
            skippedTotal += deltaSkipped
            _skippedLinesCount.value = skippedTotal
        }

        _version.value++
        return assigned
    }

    @Synchronized
    fun pushBatch(records: List<ParsedLogRecord>) {
        if (records.isEmpty()) return
        for (rec in records) {
            if (buffer.size >= capacity) {
                buffer.removeFirst()
            }
            buffer.addLast(
                LogEntry(
                    stableId = ++nextId,
                    prio = rec.prio,
                    unitId = rec.unitId,
                    catFlags = rec.catFlags,
                    timestampMs = rec.timestampMs,
                    message = rec.message
                )
            )
            val deltaSkipped = LogBinaryProtocol.extractSkippedLinesCount(rec)
            if (deltaSkipped > 0) {
                skippedTotal += deltaSkipped
            }
        }
        _skippedLinesCount.value = skippedTotal
        _version.value++
    }

    @Synchronized
    fun getOrNull(index: Int): LogEntry? = buffer.getOrNull(index)

    @Synchronized
    fun snapshot(): List<LogEntry> = buffer.toList()

    @Synchronized
    fun size(): Int = buffer.size

    @Synchronized
    fun clear() {
        buffer.clear()
        skippedTotal = 0
        _skippedLinesCount.value = 0
        _version.value++
    }
}
