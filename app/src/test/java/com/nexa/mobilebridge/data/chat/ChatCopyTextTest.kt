package com.nexa.mobilebridge.data.chat

import com.nexa.mobilebridge.data.local.chat.ChatMessageEntity
import com.nexa.mobilebridge.data.local.chat.ChatRole
import com.nexa.mobilebridge.data.local.chat.ChatStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCopyTextTest {

    private fun textMsg(
        id: String,
        role: String,
        content: String,
        timestamp: Long
    ) = ChatMessageEntity(
        id = id,
        clientMsgId = "c-$id",
        role = role,
        content = content,
        timestamp = timestamp,
        status = ChatStatus.SENT
    )

    private fun voiceMsg(id: String, timestamp: Long) = ChatMessageEntity(
        id = id,
        clientMsgId = "c-$id",
        role = ChatRole.USER,
        content = "Pesan suara (0:04)",
        timestamp = timestamp,
        status = ChatStatus.SENT,
        executionMetadata = VoiceNoteMetadata(
            audioPath = "/tmp/voice.ogg",
            durationMs = 4_000L,
            waveform = listOf(10, 40, 80)
        ).toJson()
    )

    @Test
    fun `single text message is copied verbatim without sender prefix`() {
        val msg = textMsg("1", ChatRole.ASSISTANT, "  Jadwal hari ini aman.  ", 100L)
        assertTrue(ChatCopyText.isCopyable(msg))
        assertEquals("Jadwal hari ini aman.", ChatCopyText.build(listOf(msg)))
    }

    @Test
    fun `multiple text messages are sorted chronologically with sender labels`() {
        val m2 = textMsg("2", ChatRole.ASSISTANT, "Saldo Mandiri Rp 1.500.000.", 200L)
        val m1 = textMsg("1", ChatRole.USER, "Cek saldo Mandiri", 100L)

        val copied = ChatCopyText.build(listOf(m2, m1))
        assertEquals("Tuan: Cek saldo Mandiri\n\nN.E.X.A: Saldo Mandiri Rp 1.500.000.", copied)
    }

    @Test
    fun `voice notes are skipped during copy and voice-only selection yields empty string`() {
        val voice = voiceMsg("v1", 150L)
        assertFalse(ChatCopyText.isCopyable(voice))
        assertEquals("", ChatCopyText.build(listOf(voice)))

        val reply = textMsg("a1", ChatRole.ASSISTANT, "Laporan diterima, Tuan.", 200L)
        assertEquals("Laporan diterima, Tuan.", ChatCopyText.build(listOf(voice, reply)))
    }
}
