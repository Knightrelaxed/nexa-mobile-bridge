package com.nexa.mobilebridge.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceNoteMetadataTest {

    @Test
    fun metadataJsonRoundTripPreservesFieldsAndIgnoresNonVoiceJson() {
        val original = VoiceNoteMetadata(
            audioPath = "/data/user/0/com.nexa.mobilebridge/files/voice_notes/vn_123.ogg",
            durationMs = 14_500L,
            waveform = listOf(12, 48, 85, 30, 100, 16),
            mimeType = VoiceNoteMetadata.MIME_OGG
        )

        val encoded = original.toJson()
        val decoded = VoiceNoteMetadata.fromJson(encoded)

        assertNotNull(decoded)
        assertEquals(original.audioPath, decoded!!.audioPath)
        assertEquals(original.durationMs, decoded.durationMs)
        assertEquals(original.mimeType, decoded.mimeType)
        assertEquals(original.waveform, decoded.waveform)

        assertNull(VoiceNoteMetadata.fromJson(null))
        assertNull(VoiceNoteMetadata.fromJson(""))
        assertNull(VoiceNoteMetadata.fromJson("""{"kind":"other","audio_path":"/tmp/a.ogg"}"""))
    }

    @Test
    fun waveformNormalizationProducesFixed32BarsInSafeRange() {
        val emptyNorm = VoiceNoteWaveform.normalize(emptyList())
        assertEquals(32, emptyNorm.size)
        assertTrue(emptyNorm.all { it in 1..100 })

        val samples = List(120) { idx -> if (idx % 10 == 0) 24_000 else idx * 120 }
        val normalized = VoiceNoteWaveform.normalize(samples)
        assertEquals(32, normalized.size)
        assertTrue(normalized.all { it in 8..100 })
        assertTrue(normalized.max() >= 95)
    }

    @Test
    fun durationFormattingHandlesZeroThroughThreeMinutes() {
        assertEquals("0:00", VoiceNoteWaveform.formatDuration(0L))
        assertEquals("0:09", VoiceNoteWaveform.formatDuration(9_400L))
        assertEquals("1:05", VoiceNoteWaveform.formatDuration(65_000L))
        assertEquals("3:00", VoiceNoteWaveform.formatDuration(180_000L))
    }

    @Test
    fun replyMetadataRoundTripWorksForTextAndVoiceNotes() {
        val reply = ChatReplyTarget(
            messageId = "msg-orig-99",
            role = "assistant",
            preview = "**Saldo** hari ini `Rp 120.000`\naman terkendali.",
            isVoice = false
        )
        val textReplyJson = ChatReplyMetadata.toJson(reply)
        val parsedTextReply = ChatReplyMetadata.fromJson(textReplyJson)
        assertNotNull(parsedTextReply)
        assertEquals("msg-orig-99", parsedTextReply!!.messageId)
        assertEquals("assistant", parsedTextReply.role)
        assertEquals("Saldo hari ini Rp 120.000 aman terkendali.", parsedTextReply.preview)
        assertNull(VoiceNoteMetadata.fromJson(textReplyJson))

        val voiceWithReply = VoiceNoteMetadata(
            audioPath = "/tmp/vn_reply.ogg",
            durationMs = 5_200L,
            waveform = listOf(10, 50, 90),
            replyTo = reply
        )
        val voiceJson = voiceWithReply.toJson()
        val parsedVoice = VoiceNoteMetadata.fromJson(voiceJson)
        val extractedReply = ChatReplyMetadata.fromJson(voiceJson)
        assertNotNull(parsedVoice)
        assertNotNull(extractedReply)
        assertEquals("msg-orig-99", extractedReply!!.messageId)
        assertEquals("msg-orig-99", parsedVoice!!.replyTo!!.messageId)
    }
}
