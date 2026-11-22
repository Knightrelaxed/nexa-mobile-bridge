package com.nexa.mobilebridge

import com.nexa.mobilebridge.data.chat.InteractiveCardCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveCardCodecTest {

    @Test
    fun roundTripFinanceCardAndApplyUpdates() {
        val rawCardJson = """
            {
              "version": 1,
              "card_id": "fin_gmail_99",
              "card_type": "FINANCE_TX",
              "status": "PENDING",
              "title": "New Transaction Detected",
              "subtitle": "Bank Mandiri · QRIS",
              "badge": "Expense",
              "accent": "AMBER",
              "hero_value": "-Rp 45.000",
              "hero_tone": "EXPENSE",
              "expires_at": 1800000000000,
              "expires_label": "Auto-saves in",
              "fields": [
                { "key": "category", "label": "Category", "value": "Jajan / Ngopi / Kafe" }
              ],
              "categories": ["Makan Berat / Makan Luar", "Jajan / Ngopi / Kafe"],
              "actions": [
                { "id": "confirm", "label": "Confirm", "style": "PRIMARY" }
              ],
              "meta": {
                "composite_key": "gmail_99",
                "nominal": 45000,
                "category": "Jajan / Ngopi / Kafe"
              }
            }
        """.trimIndent()

        val parsed = InteractiveCardCodec.parseFromJsonString(rawCardJson)
        assertNotNull(parsed)
        assertEquals("fin_gmail_99", parsed!!.cardId)
        assertTrue(parsed.isPending)
        assertEquals(2, parsed.categories.size)
        assertEquals(45000L, parsed.metaLong("nominal"))
        assertEquals("gmail_99", parsed.metaString("composite_key"))

        val stored = InteractiveCardCodec.toExecutionMetadataJson(parsed)
        val reloaded = InteractiveCardCodec.parseFromExecutionMetadata(stored)
        assertNotNull(reloaded)
        assertEquals(parsed.cardId, reloaded!!.cardId)
        assertEquals("-Rp 45.000", reloaded.heroValue)

        // Apply CARD_UPDATE resolving the card
        val resolvedUpdateJson = """
            {
              "card_id": "fin_gmail_99",
              "status": "RESOLVED",
              "resolved_badge": "Recorded",
              "resolved_detail": "Saved to Bank Mandiri"
            }
        """.trimIndent()
        val resolved = InteractiveCardCodec.applyCardUpdateJson(reloaded, resolvedUpdateJson)
        assertFalse(resolved.isPending)
        assertTrue(resolved.isResolved)
        assertEquals("Recorded", resolved.resolvedBadge)
        assertEquals("Saved to Bank Mandiri", resolved.resolvedDetail)
    }

    @Test
    fun ignoresNonInteractiveExecutionMetadata() {
        val voiceMeta = """{"type":"voice_note","audio_path":"/tmp/a.ogg"}"""
        assertNull(InteractiveCardCodec.parseFromExecutionMetadata(voiceMeta))
        assertNull(InteractiveCardCodec.parseFromExecutionMetadata(null))
        assertNull(InteractiveCardCodec.parseFromExecutionMetadata(""))
    }

    @Test
    fun roundTripQuickActionChipsAndInlineCardPresentations() {
        val chipsJson = """
            {
              "version": 1,
              "card_id": "calconf_123",
              "card_type": "CALENDAR_CONFLICT",
              "presentation": "CHIPS",
              "status": "PENDING",
              "title": "Calendar Conflict",
              "subtitle": "Standup",
              "actions": [
                { "id": "force_create", "label": "Create Anyway", "style": "PRIMARY" },
                { "id": "cancel", "label": "Cancel", "style": "DESTRUCTIVE" }
              ],
              "meta": { "summary": "Standup" }
            }
        """.trimIndent()

        val parsed = InteractiveCardCodec.parseFromJsonString(chipsJson)
        assertNotNull(parsed)
        assertEquals("CHIPS", parsed!!.presentation)
        val reloaded = InteractiveCardCodec.parseFromExecutionMetadata(
            InteractiveCardCodec.toExecutionMetadataJson(parsed)
        )
        assertNotNull(reloaded)
        assertEquals("CHIPS", reloaded!!.presentation)
        assertEquals("Standup", reloaded.metaString("summary"))
    }
}
