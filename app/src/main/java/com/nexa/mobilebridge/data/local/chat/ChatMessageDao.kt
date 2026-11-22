package com.nexa.mobilebridge.data.local.chat

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ChatMessageDao {

    // A-M6: Deterministic tie-breaker (rowid DESC) when consecutive messages share the same millisecond timestamp
    @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC, rowid DESC")
    fun getPagedMessages(): PagingSource<Int, ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query(
        """
        UPDATE chat_messages
        SET content = :finalContent,
            status = :finalStatus,
            lastSeq = :lastSeq,
            intentTag = COALESCE(:intentTag, intentTag)
        WHERE id = :messageId
        """
    )
    suspend fun updateStreamProgress(
        messageId: String,
        finalContent: String,
        finalStatus: String,
        lastSeq: Int,
        intentTag: String? = null
    )

    @Query("UPDATE chat_messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)

    @Query("UPDATE chat_messages SET status = :status WHERE clientMsgId = :clientMsgId AND role = :role")
    suspend fun updateStatusByClientMsgId(clientMsgId: String, role: String, status: String)

    @Query("SELECT * FROM chat_messages WHERE clientMsgId = :clientMsgId AND role = :role LIMIT 1")
    suspend fun getByClientMsgIdAndRole(clientMsgId: String, role: String): ChatMessageEntity?

    @Query("SELECT * FROM chat_messages WHERE role = :role AND status = :status ORDER BY timestamp ASC")
    suspend fun getByRoleAndStatus(role: String, status: String): List<ChatMessageEntity>

    // A-H10: Clean up stale STREAMING rows after process crash when offline
    @Query("UPDATE chat_messages SET status = :newStatus WHERE role = :role AND status = :oldStatus")
    suspend fun updateAllByRoleAndStatus(role: String, oldStatus: String, newStatus: String)

    @Query("SELECT * FROM chat_messages WHERE id IN (:ids) ORDER BY timestamp ASC, rowid ASC")
    suspend fun getByIds(ids: List<String>): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query(
        """
        SELECT * FROM chat_messages
        WHERE role = 'assistant' AND executionMetadata LIKE '%' || :cardId || '%'
        ORDER BY timestamp DESC, rowid DESC
        LIMIT 1
        """
    )
    suspend fun findAssistantByCardId(cardId: String): ChatMessageEntity?

    @Query("UPDATE chat_messages SET executionMetadata = :metadata WHERE id = :messageId")
    suspend fun updateExecutionMetadata(messageId: String, metadata: String)
}
