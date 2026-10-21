package com.nexa.mobilebridge.ui.call

import com.nexa.mobilebridge.service.ScreenCaptureService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveVoiceTranscriptStateTest {

    @Test
    fun testScreenCaptureServiceInitialSubtitleIsEmpty() {
        // Must NOT be hardcoded to "Halo Tuan Faqih, saya melihat layar Tuan."
        assertEquals("", ScreenCaptureService.assistantSubtitle.value)
    }

    @Test
    fun testCallStatusUpdatePayloadParsing() {
        val jsonPayload = """
            {
                "type": "CALL_STATUS_UPDATE",
                "text": "Halo, terdengar jelas kok! Ada yang bisa saya bantu?",
                "user_transcript": "Cek, halo."
            }
        """.trimIndent()

        val element = Json.parseToJsonElement(jsonPayload).jsonObject
        val text = element["text"]?.jsonPrimitive?.content ?: ""
        val userTranscript = element["user_transcript"]?.jsonPrimitive?.content ?: ""

        assertEquals("Halo, terdengar jelas kok! Ada yang bisa saya bantu?", text)
        assertEquals("Cek, halo.", userTranscript)
    }
}
