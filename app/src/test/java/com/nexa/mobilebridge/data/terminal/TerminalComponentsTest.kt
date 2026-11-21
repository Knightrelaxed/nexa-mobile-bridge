package com.nexa.mobilebridge.data.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TerminalComponentsTest {

    @Test
    fun modifierStateSupportsSingleShotAndLockedModes() {
        val mod = ModifierState()
        assertEquals(StickyModifierMode.IDLE, mod.state.value.ctrl)

        // IDLE -> ONCE: CTRL+c produces 0x03 and resets to IDLE
        mod.toggleCtrl()
        assertEquals(StickyModifierMode.ONCE, mod.state.value.ctrl)
        assertArrayEquals(byteArrayOf(0x03), mod.encodeInput("c"))
        assertEquals(StickyModifierMode.IDLE, mod.state.value.ctrl)

        // IDLE -> ONCE -> LOCKED: stays LOCKED across multiple inputs
        mod.toggleCtrl()
        mod.toggleCtrl()
        assertEquals(StickyModifierMode.LOCKED, mod.state.value.ctrl)
        assertArrayEquals(byteArrayOf(0x03), mod.encodeInput("c"))
        assertArrayEquals(byteArrayOf(0x04), mod.encodeInput("d"))
        assertEquals(StickyModifierMode.LOCKED, mod.state.value.ctrl)

        // LOCKED -> IDLE
        mod.toggleCtrl()
        assertEquals(StickyModifierMode.IDLE, mod.state.value.ctrl)

        // ALT ONCE prepends ESC (0x1B) and resets
        mod.toggleAlt()
        assertArrayEquals(byteArrayOf(0x1B, 'b'.code.toByte()), mod.encodeInput("b"))
        assertEquals(StickyModifierMode.IDLE, mod.state.value.alt)

        // Newline normalized to CR (\r = 0x0D)
        assertArrayEquals(byteArrayOf(0x0D), mod.encodeInput("\n"))
    }

    @Test
    fun terminalBinaryProtocolMatchesGoBigEndianSpec() {
        val sidRaw = ByteArray(16) { (it + 1).toByte() }
        val sidB64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(sidRaw)
        assertArrayEquals(sidRaw, TerminalBinaryProtocol.sidTo16Bytes(sidB64Url))

        val openFrame = TerminalBinaryProtocol.encodeOpenFrame(rows = 24, cols = 80, sid16 = sidRaw)
        assertEquals(22, openFrame.size)
        assertEquals(0x10, openFrame[0].toInt() and 0xFF)
        assertEquals(0x01, openFrame[1].toInt() and 0xFF)
        assertEquals(24, ((openFrame[2].toInt() and 0xFF) shl 8) or (openFrame[3].toInt() and 0xFF))
        assertEquals(80, ((openFrame[4].toInt() and 0xFF) shl 8) or (openFrame[5].toInt() and 0xFF))

        val resizeFrame = TerminalBinaryProtocol.encodeResizeFrame(rows = 30, cols = 104)
        assertArrayEquals(byteArrayOf(0x10, 0x05, 0x00, 0x1E, 0x00, 0x68), resizeFrame)

        val resumeFrame = TerminalBinaryProtocol.encodeResumeFrame(sid16 = sidRaw, receivedOffset = 4096L)
        assertEquals(26, resumeFrame.size)
        assertEquals(0x10, resumeFrame[0].toInt() and 0xFF)
        assertEquals(0x09, resumeFrame[1].toInt() and 0xFF)
        // Trailing u64 big-endian for 4096 (0x0000000000001000)
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00),
            resumeFrame.copyOfRange(18, 26)
        )
    }

    @Test
    fun vt100EmulatorParsesAnsiColorsAltScreenUtf8AndCapsScrollbackAt1000() {
        val emu = Vt100TerminalEmulator(initialRows = 5, initialCols = 20, maxScrollback = 1000)

        // Feed green bold prompt + split multi-byte UTF-8 bullet ("•" = E2 80 A2)
        val prompt = "\u001B[1;32mnexa\u001B[0m "
        emu.feed(prompt.toByteArray(Charsets.UTF_8))
        emu.feed(byteArrayOf(0xE2.toByte(), 0x80.toByte()))
        emu.feed(byteArrayOf(0xA2.toByte()))

        val snap1 = emu.snapshot()
        assertEquals('n', snap1.grid[0][0].ch)
        assertTrue(snap1.grid[0][0].bold)
        assertEquals(0xFF00E676.toInt(), snap1.grid[0][0].fgArgb)
        assertEquals('•', snap1.grid[0][5].ch)

        // Alternate screen switch (?1049h / ?1049l) preserves primary screen
        emu.feed("\u001B[?1049hHTOP".toByteArray(Charsets.UTF_8))
        val altSnap = emu.snapshot()
        assertTrue(altSnap.isAlternateScreen)
        assertEquals('H', altSnap.grid[0][0].ch)

        emu.feed("\u001B[?1049l".toByteArray(Charsets.UTF_8))
        val backSnap = emu.snapshot()
        assertFalse(backSnap.isAlternateScreen)
        assertEquals('n', backSnap.grid[0][0].ch)

        // Verify scrollback never exceeds 1,000 lines (FR-TERM-09)
        val manyLines = StringBuilder()
        for (i in 1..1250) {
            manyLines.append("line-").append(i).append("\r\n")
        }
        emu.feed(manyLines.toString().toByteArray(Charsets.UTF_8))
        assertEquals(1000, emu.snapshot().scrollbackLines)
    }

    @Test
    fun derivesSidecarTerminalUrlFromCoreWsUrl() {
        assertEquals(
            "wss://nexa.domain-tuan.id/io/term",
            SidecarTerminalClient.deriveTerminalEndpointUrl("wss://nexa.domain-tuan.id/ws")
        )
    }
}
