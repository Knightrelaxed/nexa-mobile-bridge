package com.nexa.mobilebridge.data.local.chat

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

object ChatRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val SYSTEM = "system"
}

object ChatStatus {
    const val SENDING = "SENDING"
    const val STREAMING = "STREAMING"
    const val SENT = "SENT"
    const val INTERRUPTED = "INTERRUPTED"
    const val FAILED = "FAILED"
}

/**
 * Offline-first chat message entity (FR-CHAT-02, FR-CHAT-08, Plan Section 3.B).
 * Unique index on (clientMsgId, role) guarantees idempotent deduplication across reconnects.
 */
@Entity(
    tableName = "chat_messages",
    indices = [
        Index(value = ["clientMsgId", "role"], unique = true),
        Index(value = ["timestamp"])
    ]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val clientMsgId: String,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String,
    val lastSeq: Int = 0,
    val intentTag: String? = null,
    val executionMetadata: String? = null
)
