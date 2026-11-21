package com.nexa.mobilebridge

import com.nexa.mobilebridge.core.power.BridgePowerLockManager
import com.nexa.mobilebridge.core.security.DeviceKeystoreManager
import com.nexa.mobilebridge.core.security.HmacVerifier
import com.nexa.mobilebridge.data.logs.LogBinaryProtocol
import com.nexa.mobilebridge.data.logs.LogFilterPreset
import com.nexa.mobilebridge.data.logs.ParsedLogRecord
import com.nexa.mobilebridge.data.terminal.ModifierState
import com.nexa.mobilebridge.data.terminal.Vt100TerminalEmulator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditWaveBAndroidCoreTest {

    @Test
    fun `A-C6 Vt100 CSI L and M outside scroll region do not crash with IllegalArgumentException`() {
        val emu = Vt100TerminalEmulator(initialRows = 10, initialCols = 40)
        // Set scroll region rows 2..5 (1-based) -> 1..4 (0-based), then move cursor to row 8 (0-based 7 > scrollBottom 4)
        emu.feed("\u001B[2;5r\u001B[8;1H\u001B[2L\u001B[2M".toByteArray())
        val snap = emu.snapshot()
        assertEquals(10, snap.rows)
        assertEquals(7, snap.cursorRow)
    }

    @Test
    fun `A-H9 Vt100 ignores CSI private prefixes like greater-than without resetting SGR colors and supports ECH and vertical shrink scrollback`() {
        val emu = Vt100TerminalEmulator(initialRows = 6, initialCols = 20)
        // Set foreground to ANSI green (32), then send CSI >4;2m (modifyOtherKeys) which must NOT reset SGR!
        emu.feed("\u001B[32m\u001B[>4;2mG".toByteArray())
        val greenFg = emu.snapshot().grid[0][0].fgArgb
        assertEquals(0xFF00E676.toInt(), greenFg)

        // Test CSI X (ECH - erase 2 chars at cursor without moving cursor)
        emu.feed("\r\nABCD\u001B[2G\u001B[2X".toByteArray())
        val row1 = emu.snapshot().grid[1].take(4).map { it.ch }.joinToString("")
        assertEquals("A  D", row1)

        // Fill 6 rows so cursor is on bottom row 5 ("PROMPT"), then shrink to 4 rows (IME open)
        val emu2 = Vt100TerminalEmulator(initialRows = 6, initialCols = 20)
        emu2.feed("L0\r\nL1\r\nL2\r\nL3\r\nL4\r\nPROMPT".toByteArray())
        assertEquals(5, emu2.snapshot().cursorRow)
        emu2.resize(4, 20)
        val afterShrink = emu2.snapshot()
        val bottomText = afterShrink.grid[afterShrink.cursorRow].take(6).map { it.ch }.joinToString("")
        assertEquals("PROMPT", bottomText)
        assertTrue("Top lines should be pushed into scrollback on vertical shrink", afterShrink.scrollbackLines >= 2)
    }

    @Test
    fun `A-M9 ModifierState supports CTRL+ALT combo and CTRL+Space NUL byte`() {
        val mod = ModifierState()
        mod.toggleCtrl()
        mod.toggleAlt()
        val ctrlAltC = mod.encodeInput("c")
        assertArrayEquals(byteArrayOf(0x1B, 0x03), ctrlAltC)

        mod.toggleCtrl()
        val ctrlSpace = mod.encodeInput(" ")
        assertArrayEquals(byteArrayOf(0x00), ctrlSpace)
    }

    @Test
    fun `A-H12 BridgePowerLockManager extends platform lock timeout when overlapping acquire has longer deadline`() {
        var now = 1_000L
        val timeouts = mutableListOf<Long>()
        val mgr = BridgePowerLockManager(
            acquirePlatformLocks = { timeouts.add(it) },
            releasePlatformLocks = {},
            onLog = {},
            nowElapsedMs = { now }
        )

        mgr.acquire("cmd", 15_000L) // deadline = 16_000
        now = 5_000L
        mgr.acquire("call", 1_800_000L) // deadline = 1_805_000 > 16_000 -> MUST re-acquire with longer timeout!
        assertEquals(2, timeouts.size)
        assertEquals(1_800_000L, timeouts[1])
    }

    @Test
    fun `A-M3 HmacVerifier validates uppercase and lowercase hex safely and rejects invalid signatures`() {
        val ts = 1722780000000L
        val secret = "TEST_SECRET_KEY"
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        }
        val expectedHex = mac.doFinal("${ts}3${secret}".toByteArray()).joinToString("") { "%02x".format(it) }
        assertTrue(HmacVerifier.verify(ts, 3, expectedHex, secret))
        assertTrue(HmacVerifier.verify(ts, 3, expectedHex.uppercase(), secret))
        assertFalse(HmacVerifier.verify(ts, 3, expectedHex.dropLast(1) + "0", secret))
    }

    @Test
    fun `A-L2 ParsedLogRecord matchesFilter rejects out-of-range unitId safely`() {
        val rec = ParsedLogRecord(
            prio = LogBinaryProtocol.PRIO_INFO,
            unitId = 32, // 1 shl 32 wraps to 1 in Kotlin Int! Must be rejected.
            catFlags = LogBinaryProtocol.CAT_NONE,
            timestampMs = 1_000L,
            message = "bad unit"
        )
        assertFalse(rec.matchesFilter(LogFilterPreset.ALL))
    }

    @Test
    fun `GeneralAudit HmacVerifier verifyCommand fails closed on missing signature, stale timestamp, and replay`() {
        HmacVerifier.clearReplayCacheForTest()
        val now = 1_750_000_000_000L
        val secret = "SUPER_SECRET_HMAC"

        fun hmac(data: String): String {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
                init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            }
            return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        // Missing signature MUST fail closed when secret is configured
        assertFalse(
            HmacVerifier.verifyCommand(
                commandId = "cmd-1",
                action = "LOCK_SCREEN",
                timestamp = now,
                level = 3,
                receivedSignature = null,
                secret = secret,
                nowMs = now
            )
        )

        // Valid v2 bound signature succeeds once, then replay is rejected
        val v2Sig = hmac("cmd-2:LOCK_SCREEN:$now:3")
        assertTrue(
            HmacVerifier.verifyCommand(
                commandId = "cmd-2",
                action = "LOCK_SCREEN",
                timestamp = now,
                level = 3,
                receivedSignature = v2Sig,
                secret = secret,
                nowMs = now
            )
        )
        assertFalse(
            "Replayed command_id must be rejected",
            HmacVerifier.verifyCommand(
                commandId = "cmd-2",
                action = "LOCK_SCREEN",
                timestamp = now,
                level = 3,
                receivedSignature = v2Sig,
                secret = secret,
                nowMs = now + 500L
            )
        )

        // Stale timestamp (> 60s skew) must be rejected
        val staleTs = now - 120_000L
        val staleSig = hmac("cmd-3:LOCK_SCREEN:$staleTs:3")
        assertFalse(
            HmacVerifier.verifyCommand(
                commandId = "cmd-3",
                action = "LOCK_SCREEN",
                timestamp = staleTs,
                level = 3,
                receivedSignature = staleSig,
                secret = secret,
                nowMs = now
            )
        )

        // Legacy v1 signature remains accepted for backward compatibility
        val v1Sig = hmac("${now}3${secret}")
        assertTrue(
            HmacVerifier.verifyCommand(
                commandId = "cmd-4",
                action = "PING",
                timestamp = now,
                level = 3,
                receivedSignature = v1Sig,
                secret = secret,
                nowMs = now
            )
        )
    }

    @Test
    fun `GeneralAudit DeviceKeystoreManager canonical action hash matches Node FinanceStepUpService vector`() {
        val hash = DeviceKeystoreManager.computeCanonicalActionHash(
            actionId = "act-test1234",
            actionLabel = "Transfer/Bayar Tagihan",
            amount = 350_000L,
            recipientFull = "PT Telkom Indonesia (IndiHome)",
            bank = "Bank Mandiri",
            acctLast4 = "4882",
            whitelisted = true
        )
        assertEquals("168d7ccfd20c24dff85f1c5257d7cc4db7379aea9620502f8f55fe3d19b0110e", hash)
    }

    @Test
    fun `GeneralAudit ModifierState normalizes Windows CRLF paste into single CR without double Enter`() {
        val mod = ModifierState()
        val encoded = mod.encodeInput("ls -la\r\npwd\n")
        assertArrayEquals("ls -la\rpwd\r".toByteArray(Charsets.UTF_8), encoded)
    }
}
