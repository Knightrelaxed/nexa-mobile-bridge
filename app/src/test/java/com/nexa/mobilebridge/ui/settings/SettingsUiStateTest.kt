package com.nexa.mobilebridge.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUiStateTest {

    @Test
    fun `hasUnsavedServerChanges compares trimmed url token and hmac against saved baseline`() {
        val clean = SettingsUiState(
            serverUrl = "wss://nexa.example.com/ws",
            authToken = "tok-123",
            hmacSecret = "sec-456",
            savedServerUrl = "wss://nexa.example.com/ws",
            savedAuthToken = "tok-123",
            savedHmacSecret = "sec-456"
        )
        assertFalse(clean.hasUnsavedServerChanges)
        assertFalse(clean.copy(serverUrl = "  wss://nexa.example.com/ws  ").hasUnsavedServerChanges)
        assertTrue(clean.copy(serverUrl = "wss://other.example.com/ws").hasUnsavedServerChanges)
        assertTrue(clean.copy(authToken = "tok-999").hasUnsavedServerChanges)
        assertTrue(clean.copy(hmacSecret = "sec-999").hasUnsavedServerChanges)
    }

    @Test
    fun `formatFingerprintPreview truncates 32-octet SHA-256 cleanly to first 4 octets`() {
        assertEquals(
            "df:14:fc:a6...",
            formatFingerprintPreview("df:14:fc:a6:33:8c:3c:67:15:5a")
        )
        assertEquals("Not available", formatFingerprintPreview("   "))
    }

    @Test
    fun `extractDisplayHost strips wss scheme and path cleanly`() {
        assertEquals(
            "nexa-server.indonesiacentral.cloudapp.azure.com",
            extractDisplayHost("wss://nexa-server.indonesiacentral.cloudapp.azure.com/ws")
        )
        assertEquals("Not set", extractDisplayHost(""))
    }
}
