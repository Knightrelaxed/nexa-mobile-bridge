package com.nexa.mobilebridge.data.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StickyModifierMode {
    IDLE,
    ONCE,
    LOCKED;

    fun next(): StickyModifierMode = when (this) {
        IDLE -> ONCE
        ONCE -> LOCKED
        LOCKED -> IDLE
    }
}

data class ModifierSnapshot(
    val ctrl: StickyModifierMode = StickyModifierMode.IDLE,
    val alt: StickyModifierMode = StickyModifierMode.IDLE
)

/**
 * Shared CTRL/ALT sticky modifier state owned by TerminalViewModel and shared
 * between TerminalAccessoryBar and TerminalInputSink (FR-TERM-07, PRD §12.3).
 * Prevents state desync between bar and hidden IME sink.
 */
class ModifierState {
    private val _state = MutableStateFlow(ModifierSnapshot())
    val state: StateFlow<ModifierSnapshot> = _state.asStateFlow()

    @Synchronized
    fun toggleCtrl() {
        val curr = _state.value
        _state.value = curr.copy(ctrl = curr.ctrl.next())
    }

    @Synchronized
    fun toggleAlt() {
        val curr = _state.value
        _state.value = curr.copy(alt = curr.alt.next())
    }

    @Synchronized
    fun resetAll() {
        _state.value = ModifierSnapshot()
    }

    /**
     * Returns (isCtrlActive, isAltActive) and automatically clears ONCE modifiers
     * back to IDLE while keeping LOCKED modifiers active.
     */
    @Synchronized
    fun consume(): Pair<Boolean, Boolean> {
        val curr = _state.value
        val ctrlActive = curr.ctrl != StickyModifierMode.IDLE
        val altActive = curr.alt != StickyModifierMode.IDLE
        val nextCtrl = if (curr.ctrl == StickyModifierMode.ONCE) StickyModifierMode.IDLE else curr.ctrl
        val nextAlt = if (curr.alt == StickyModifierMode.ONCE) StickyModifierMode.IDLE else curr.alt
        if (nextCtrl != curr.ctrl || nextAlt != curr.alt) {
            _state.value = ModifierSnapshot(ctrl = nextCtrl, alt = nextAlt)
        }
        return ctrlActive to altActive
    }

    /**
     * A-M9: Transform typed text into raw PTY bytes applying active CTRL/ALT sticky modifiers
     * (including CTRL+ALT combo, CTRL+Space -> 0x00, CTRL+? -> 0x7F, and full UTF-8 preservation)
     * and normalizing '\n' to '\r' (FR-TERM-06 / FR-TERM-07).
     */
    fun encodeInput(chars: String): ByteArray {
        val (isCtrl, isAlt) = consume()
        val normalized = chars.replace("\r\n", "\r").replace('\n', '\r')
        val baseBytes = if (isCtrl) {
            val out = java.io.ByteArrayOutputStream(normalized.length)
            for (ch in normalized) {
                val upper = ch.uppercaseChar().code
                when {
                    ch == ' ' -> out.write(0x00) // CTRL+Space -> NUL (nano mark)
                    ch == '?' -> out.write(0x7F) // CTRL+? -> DEL
                    ch == '/' -> out.write(0x1F) // CTRL+/ -> US
                    upper in 64..95 -> out.write(upper and 0x1F)
                    else -> out.write(ch.toString().toByteArray(Charsets.UTF_8))
                }
            }
            out.toByteArray()
        } else {
            normalized.toByteArray(Charsets.UTF_8)
        }
        return if (isAlt) byteArrayOf(0x1B) + baseBytes else baseBytes
    }
}
