package com.nexa.mobilebridge.data.chat

import com.nexa.mobilebridge.core.security.DeviceKeystoreManager
import com.nexa.mobilebridge.data.local.chat.ChatStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatStreamConsumerTest {

    @Test
    fun batchesRapidTokensAt33msAndFinalizesAsSent() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val consumer = ChatStreamConsumer(
            batchIntervalMs = 33L,
            checkpointIntervalMs = 5_000L,
            nowMs = { testScheduler.currentTime },
            ioDispatcher = dispatcher
        )

        val channel = Channel<StreamToken>(Channel.UNLIMITED)
        val uiUpdates = mutableListOf<String>()
        var finalizedText = ""
        var finalizedStatus = ""
        var finalizedSeq = 0

        val job = launch(dispatcher) {
            consumer.consume(
                messageId = "row-1",
                streamChannel = channel,
                onUiTextUpdate = { if (it.isNotEmpty()) uiUpdates.add(it) },
                onCheckpoint = { _, _, _, _ -> },
                onFinalize = { _, text, status, seq ->
                    finalizedText = text
                    finalizedStatus = status
                    finalizedSeq = seq
                }
            )
        }

        // Send 40 rapid tokens at t=0
        for (i in 1..40) {
            channel.send(StreamToken(seq = i, text = "t$i "))
        }
        channel.close()
        advanceUntilIdle()
        job.join()

        // Must be aggressively batched (far fewer UI updates than 40 tokens)
        assertTrue("Expected batched UI updates < 5, got ${uiUpdates.size}", uiUpdates.size < 5)
        assertEquals(ChatStatus.SENT, finalizedStatus)
        assertEquals(40, finalizedSeq)
        assertTrue(finalizedText.startsWith("t1 "))
        assertTrue(finalizedText.endsWith("t40 "))
    }

    @Test
    fun persistsPeriodicCheckpointEvery5SecondsDuringLongStream() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val consumer = ChatStreamConsumer(
            batchIntervalMs = 33L,
            checkpointIntervalMs = 5_000L,
            nowMs = { testScheduler.currentTime },
            ioDispatcher = dispatcher
        )

        val channel = Channel<StreamToken>(Channel.UNLIMITED)
        val checkpoints = mutableListOf<Triple<String, String, Int>>()

        val job = launch(dispatcher) {
            consumer.consume(
                messageId = "row-long",
                streamChannel = channel,
                onUiTextUpdate = {},
                onCheckpoint = { _, text, status, seq ->
                    checkpoints.add(Triple(text, status, seq))
                },
                onFinalize = { _, _, _, _ -> }
            )
        }

        channel.send(StreamToken(1, "Halo "))
        advanceTimeBy(2_500L)
        assertEquals(0, checkpoints.size)

        advanceTimeBy(2_600L) // t > 5000ms
        channel.send(StreamToken(2, "Tuan!"))
        advanceTimeBy(100L)
        assertEquals(1, checkpoints.size)
        assertEquals(ChatStatus.STREAMING, checkpoints[0].second)
        assertEquals("Halo Tuan!", checkpoints[0].first)
        assertEquals(2, checkpoints[0].third)

        channel.close()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun finalizesAsInterruptedInsideNonCancellableWhenCancelledMidStream() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val consumer = ChatStreamConsumer(
            batchIntervalMs = 33L,
            checkpointIntervalMs = 5_000L,
            nowMs = { testScheduler.currentTime },
            ioDispatcher = dispatcher
        )

        val channel = Channel<StreamToken>(Channel.UNLIMITED)
        var finalText = ""
        var finalStatus = ""
        var finalSeq = 0

        val job = launch(dispatcher) {
            consumer.consume(
                messageId = "row-abort",
                streamChannel = channel,
                onUiTextUpdate = {},
                onCheckpoint = { _, _, _, _ -> },
                onFinalize = { _, text, status, seq ->
                    finalText = text
                    finalStatus = status
                    finalSeq = seq
                }
            )
        }

        channel.send(StreamToken(1, "Sebagian "))
        advanceTimeBy(50L)
        channel.cancel(CancellationException("Network lost"))
        advanceUntilIdle()
        job.join()

        assertEquals("Sebagian ", finalText)
        assertEquals(ChatStatus.INTERRUPTED, finalStatus)
        assertEquals(1, finalSeq)
    }

    @Test
    fun consecutiveStreamsAreIsolatedAndDeduplicateReplayedSeq() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val consumer = ChatStreamConsumer(
            batchIntervalMs = 10L,
            checkpointIntervalMs = 5_000L,
            nowMs = { testScheduler.currentTime },
            ioDispatcher = dispatcher
        )

        var firstResult = ""
        val ch1 = Channel<StreamToken>(Channel.UNLIMITED)
        val job1 = launch(dispatcher) {
            consumer.consume(
                messageId = "s1",
                streamChannel = ch1,
                initialDraft = "A",
                initialLastSeq = 1,
                onUiTextUpdate = {},
                onCheckpoint = { _, _, _, _ -> },
                onFinalize = { _, text, _, _ -> firstResult = text }
            )
        }
        // seq=1 is duplicate of initialLastSeq -> must be ignored
        ch1.send(StreamToken(1, "A"))
        ch1.send(StreamToken(2, "B"))
        ch1.close()
        advanceUntilIdle()
        job1.join()

        var secondResult = ""
        val ch2 = Channel<StreamToken>(Channel.UNLIMITED)
        val job2 = launch(dispatcher) {
            consumer.consume(
                messageId = "s2",
                streamChannel = ch2,
                onUiTextUpdate = {},
                onCheckpoint = { _, _, _, _ -> },
                onFinalize = { _, text, _, _ -> secondResult = text }
            )
        }
        ch2.send(StreamToken(1, "C"))
        ch2.close()
        advanceUntilIdle()
        job2.join()

        assertEquals("AB", firstResult)
        assertEquals("C", secondResult)
    }

    @Test
    fun canonicalFinanceActionSignMessageMatchesServerSpec() {
        val bytes = DeviceKeystoreManager.buildCanonicalActionMessage(
            nonce = "nonce-32b-url",
            actionId = "act-9f2a",
            canonicalHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )
        val expected = "nexa-action-v1\nnonce-32b-url\nact-9f2a\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(expected, bytes.toString(Charsets.UTF_8))
    }
}
