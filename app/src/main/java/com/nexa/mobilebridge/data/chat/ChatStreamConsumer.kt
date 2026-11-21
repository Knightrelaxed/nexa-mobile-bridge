package com.nexa.mobilebridge.data.chat

import com.nexa.mobilebridge.data.local.chat.ChatStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class StreamToken(
    val seq: Int,
    val text: String
)

/**
 * Isolated per-stream token consumer implementing PRD FR-CHAT-04, 05, 06, 07 & Plan 3.C:
 * - One dedicated Channel<StreamToken> per assistant response (never mixes consecutive streams)
 * - Batches incoming tokens every ~33 ms (~30 fps UI ceiling) instead of updating per token
 * - Persists checkpoint to Room every ~5 seconds so process death loses at most 5s of output
 * - Finalizes inside NonCancellable to guarantee no row is ever left stuck in STREAMING
 */
class ChatStreamConsumer(
    private val batchIntervalMs: Long = 33L,
    private val checkpointIntervalMs: Long = 5_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun consume(
        messageId: String,
        streamChannel: Channel<StreamToken>,
        initialDraft: String = "",
        initialLastSeq: Int = 0,
        onUiTextUpdate: (text: String) -> Unit,
        onCheckpoint: suspend (messageId: String, text: String, status: String, lastSeq: Int) -> Unit,
        onFinalize: suspend (messageId: String, text: String, status: String, lastSeq: Int) -> Unit
    ) {
        val sb = StringBuilder(initialDraft)
        var lastSeq = initialLastSeq
        var completed = false
        var lastCheckpointAt = nowMs()

        try {
            for (first in streamChannel) {
                if (first.seq > lastSeq) {
                    sb.append(first.text)
                    lastSeq = first.seq
                }

                if (batchIntervalMs > 0L) {
                    delay(batchIntervalMs)
                }

                // Drain all tokens that arrived within the ~33 ms window
                while (true) {
                    val next = streamChannel.tryReceive().getOrNull() ?: break
                    if (next.seq > lastSeq) {
                        sb.append(next.text)
                        lastSeq = next.seq
                    }
                }

                val currentText = sb.toString()
                onUiTextUpdate(currentText)

                // FR-CHAT-06: Periodic Room checkpoint (~5 seconds)
                val now = nowMs()
                if (now - lastCheckpointAt >= checkpointIntervalMs) {
                    withContext(NonCancellable + ioDispatcher) {
                        onCheckpoint(messageId, currentText, ChatStatus.STREAMING, lastSeq)
                    }
                    lastCheckpointAt = now
                }
            }
            completed = true
        } finally {
            // FR-CHAT-07: Always finalize inside NonCancellable
            val finalStatus = if (completed) ChatStatus.SENT else ChatStatus.INTERRUPTED
            val finalText = sb.toString()
            withContext(NonCancellable + ioDispatcher) {
                onFinalize(messageId, finalText, finalStatus, lastSeq)
            }
            onUiTextUpdate("")
        }
    }
}
