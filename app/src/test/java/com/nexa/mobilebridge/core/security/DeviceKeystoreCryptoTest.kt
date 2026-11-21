package com.nexa.mobilebridge.core.security

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceKeystoreCryptoTest {

    @Test
    fun canonicalTicketMessageMatchesServerSpecification() {
        val msg = DeviceKeystoreManager.buildCanonicalTicketMessage(
            nonce = "abc123Nonce",
            scope = "term",
            sid = "sid-9988"
        )
        assertEquals(
            "nexa-ticket-v1\nabc123Nonce\nterm\nsid-9988",
            msg.toString(Charsets.UTF_8)
        )
    }

    @Test
    fun sha256FingerprintIsFull32OctetsFormatted4x8() {
        val sampleBytes = "nexa-p256-spki-sample".toByteArray(Charsets.UTF_8)
        val colonHex = DeviceKeystoreManager.computeSha256ColonHex(sampleBytes)
        val octets = colonHex.split(":")
        assertEquals(32, octets.size)

        val formatted = DeviceKeystoreManager.formatFingerprint4x8(colonHex)
        val lines = formatted.split("\n")
        assertEquals(4, lines.size)
        lines.forEach { line ->
            assertEquals(8, line.split(":").size)
        }
    }
}
