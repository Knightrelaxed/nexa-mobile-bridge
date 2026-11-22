package com.nexa.mobilebridge.ui.chat

import com.nexa.mobilebridge.data.chat.InteractiveCardCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3CardsAndCommandsTest {

    @Test
    fun `InteractiveCardCodec round-trips SUMMARY_CARD metrics progress and SEND_PROMPT actions`() {
        val rawBudgetCard = """
            {
              "version": 1,
              "card_id": "budget_monthly_123",
              "card_type": "BUDGET_RECAP",
              "presentation": "SUMMARY_CARD",
              "status": "INFO",
              "title": "Monthly Budget Recap",
              "subtitle": "Within budget target",
              "badge": "On Track",
              "accent": "EMERALD",
              "hero_value": "+Rp 400.000",
              "hero_tone": "INCOME",
              "metrics": [
                {"label": "GLOBAL", "value": "Rp 1.800.000 / Rp 2.250.000 (80%)", "tone": "AMBER", "progress": 80},
                {"label": "MAKAN", "value": "Rp 650.000 / Rp 600.000 (108%)", "tone": "CRIMSON", "progress": 100}
              ],
              "actions": [
                {"id": "prompt_recent", "label": "Recent Transactions", "style": "SECONDARY", "kind": "SEND_PROMPT", "prompt": "cek 10 transaksi terakhir"}
              ]
            }
        """.trimIndent()

        val parsed = InteractiveCardCodec.parseFromJsonString(rawBudgetCard)
        assertNotNull(parsed)
        assertEquals("BUDGET_RECAP", parsed!!.cardType)
        assertEquals("SUMMARY_CARD", parsed.presentation)
        assertEquals(2, parsed.metrics.size)
        assertEquals(80, parsed.metrics[0].progress)
        assertEquals("AMBER", parsed.metrics[0].tone)
        assertEquals("cek 10 transaksi terakhir", parsed.actions[0].prompt)

        val metaJson = InteractiveCardCodec.toExecutionMetadataJson(parsed)
        val roundTripped = InteractiveCardCodec.parseFromExecutionMetadata(metaJson)
        assertNotNull(roundTripped)
        assertEquals(parsed.metrics, roundTripped!!.metrics)
        assertEquals(parsed.actions, roundTripped.actions)
    }

    @Test
    fun `InteractiveCardCodec updates VAULT_METADATA card on CARD_UPDATE`() {
        val initialRaw = """
            {
              "version": 1,
              "card_id": "vault_42",
              "card_type": "VAULT_METADATA",
              "presentation": "CARD",
              "status": "PENDING",
              "title": "KTP Tuan",
              "subtitle": "IDENTITAS · KTP.jpg",
              "badge": "Draft",
              "accent": "AMBER",
              "fields": [
                {"key": "nik", "label": "Nik", "value": "34040001"}
              ],
              "meta": {"vault_row_id": 42, "category": "IDENTITAS"},
              "actions": [
                {"id": "confirm", "label": "Confirm & Save", "style": "PRIMARY"},
                {"id": "edit_fields", "label": "Edit Fields", "style": "SECONDARY", "kind": "EDIT_VAULT"}
              ]
            }
        """.trimIndent()

        val initial = InteractiveCardCodec.parseFromJsonString(initialRaw)!!
        assertEquals("EDIT_VAULT", initial.actions[1].kind)
        assertEquals(42L, initial.metaLong("vault_row_id"))

        val updateFrame = """
            {
              "card_id": "vault_42",
              "status": "RESOLVED",
              "resolved_badge": "Confirmed",
              "resolved_detail": "KTP Tuan saved to Vault (IDENTITAS)."
            }
        """.trimIndent()

        val resolved = InteractiveCardCodec.applyCardUpdateJson(initial, updateFrame)
        assertTrue(resolved.isResolved)
        assertEquals("Confirmed", resolved.resolvedBadge)
    }

    @Test
    fun `filterSlashCommands matches commands live and hides once argument starts`() {
        assertEquals(NEXA_SLASH_COMMANDS.size, filterSlashCommands("/").size)

        val briefMatches = filterSlashCommands("/br")
        assertEquals(1, briefMatches.size)
        assertEquals("/briefing", briefMatches[0].command)

        val budgetMatches = filterSlashCommands("/bu")
        assertEquals(1, budgetMatches.size)
        assertEquals("/budget", budgetMatches[0].command)

        val vaultMatches = filterSlashCommands("/va")
        assertEquals(1, vaultMatches.size)
        assertEquals("/vault", vaultMatches[0].command)

        // Hides when user starts typing command arguments or non-slash text
        assertTrue(filterSlashCommands("/search kopi").isEmpty())
        assertTrue(filterSlashCommands("beli kopi 25rb").isEmpty())
        assertTrue(filterSlashCommands("").isEmpty())
    }

    @Test
    fun `InteractiveCardCodec round-trips FINANCE_TX prompt_text field tone accounts and payment_methods`() {
        val raw = """
            {
              "version": 1,
              "card_id": "fin_9000_unknown",
              "card_type": "FINANCE_TX",
              "status": "PENDING",
              "title": "New Transaction Detected",
              "subtitle": "Details needed",
              "badge": "Expense",
              "accent": "AMBER",
              "hero_value": "-Rp 9.000",
              "hero_tone": "EXPENSE",
              "prompt_text": "Satu hal lagi Tuan, akun bank/dompet dan metode pembayarannya belum dipilih.",
              "fields": [
                {"key": "time", "label": "Time", "value": "22 Nov · 09:01 WIB"},
                {"key": "account", "label": "Account", "value": "Not selected", "tone": "AMBER"},
                {"key": "payment_method", "label": "Method", "value": "Not selected", "tone": "AMBER"}
              ],
              "accounts": ["Bank Mandiri", "Bank Jago", "Tunai"],
              "payment_methods": ["QRIS", "Direct Debit", "Transfer bank", "Tunai"],
              "actions": [
                {"id": "confirm", "label": "Confirm", "style": "PRIMARY"},
                {"id": "account", "label": "Set Account", "style": "SECONDARY", "kind": "PICK_ACCOUNT"},
                {"id": "payment_method", "label": "Set Method", "style": "SECONDARY", "kind": "PICK_METHOD"}
              ]
            }
        """.trimIndent()

        val parsed = InteractiveCardCodec.parseFromJsonString(raw)!!
        assertTrue(parsed.promptText.contains("akun bank/dompet"))
        assertEquals("AMBER", parsed.fields[1].tone)
        assertEquals(listOf("Bank Mandiri", "Bank Jago", "Tunai"), parsed.accounts)
        assertEquals(listOf("QRIS", "Direct Debit", "Transfer bank", "Tunai"), parsed.paymentMethods)

        val roundTripped = InteractiveCardCodec.parseFromExecutionMetadata(
            InteractiveCardCodec.toExecutionMetadataJson(parsed)
        )!!
        assertEquals(parsed.promptText, roundTripped.promptText)
        assertEquals(parsed.accounts, roundTripped.accounts)
        assertEquals(parsed.paymentMethods, roundTripped.paymentMethods)
        assertEquals(parsed.fields, roundTripped.fields)
    }
}
