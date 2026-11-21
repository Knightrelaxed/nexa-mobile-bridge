package com.nexa.mobilebridge.data.chat

import android.util.Base64
import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.nexa.mobilebridge.NexaBridgeService
import com.nexa.mobilebridge.core.network.NexaWebSocketClient
import com.nexa.mobilebridge.core.util.ConnectionState
import com.nexa.mobilebridge.data.local.chat.ChatMessageDao
import com.nexa.mobilebridge.data.local.chat.ChatMessageEntity
import com.nexa.mobilebridge.data.local.chat.ChatRole
import com.nexa.mobilebridge.data.local.chat.ChatStatus
import com.nexa.mobilebridge.notification.ChatNotificationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class NexaAvatarState {
    IDLE,
    LISTENING,
    THINKING,
    STREAMING,
    STEP_UP,
    ALERT
}

data class ActiveChatStreamState(
    val activeClientMsgId: String? = null,
    val activeAssistantRowId: String? = null,
    val streamingText: String = "",
    val avatarState: NexaAvatarState = NexaAvatarState.IDLE,
    val lastError: String? = null
)

/**
 * Canonical pending Finance Step-Up authorization request from server (FR-AUTH-07, PRD 10.6, 01_chat_ai_native_v2.png).
 */
data class PendingFinanceStepUp(
    val actionId: String,
    val clientMsgId: String,
    val actionLabel: String,
    val amount: Long,
    val formattedAmount: String,
    val recipientFull: String,
    val bank: String,
    val acctLast4: String,
    val whitelisted: Boolean,
    val formattedDailyRemaining: String,
    val canonicalHash: String,
    val nonce: String,
    val expiresAtEpochMs: Long,
    val isSubmitting: Boolean = false,
    val errorBanner: String? = null
)

/**
 * Offline-first Chat Repository combining Room + Paging 3 (maxSize=100),
 * per-stream Channel batching, offline send queue, and seq-based CHAT_RESUME
 * (FR-CHAT-02..12, Plan Section 3.A-C).
 */
@Singleton
class ChatRepository @Inject constructor(
    private val chatMessageDao: ChatMessageDao,
    private val webSocketClient: NexaWebSocketClient,
    private val notificationManager: ChatNotificationManager,
    private val visibilityTracker: ChatVisibilityTracker
) {
    companion object {
        private const val TAG = "ChatRepository"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val ACK_RETRY_DELAY_MS = 4_000L
        private const val VOICE_ACK_RETRY_DELAY_MS = 6_000L
        private const val MAX_VOICE_BYTES = 1_500 * 1024
        private const val STREAM_CHANNEL_CAPACITY = 256
    }

    fun isLiveCallActive(): Boolean = webSocketClient.liveCallListener != null

    private data class ActiveStreamSession(
        val clientMsgId: String,
        val assistantRowId: String,
        val channel: Channel<StreamToken>,
        var consumerJob: Job? = null,
        @Volatile var lastSeq: Int = 0,
        @Volatile var finalIntentTag: String? = null
    )

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streamConsumer = ChatStreamConsumer()
    private val activeStreams = ConcurrentHashMap<String, ActiveStreamSession>()
    private val streamSessionMutex = Mutex()
    private val retryCounts = ConcurrentHashMap<String, Int>()
    private val pendingAckTimers = ConcurrentHashMap<String, Job>()
    private val stoppedIdsLock = Any()
    private val stoppedClientMsgIds = LinkedHashSet<String>()

    private val _streamState = MutableStateFlow(ActiveChatStreamState())
    val streamState: StateFlow<ActiveChatStreamState> = _streamState.asStateFlow()

    private val _pendingStepUp = MutableStateFlow<PendingFinanceStepUp?>(null)
    val pendingStepUp: StateFlow<PendingFinanceStepUp?> = _pendingStepUp.asStateFlow()

    init {
        observeIncomingChatEvents()
        observeConnectionForQueueAndResume()
        // Opening the chat clears the notification shade + unread badge and tells the server it was read
        repoScope.launch {
            visibilityTracker.chatVisible.collect { visible ->
                if (visible && notificationManager.clearAll()) {
                    markChatRead()
                }
            }
        }
        // A-H10: Recover stale STREAMING rows if app starts while offline
        repoScope.launch {
            delay(5_000L)
            if (!webSocketClient.isConnected() && activeStreams.isEmpty()) {
                chatMessageDao.updateAllByRoleAndStatus(
                    role = ChatRole.ASSISTANT,
                    oldStatus = ChatStatus.STREAMING,
                    newStatus = ChatStatus.INTERRUPTED
                )
            }
        }
    }

    /**
     * FR-CHAT-03: Paging 3 with pageSize=20, prefetchDistance=5, enablePlaceholders=false, maxSize=100.
     */
    fun getPagedMessages(): Flow<PagingData<ChatMessageEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = 20,
                prefetchDistance = 5,
                enablePlaceholders = false,
                maxSize = 100
            ),
            pagingSourceFactory = { chatMessageDao.getPagedMessages() }
        ).flow
    }

    /**
     * Persist user message offline-first and transmit CHAT_SEND if WebSocket is online.
     */
    suspend fun sendMessage(
        content: String,
        intentHint: String = "AUTO",
        replyTo: ChatReplyTarget? = null
    ): String {
        val clean = content.trim()
        require(clean.isNotEmpty()) { "Pesan tidak boleh kosong" }

        val clientMsgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val metadataJson = replyTo?.let { ChatReplyMetadata.toJson(it) }

        val userEntity = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            clientMsgId = clientMsgId,
            role = ChatRole.USER,
            content = clean,
            timestamp = now,
            status = ChatStatus.SENDING,
            executionMetadata = metadataJson
        )
        chatMessageDao.insertMessage(userEntity)

        if (webSocketClient.isConnected()) {
            transmitChatSend(clientMsgId, clean, intentHint, replyTo)
        } else {
            _streamState.update {
                it.copy(
                    avatarState = NexaAvatarState.ALERT,
                    lastError = "Offline: pesan masuk antrean kirim otomatis."
                )
            }
        }
        return clientMsgId
    }

    /**
     * Persist voice note offline-first and transmit CHAT_VOICE_SEND if WebSocket is online.
     */
    suspend fun sendVoiceMessage(clientMsgId: String, metadata: VoiceNoteMetadata): String {
        val now = System.currentTimeMillis()
        val label = "Pesan suara (${VoiceNoteWaveform.formatDuration(metadata.durationMs)})"

        val userEntity = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            clientMsgId = clientMsgId,
            role = ChatRole.USER,
            content = label,
            timestamp = now,
            status = ChatStatus.SENDING,
            executionMetadata = metadata.toJson()
        )
        chatMessageDao.insertMessage(userEntity)

        if (webSocketClient.isConnected()) {
            transmitChatVoiceSend(clientMsgId, metadata)
        } else {
            _streamState.update {
                it.copy(
                    avatarState = NexaAvatarState.ALERT,
                    lastError = "Offline: pesan suara masuk antrean kirim otomatis."
                )
            }
        }
        return clientMsgId
    }

    /**
     * FR-CHAT-09: Idempotent manual retry using the exact same clientMsgId.
     */
    suspend fun retryMessage(clientMsgId: String) {
        val userMsg = chatMessageDao.getByClientMsgIdAndRole(clientMsgId, ChatRole.USER) ?: return
        synchronized(stoppedIdsLock) {
            stoppedClientMsgIds.remove(clientMsgId)
        }
        retryCounts[clientMsgId] = 0
        chatMessageDao.updateMessageStatus(userMsg.id, ChatStatus.SENDING)
        if (webSocketClient.isConnected()) {
            val voiceMeta = VoiceNoteMetadata.fromJson(userMsg.executionMetadata)
            if (voiceMeta != null) {
                transmitChatVoiceSend(userMsg.clientMsgId, voiceMeta)
            } else {
                val replyTo = ChatReplyMetadata.fromJson(userMsg.executionMetadata)
                transmitChatSend(userMsg.clientMsgId, userMsg.content, "AUTO", replyTo)
            }
        }
    }

    /**
     * Stop active assistant stream cleanly and finalize local row as INTERRUPTED.
     */
    fun stopActiveStream() {
        val activeId = _streamState.value.activeClientMsgId ?: return
        synchronized(stoppedIdsLock) {
            stoppedClientMsgIds.add(activeId)
            if (stoppedClientMsgIds.size > 64) {
                val iter = stoppedClientMsgIds.iterator()
                if (iter.hasNext()) {
                    iter.next()
                    iter.remove()
                }
            }
        }
        val payload = JSONObject().apply {
            put("type", "CHAT_STOP")
            put("client_msg_id", activeId)
        }.toString()
        webSocketClient.send(payload)

        activeStreams.remove(activeId)?.let { session ->
            session.channel.cancel(CancellationException("Dibatalkan oleh pengguna"))
            session.consumerJob?.cancel()
        }
        _streamState.update {
            it.copy(
                activeClientMsgId = null,
                activeAssistantRowId = null,
                streamingText = "",
                avatarState = NexaAvatarState.IDLE
            )
        }
    }

    fun clearError() {
        _streamState.update { curr ->
            val nextAvatar = when {
                _pendingStepUp.value != null -> NexaAvatarState.STEP_UP
                curr.avatarState == NexaAvatarState.ALERT && curr.activeClientMsgId == null -> NexaAvatarState.IDLE
                else -> curr.avatarState
            }
            curr.copy(lastError = null, avatarState = nextAvatar)
        }
    }

    suspend fun getMessagesByIds(ids: Collection<String>): List<ChatMessageEntity> {
        if (ids.isEmpty()) return emptyList()
        return chatMessageDao.getByIds(ids.toList())
    }

    /**
     * Delete chat rows from this device only (server memory is untouched).
     * Rows still SENDING or STREAMING are never deleted so retry timers and live streams stay consistent.
     * Returns the ids that were actually deleted.
     */
    suspend fun deleteMessages(ids: Collection<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val deletable = chatMessageDao.getByIds(ids.toList()).filter {
            it.status != ChatStatus.SENDING && it.status != ChatStatus.STREAMING
        }
        if (deletable.isEmpty()) return emptySet()

        for (row in deletable) {
            if (row.role == ChatRole.USER) {
                // User and assistant rows share clientMsgId: only user rows own ACK timers and retry counters
                pendingAckTimers.remove(row.clientMsgId)?.cancel()
                retryCounts.remove(row.clientMsgId)
                VoiceNoteMetadata.fromJson(row.executionMetadata)?.let { meta ->
                    try {
                        File(meta.audioPath).takeIf { it.isFile }?.delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed deleting voice file: ${e.message}")
                    }
                }
            }
        }
        val deletedIds = deletable.map { it.id }
        chatMessageDao.deleteByIds(deletedIds)
        return deletedIds.toSet()
    }

    /**
     * Submit ECDSA P-256 hardware-biometric signature for a pending Finance Step-Up action (FR-AUTH-07).
     */
    fun submitActionAuth(
        actionId: String,
        nonce: String,
        deviceId: String,
        signatureDerBytes: ByteArray
    ) {
        val sigB64 = Base64.encodeToString(signatureDerBytes, Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("type", "ACTION_AUTH")
            put("action_id", actionId)
            put("nonce", nonce)
            put("device_id", deviceId)
            put("sig", sigB64)
        }.toString()

        _pendingStepUp.update { curr ->
            if (curr?.actionId == actionId) curr.copy(isSubmitting = true, errorBanner = null) else curr
        }

        val sent = webSocketClient.send(payload)
        if (!sent) {
            _pendingStepUp.update { curr ->
                if (curr?.actionId == actionId) {
                    curr.copy(
                        isSubmitting = false,
                        errorBanner = "Koneksi terputus saat mengirim otorisasi. Periksa jaringan dan coba lagi."
                    )
                } else curr
            }
        }
    }

    /**
     * Cancel a pending Finance Step-Up action explicitly from the Modal Bottom Sheet.
     */
    fun cancelPendingAction(actionId: String, deviceId: String) {
        val payload = JSONObject().apply {
            put("type", "ACTION_CANCEL")
            put("action_id", actionId)
            put("device_id", deviceId)
        }.toString()
        webSocketClient.send(payload)
        _pendingStepUp.update { curr -> if (curr?.actionId == actionId) null else curr }
        notificationManager.cancelStepUp()
        _streamState.update { curr ->
            if (curr.activeClientMsgId == null &&
                (curr.avatarState == NexaAvatarState.STEP_UP || curr.avatarState == NexaAvatarState.ALERT)
            ) {
                curr.copy(
                    avatarState = if (curr.lastError != null) NexaAvatarState.ALERT else NexaAvatarState.IDLE
                )
            } else {
                curr
            }
        }
    }

    /**
     * Expire a pending Finance Step-Up action when the 30-second countdown reaches 0 locally.
     */
    fun expirePendingActionLocally(actionId: String, deviceId: String) {
        val current = _pendingStepUp.value
        if (current == null || current.actionId != actionId) return
        _pendingStepUp.value = null
        notificationManager.cancelStepUp()
        _streamState.update { curr ->
            if (curr.activeClientMsgId == null &&
                (curr.avatarState == NexaAvatarState.STEP_UP || curr.avatarState == NexaAvatarState.ALERT)
            ) {
                curr.copy(
                    avatarState = if (curr.lastError != null) NexaAvatarState.ALERT else NexaAvatarState.IDLE
                )
            } else {
                curr
            }
        }

        val payload = JSONObject().apply {
            put("type", "ACTION_CANCEL")
            put("action_id", actionId)
            put("device_id", deviceId)
            put("reason", "TIMEOUT") // N-L6 / A-M6: Prevent duplicate assistant bubble from server
        }.toString()
        webSocketClient.send(payload)

        repoScope.launch {
            chatMessageDao.insertMessage(
                ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    clientMsgId = "stepup-exp-$actionId-${System.currentTimeMillis()}",
                    role = ChatRole.ASSISTANT,
                    content = "Waktu konfirmasi sidik jari 30 detik untuk aksi #${current.actionId} (${current.formattedAmount} ke ${current.recipientFull}) telah habis. Silakan kirim ulang perintah bila masih ingin melanjutkan.",
                    timestamp = System.currentTimeMillis(),
                    status = ChatStatus.SENT,
                    intentTag = "FINANCE_STEPUP_EXPIRED"
                )
            )
        }
    }

    fun setStepUpError(actionId: String, message: String) {
        _pendingStepUp.update { curr ->
            if (curr?.actionId == actionId) curr.copy(isSubmitting = false, errorBanner = message) else curr
        }
    }

    private suspend fun transmitChatSend(
        clientMsgId: String,
        content: String,
        intentHint: String,
        replyTo: ChatReplyTarget? = null
    ) {
        val telemetry = NexaBridgeService.latestTelemetry.value
        val payload = JSONObject().apply {
            put("type", "CHAT_SEND")
            put("client_msg_id", clientMsgId)
            put("ts", System.currentTimeMillis())
            put("intent_hint", intentHint)
            put("content", content)
            if (replyTo != null) {
                put("reply_to", JSONObject().apply {
                    put("message_id", replyTo.messageId)
                    put("role", replyTo.role)
                    put("preview", replyTo.preview)
                    put("is_voice", replyTo.isVoice)
                })
            }
            if (telemetry != null) {
                put("device_context", JSONObject().apply {
                    put("battery_level", telemetry.battery_level)
                    put("charging", telemetry.battery_charging)
                    put("screen_on", telemetry.screen_on)
                    put("network_type", telemetry.network_type)
                })
            }
        }.toString()

        val sent = webSocketClient.send(payload)
        if (sent) {
            // A-H10: Keep user message in SENDING until server confirms with CHAT_ACK (or first CHAT_CHUNK)
            _streamState.update {
                it.copy(
                    activeClientMsgId = clientMsgId,
                    avatarState = NexaAvatarState.THINKING,
                    lastError = null
                )
            }
            scheduleAckRetry(clientMsgId, content, intentHint, replyTo)
        } else {
            val attempts = (retryCounts[clientMsgId] ?: 0) + 1
            retryCounts[clientMsgId] = attempts
            if (attempts >= MAX_RETRY_ATTEMPTS) {
                pendingAckTimers.remove(clientMsgId)?.cancel()
                chatMessageDao.updateStatusByClientMsgId(clientMsgId, ChatRole.USER, ChatStatus.FAILED)
                _streamState.update {
                    it.copy(
                        avatarState = NexaAvatarState.ALERT,
                        lastError = "Gagal mengirim pesan setelah $MAX_RETRY_ATTEMPTS percobaan."
                    )
                }
            }
        }
    }

    private fun scheduleAckRetry(
        clientMsgId: String,
        content: String,
        intentHint: String,
        replyTo: ChatReplyTarget? = null
    ) {
        pendingAckTimers.remove(clientMsgId)?.cancel()
        pendingAckTimers[clientMsgId] = repoScope.launch {
            delay(ACK_RETRY_DELAY_MS)
            val row = chatMessageDao.getByClientMsgIdAndRole(clientMsgId, ChatRole.USER)
            if (row == null || row.status != ChatStatus.SENDING) {
                pendingAckTimers.remove(clientMsgId)
                return@launch
            }
            val attempts = (retryCounts[clientMsgId] ?: 0) + 1
            retryCounts[clientMsgId] = attempts
            if (attempts >= MAX_RETRY_ATTEMPTS) {
                pendingAckTimers.remove(clientMsgId)
                chatMessageDao.updateStatusByClientMsgId(clientMsgId, ChatRole.USER, ChatStatus.FAILED)
                _streamState.update {
                    it.copy(
                        avatarState = NexaAvatarState.ALERT,
                        lastError = "Server belum mengonfirmasi pesan setelah $MAX_RETRY_ATTEMPTS percobaan."
                    )
                }
            } else if (webSocketClient.isConnected()) {
                transmitChatSend(clientMsgId, content, intentHint, replyTo)
            }
        }
    }

    private fun observeConnectionForQueueAndResume() {
        repoScope.launch {
            webSocketClient.connectionState.collect { state ->
                if (state is ConnectionState.Connected) {
                    flushPendingOfflineQueue()
                    resumeInterruptedStreams()
                }
            }
        }
    }

    private suspend fun transmitChatVoiceSend(clientMsgId: String, metadata: VoiceNoteMetadata) {
        val file = File(metadata.audioPath)
        if (!file.exists() || file.length() < 128L) {
            chatMessageDao.updateStatusByClientMsgId(clientMsgId, ChatRole.USER, ChatStatus.FAILED)
            _streamState.update {
                it.copy(
                    avatarState = NexaAvatarState.ALERT,
                    lastError = "File rekaman suara tidak ditemukan atau terlalu pendek."
                )
            }
            return
        }
        if (file.length() > MAX_VOICE_BYTES) {
            chatMessageDao.updateStatusByClientMsgId(clientMsgId, ChatRole.USER, ChatStatus.FAILED)
            _streamState.update {
                it.copy(
                    avatarState = NexaAvatarState.ALERT,
                    lastError = "Ukuran file pesan suara melebihi batas pengiriman (1.5 MB)."
                )
            }
            return
        }

        val audioB64 = try {
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading voice note file: ${e.message}")
            chatMessageDao.updateStatusByClientMsgId(clientMsgId, ChatRole.USER, ChatStatus.FAILED)
            _streamState.update {
                it.copy(
                    avatarState = NexaAvatarState.ALERT,
                    lastError = "Gagal membaca file rekaman suara."
                )
            }
            return
        }

        val telemetry = NexaBridgeService.latestTelemetry.value
        val payload = JSONObject().apply {
            put("type", "CHAT_VOICE_SEND")
            put("client_msg_id", clientMsgId)
            put("ts", System.currentTimeMillis())
            put("audio_mime", metadata.mimeType)
            put("duration_ms", metadata.durationMs)
            put("audio_b64", audioB64)
            metadata.replyTo?.let { replyTo ->
                put("reply_to", JSONObject().apply {
                    put("message_id", replyTo.messageId)
                    put("role", replyTo.role)
                    put("preview", replyTo.preview)
                    put("is_voice", replyTo.isVoice)
                })
            }
            if (telemetry != null) {
                put("device_context", JSONObject().apply {
                    put("battery_level", telemetry.battery_level)
                    put("charging", telemetry.battery_charging)
                    put("screen_on", telemetry.screen_on)
                    put("network_type", telemetry.network_type)
                })
            }
        }.toString()

        val sent = webSocketClient.send(payload)
        if (sent) {
            _streamState.update {
                it.copy(
                    activeClientMsgId = clientMsgId,
                    avatarState = NexaAvatarState.THINKING,
                    lastError = null
                )
            }
            scheduleVoiceAckRetry(clientMsgId, metadata)
        } else {
            val attempts = (retryCounts[clientMsgId] ?: 0) + 1
            retryCounts[clientMsgId] = attempts
            if (attempts >= MAX_RETRY_ATTEMPTS) {
                pendingAckTimers.remove(clientMsgId)?.cancel()
                chatMessageDao.updateStatusByClientMsgId(clientMsgId, ChatRole.USER, ChatStatus.FAILED)
                _streamState.update {
                    it.copy(
                        avatarState = NexaAvatarState.ALERT,
                        lastError = "Gagal mengirim pesan suara setelah $MAX_RETRY_ATTEMPTS percobaan."
                    )
                }
            }
        }
    }

    private fun scheduleVoiceAckRetry(clientMsgId: String, metadata: VoiceNoteMetadata) {
        pendingAckTimers.remove(clientMsgId)?.cancel()
        pendingAckTimers[clientMsgId] = repoScope.launch {
            delay(VOICE_ACK_RETRY_DELAY_MS)
            val row = chatMessageDao.getByClientMsgIdAndRole(clientMsgId, ChatRole.USER)
            if (row == null || row.status != ChatStatus.SENDING) {
                pendingAckTimers.remove(clientMsgId)
                return@launch
            }
            val attempts = (retryCounts[clientMsgId] ?: 0) + 1
            retryCounts[clientMsgId] = attempts
            if (attempts >= MAX_RETRY_ATTEMPTS) {
                pendingAckTimers.remove(clientMsgId)
                chatMessageDao.updateStatusByClientMsgId(clientMsgId, ChatRole.USER, ChatStatus.FAILED)
                _streamState.update {
                    it.copy(
                        avatarState = NexaAvatarState.ALERT,
                        lastError = "Server belum mengonfirmasi pesan suara setelah $MAX_RETRY_ATTEMPTS percobaan."
                    )
                }
            } else if (webSocketClient.isConnected()) {
                transmitChatVoiceSend(clientMsgId, metadata)
            }
        }
    }

    private suspend fun flushPendingOfflineQueue() {
        val queued = chatMessageDao.getByRoleAndStatus(ChatRole.USER, ChatStatus.SENDING)
        for (msg in queued) {
            val voiceMeta = VoiceNoteMetadata.fromJson(msg.executionMetadata)
            if (voiceMeta != null) {
                transmitChatVoiceSend(msg.clientMsgId, voiceMeta)
            } else {
                val replyTo = ChatReplyMetadata.fromJson(msg.executionMetadata)
                transmitChatSend(msg.clientMsgId, msg.content, "AUTO", replyTo)
            }
        }
    }

    private suspend fun resumeInterruptedStreams() {
        val streamingRows = chatMessageDao.getByRoleAndStatus(ChatRole.ASSISTANT, ChatStatus.STREAMING)
        for (row in streamingRows) {
            ensureStreamSession(row.clientMsgId, existingRow = row)
            val resumePayload = JSONObject().apply {
                put("type", "CHAT_RESUME")
                put("client_msg_id", row.clientMsgId)
                put("last_seq", row.lastSeq)
            }.toString()
            webSocketClient.send(resumePayload)
            Log.i(TAG, "Sent CHAT_RESUME for clientMsgId=${row.clientMsgId} lastSeq=${row.lastSeq}")
        }
    }

    private fun observeIncomingChatEvents() {
        repoScope.launch {
            webSocketClient.incomingChatEvents.collect { rawJson ->
                try {
                    val obj = JSONObject(rawJson)
                    when (obj.optString("type")) {
                        "CHAT_ACK" -> handleChatAck(obj)
                        "CHAT_CHUNK" -> handleChatChunk(obj)
                        "CHAT_RESUME_MISS" -> handleChatResumeMiss(obj)
                        "CHAT_PROACTIVE" -> handleChatProactive(obj)
                        "ACTION_PENDING" -> handleActionPending(obj)
                        "ACTION_RESULT" -> handleActionResult(obj)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling chat WS event: ${e.message}")
                }
            }
        }
    }

    private suspend fun handleChatProactive(obj: JSONObject) {
        val content = obj.optString("content", "").trim()
        if (content.isEmpty()) return
        val clientMsgId = obj.optString("client_msg_id", "")
            .takeIf { it.isNotBlank() }
            ?: "proactive-${System.currentTimeMillis()}"
        val kind = obj.optString("kind", "PROACTIVE").ifBlank { "PROACTIVE" }
        val priority = obj.optString("priority", "P1").ifBlank { "P1" }
        val caughtUp = obj.optBoolean("caught_up", false)
        val rawTs = obj.optLong("ts", System.currentTimeMillis()).takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val ts = if (caughtUp) rawTs else maxOf(rawTs, System.currentTimeMillis())

        // Deduplicate by client_msg_id so reconnect replays never duplicate proactive bubbles
        if (chatMessageDao.getByClientMsgIdAndRole(clientMsgId, ChatRole.ASSISTANT) != null) {
            return
        }

        chatMessageDao.insertMessage(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                clientMsgId = clientMsgId,
                role = ChatRole.ASSISTANT,
                content = content,
                timestamp = ts,
                status = ChatStatus.SENT,
                intentTag = kind
            )
        )
        notificationManager.onIncomingMessage(
            text = content,
            priority = priority,
            isProactive = true,
            silent = isLiveCallActive()
        )
    }

    /** Tells the server the user read/answered the notification (engagement analytics). */
    fun markChatRead() {
        webSocketClient.send(JSONObject().apply { put("type", "CHAT_READ") }.toString())
    }

    private suspend fun handleChatAck(obj: JSONObject) {
        val clientMsgId = obj.optString("client_msg_id").takeIf { it.isNotBlank() } ?: return
        pendingAckTimers.remove(clientMsgId)?.cancel()
        retryCounts.remove(clientMsgId)
        chatMessageDao.updateStatusByClientMsgId(clientMsgId, ChatRole.USER, ChatStatus.SENT)
        ensureStreamSession(clientMsgId)
        _streamState.update {
            it.copy(
                activeClientMsgId = clientMsgId,
                avatarState = NexaAvatarState.THINKING,
                lastError = null
            )
        }
    }

    private suspend fun handleChatChunk(obj: JSONObject) {
        val clientMsgId = obj.optString("client_msg_id").takeIf { it.isNotBlank() } ?: return
        val status = obj.optString("status")
        val seq = obj.optInt("seq", 0)
        val chunkText = obj.optString("chunk", "")

        pendingAckTimers.remove(clientMsgId)?.cancel()
        retryCounts.remove(clientMsgId)

        if (status == "ERROR_ABORT") {
            val wasUserStopped = synchronized(stoppedIdsLock) {
                stoppedClientMsgIds.remove(clientMsgId)
            }
            if (wasUserStopped) {
                // User explicitly pressed Stop: do not show crimson error or spawn ghost assistant row
                activeStreams.remove(clientMsgId)?.let { session ->
                    session.channel.cancel(CancellationException("Dibatalkan oleh pengguna"))
                }
                return
            }

            val errMsg = obj.optString("error", "Stream dibatalkan oleh server.")
            val existingSession = activeStreams.remove(clientMsgId)
            existingSession?.channel?.cancel(CancellationException(errMsg))

            val existingAssistant = chatMessageDao.getByClientMsgIdAndRole(clientMsgId, ChatRole.ASSISTANT)
            if (existingAssistant == null || existingAssistant.content.isBlank()) {
                // Aborted before any assistant reply was generated: mark user message FAILED so manual retry works
                chatMessageDao.updateStatusByClientMsgId(clientMsgId, ChatRole.USER, ChatStatus.FAILED)
            } else {
                chatMessageDao.updateStatusByClientMsgId(clientMsgId, ChatRole.USER, ChatStatus.SENT)
            }

            _streamState.update {
                it.copy(
                    activeClientMsgId = if (it.activeClientMsgId == clientMsgId) null else it.activeClientMsgId,
                    activeAssistantRowId = if (it.activeClientMsgId == clientMsgId) null else it.activeAssistantRowId,
                    streamingText = if (it.activeClientMsgId == clientMsgId) "" else it.streamingText,
                    avatarState = NexaAvatarState.ALERT,
                    lastError = errMsg
                )
            }
            return
        }

        chatMessageDao.updateStatusByClientMsgId(clientMsgId, ChatRole.USER, ChatStatus.SENT)

        val session = ensureStreamSession(clientMsgId) ?: return

        when (status) {
            "STREAMING_CHUNK" -> {
                if (seq > session.lastSeq) {
                    session.lastSeq = seq
                    session.channel.send(StreamToken(seq = seq, text = chunkText))
                    _streamState.update {
                        it.copy(
                            activeClientMsgId = clientMsgId,
                            activeAssistantRowId = session.assistantRowId,
                            avatarState = NexaAvatarState.STREAMING
                        )
                    }
                }
            }

            "STREAM_FINAL" -> {
                if (chunkText.isNotEmpty() && seq > session.lastSeq) {
                    session.lastSeq = seq
                    session.channel.send(StreamToken(seq = seq, text = chunkText))
                }
                val intentTag = obj.optString("intent", "").takeIf { it.isNotBlank() }
                session.finalIntentTag = intentTag
                session.channel.close()
                activeStreams.remove(clientMsgId)
            }
        }
    }

    private suspend fun handleChatResumeMiss(obj: JSONObject) {
        val clientMsgId = obj.optString("client_msg_id").takeIf { it.isNotBlank() } ?: return
        activeStreams.remove(clientMsgId)?.let { session ->
            session.channel.cancel(CancellationException("Buffer server kedaluwarsa"))
        }
        val row = chatMessageDao.getByClientMsgIdAndRole(clientMsgId, ChatRole.ASSISTANT)
        if (row != null && row.status == ChatStatus.STREAMING) {
            chatMessageDao.updateMessageStatus(row.id, ChatStatus.INTERRUPTED)
        }
    }

    private fun handleActionPending(obj: JSONObject) {
        val actionId = obj.optString("action_id").takeIf { it.isNotBlank() } ?: return
        val nonce = obj.optString("nonce").takeIf { it.isNotBlank() } ?: return
        val canonicalHash = obj.optString("canonical_hash").takeIf { it.isNotBlank() } ?: return
        // A-M6: Derive local countdown strictly from ttl_sec (immune to server/client wall-clock skew)
        val ttlSec = obj.optLong("ttl_sec", 30L).coerceIn(5L, 60L)
        val effectiveExp = System.currentTimeMillis() + (ttlSec * 1000L)

        val pending = PendingFinanceStepUp(
            actionId = actionId,
            clientMsgId = obj.optString("client_msg_id", ""),
            actionLabel = obj.optString("action_label", "Transfer Dana"),
            amount = obj.optLong("amount", 0L),
            formattedAmount = obj.optString("formatted_amount", "Rp 0"),
            recipientFull = obj.optString("recipient_full", "Penerima"),
            bank = obj.optString("bank", "Bank"),
            acctLast4 = obj.optString("acct_last4", "0000"),
            whitelisted = obj.optBoolean("whitelisted", false),
            formattedDailyRemaining = obj.optString("formatted_daily_remaining", "Rp 0 / Rp 2.000.000"),
            canonicalHash = canonicalHash,
            nonce = nonce,
            expiresAtEpochMs = effectiveExp,
            isSubmitting = false,
            errorBanner = null
        )
        _pendingStepUp.value = pending
        _streamState.update { it.copy(avatarState = NexaAvatarState.STEP_UP) }
        notificationManager.showStepUpRequired(
            actionLabel = pending.actionLabel,
            formattedAmount = pending.formattedAmount,
            recipient = pending.recipientFull
        )
    }

    private suspend fun handleActionResult(obj: JSONObject) {
        notificationManager.cancelStepUp()
        val actionId = obj.optString("action_id", "")
        val status = obj.optString("status", "UNKNOWN").uppercase()
        val message = obj.optString("message", "").trim()

        _pendingStepUp.update { curr ->
            if (curr == null || curr.actionId == actionId || actionId.isBlank()) null else curr
        }

        _streamState.update {
            it.copy(
                avatarState = if (status == "APPROVED" || status == "CANCELLED") {
                    NexaAvatarState.IDLE
                } else {
                    NexaAvatarState.ALERT
                }
            )
        }

        if (message.isNotEmpty()) {
            chatMessageDao.insertMessage(
                ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    clientMsgId = "stepup-res-$actionId-${System.currentTimeMillis()}",
                    role = ChatRole.ASSISTANT,
                    content = message,
                    timestamp = System.currentTimeMillis(),
                    status = ChatStatus.SENT,
                    intentTag = "FINANCE_STEPUP_$status"
                )
            )
        }
    }

    private suspend fun ensureStreamSession(
        clientMsgId: String,
        existingRow: ChatMessageEntity? = null
    ): ActiveStreamSession? {
        activeStreams[clientMsgId]?.let { return it }

        return streamSessionMutex.withLock {
            activeStreams[clientMsgId]?.let { return@withLock it }

            // A-C4: Check insertMessage return value (-1L on conflict) and reload canonical row so row.id is never a ghost
            val row: ChatMessageEntity = if (existingRow != null) {
                existingRow
            } else {
                val found = chatMessageDao.getByClientMsgIdAndRole(clientMsgId, ChatRole.ASSISTANT)
                if (found != null) {
                    if (found.status == ChatStatus.SENT) {
                        // Already finalized in Room: never spawn a leaked Channel/consumer on duplicate replay
                        return@withLock null
                    }
                    found
                } else {
                    val candidate = ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        clientMsgId = clientMsgId,
                        role = ChatRole.ASSISTANT,
                        content = "",
                        timestamp = System.currentTimeMillis() + 1L,
                        status = ChatStatus.STREAMING,
                        lastSeq = 0
                    )
                    val insertedId = chatMessageDao.insertMessage(candidate)
                    if (insertedId == -1L) {
                        chatMessageDao.getByClientMsgIdAndRole(clientMsgId, ChatRole.ASSISTANT) ?: candidate
                    } else {
                        candidate
                    }
                }
            }

            // A-M6 / NFR-PERF-02: Bounded token channel (256 slots)
            val channel = Channel<StreamToken>(capacity = STREAM_CHANNEL_CAPACITY)
            val session = ActiveStreamSession(
                clientMsgId = clientMsgId,
                assistantRowId = row.id,
                channel = channel,
                lastSeq = row.lastSeq
            )
            activeStreams[clientMsgId] = session

            session.consumerJob = repoScope.launch(Dispatchers.Default) {
                streamConsumer.consume(
                    messageId = row.id,
                    streamChannel = channel,
                    initialDraft = row.content,
                    initialLastSeq = row.lastSeq,
                    onUiTextUpdate = { liveText ->
                        _streamState.update { curr ->
                            // A-C1: Separate empty finalize clear branch so stopped/aborted streams NEVER resurrect into STREAMING
                            if (liveText.isEmpty()) {
                                if (curr.activeAssistantRowId == row.id || curr.activeClientMsgId == clientMsgId) {
                                    curr.copy(
                                        activeClientMsgId = null,
                                        activeAssistantRowId = null,
                                        streamingText = "",
                                        avatarState = if (curr.lastError != null) NexaAvatarState.ALERT else NexaAvatarState.IDLE
                                    )
                                } else {
                                    curr
                                }
                            } else {
                                curr.copy(
                                    activeClientMsgId = clientMsgId,
                                    activeAssistantRowId = row.id,
                                    streamingText = liveText,
                                    avatarState = NexaAvatarState.STREAMING
                                )
                            }
                        }
                    },
                    onCheckpoint = { msgId, text, status, seq ->
                        chatMessageDao.updateStreamProgress(msgId, text, status, seq)
                    },
                    onFinalize = { msgId, text, status, seq ->
                        chatMessageDao.updateStreamProgress(
                            messageId = msgId,
                            finalContent = text,
                            finalStatus = status,
                            lastSeq = seq,
                            intentTag = session.finalIntentTag
                        )
                        // Completed assistant reply while the chat is not on screen -> notification
                        if (status == ChatStatus.SENT && text.isNotBlank()) {
                            notificationManager.onIncomingMessage(
                                text = text,
                                priority = "P1",
                                isProactive = false,
                                silent = isLiveCallActive()
                            )
                        }
                    }
                )
            }

            session
        }
    }
}
