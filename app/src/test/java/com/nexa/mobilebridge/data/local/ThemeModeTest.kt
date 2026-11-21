package com.nexa.mobilebridge.data.local

import com.nexa.mobilebridge.NexaBridgeService
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {

    @Test
    fun fromStoredParsesKnownModesAndDefaultsToDark() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromStored("DARK"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStored("light"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored("System"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStored(null))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStored("unknown"))
    }

    @Test
    fun accentPresetFromStoredParsesAllFourSwatchesAndDefaultsToCyan() {
        assertEquals(AccentPreset.CYAN, AccentPreset.fromStored("CYAN"))
        assertEquals(AccentPreset.EMERALD, AccentPreset.fromStored("emerald"))
        assertEquals(AccentPreset.AMBER, AccentPreset.fromStored("Amber"))
        assertEquals(AccentPreset.VIOLET, AccentPreset.fromStored("VIOLET"))
        assertEquals(AccentPreset.CYAN, AccentPreset.fromStored(null))
        assertEquals(AccentPreset.CYAN, AccentPreset.fromStored("invalid"))
    }

    @Test
    fun sensitiveCommandsAreMaskedInCommandHistory() {
        val sensitiveCmd = NexaCommand(
            type = "COMMAND",
            command_id = "cmd-fin-1",
            action = "FINANCE_AUTH_VERIFY"
        )
        val okRes = CommandResult.success("cmd-fin-1", "Rp 350.000 Telkom")

        NexaBridgeService.recordCommandResult(sensitiveCmd, okRes, durationMs = 42L)
        val latest = NexaBridgeService.commandHistory.value.first()

        assertEquals("FINANCE_AUTH_VERIFY", latest.action)
        assertEquals("[Redacted]", latest.summary)
        assertTrue(latest.isSensitive)
        assertTrue(latest.isSuccess)
        assertEquals(42L, latest.durationMs)

        val normalCmd = NexaCommand(
            type = "COMMAND",
            command_id = "cmd-bat-2",
            action = "GET_BATTERY_STATUS"
        )
        NexaBridgeService.recordCommandResult(normalCmd, CommandResult.success("cmd-bat-2", "84%"), durationMs = 12L)
        val latestNormal = NexaBridgeService.commandHistory.value.first()

        assertFalse(latestNormal.isSensitive)
        assertEquals("84%", latestNormal.summary)
    }
}
