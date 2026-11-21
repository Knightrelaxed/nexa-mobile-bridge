package com.nexa.mobilebridge.data.chat

import com.nexa.mobilebridge.data.local.chat.ChatMessageEntity
import com.nexa.mobilebridge.data.local.chat.ChatRole

/**
 * Pure clipboard text builder for the message selection mode.
 * - Voice notes have no transcript in the bubble, so they are skipped.
 * - One text message is copied as-is; several are copied chronologically with a sender label.
 */
object ChatCopyText {

    fun isCopyable(message: ChatMessageEntity): Boolean {
        return message.content.isNotBlank() && VoiceNoteMetadata.fromJson(message.executionMetadata) == null
    }

    fun build(messages: List<ChatMessageEntity>): String {
        val textual = messages.filter { isCopyable(it) }.sortedBy { it.timestamp }
        return when (textual.size) {
            0 -> ""
            1 -> textual[0].content.trim()
            else -> textual.joinToString(separator = "\n\n") { msg ->
                "${senderLabel(msg.role)}: ${msg.content.trim()}"
            }
        }
    }

    private fun senderLabel(role: String): String = if (role == ChatRole.ASSISTANT) "N.E.X.A" else "User"
}
