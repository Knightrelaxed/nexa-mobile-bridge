package com.nexa.mobilebridge.data.chat

import com.nexa.mobilebridge.data.local.chat.ChatRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Compact quoted reply reference persisted inside ChatMessageEntity.executionMetadata
 * (supports replying to either user or N.E.X.A messages, via text or voice note).
 */
@Serializable
data class ChatReplyTarget(
    @SerialName("message_id")
    val messageId: String,
    @SerialName("role")
    val role: String,
    @SerialName("preview")
    val preview: String,
    @SerialName("is_voice")
    val isVoice: Boolean = false
)

@Serializable
private data class ReplyMetadataEnvelope(
    @SerialName("kind")
    val kind: String = KIND_REPLY,
    @SerialName("reply_to")
    val replyTo: ChatReplyTarget? = null
) {
    companion object {
        const val KIND_REPLY = "reply"
    }
}

object ChatReplyMetadata {
    private val jsonCodec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun toJson(replyTo: ChatReplyTarget): String {
        return jsonCodec.encodeToString(
            ReplyMetadataEnvelope(
                kind = ReplyMetadataEnvelope.KIND_REPLY,
                replyTo = replyTo.copy(preview = sanitizePreview(replyTo.preview))
            )
        )
    }

    /**
     * Extract reply_to from either a text-reply envelope or a voice-note envelope.
     */
    fun fromJson(raw: String?): ChatReplyTarget? {
        if (raw.isNullOrBlank()) return null
        return try {
            val env = jsonCodec.decodeFromString<ReplyMetadataEnvelope>(raw)
            val target = env.replyTo ?: return null
            if (target.messageId.isBlank() || target.preview.isBlank()) null else target
        } catch (_: Exception) {
            null
        }
    }

    fun senderLabel(role: String): String {
        return if (role == ChatRole.ASSISTANT) "N.E.X.A" else "You"
    }

    fun sanitizePreview(raw: String, maxChars: Int = 140): String {
        val cleaned = raw
            .replace(Regex("```[\\s\\S]*?```"), "[code]")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.length > maxChars) {
            cleaned.take(maxChars - 1).trimEnd() + "…"
        } else {
            cleaned
        }
    }
}

/**
 * Persisted voice note metadata stored inside ChatMessageEntity.executionMetadata
 * (zero Room schema migration risk, offline-first compatible, pure Kotlin JSON serialization).
 */
@Serializable
data class VoiceNoteMetadata(
    @SerialName("audio_path")
    val audioPath: String,
    @SerialName("duration_ms")
    val durationMs: Long,
    @SerialName("waveform")
    val waveform: List<Int>,
    @SerialName("mime_type")
    val mimeType: String = MIME_OGG,
    @SerialName("kind")
    val kind: String = KIND_VOICE_NOTE,
    @SerialName("reply_to")
    val replyTo: ChatReplyTarget? = null
) {
    companion object {
        const val KIND_VOICE_NOTE = "voice_note"
        const val MIME_OGG = "audio/ogg"
        const val MIME_MP4 = "audio/mp4"
        const val TARGET_WAVEFORM_BARS = 32

        private val jsonCodec = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromJson(raw: String?): VoiceNoteMetadata? {
            if (raw.isNullOrBlank()) return null
            return try {
                val parsed = jsonCodec.decodeFromString<VoiceNoteMetadata>(raw)
                if (parsed.kind != KIND_VOICE_NOTE) return null
                val cleanPath = parsed.audioPath.trim()
                if (cleanPath.isEmpty()) return null
                val safeBars = if (parsed.waveform.isEmpty()) {
                    List(TARGET_WAVEFORM_BARS) { 14 }
                } else {
                    parsed.waveform.map { it.coerceIn(1, 100) }
                }
                parsed.copy(
                    audioPath = cleanPath,
                    durationMs = parsed.durationMs.coerceAtLeast(0L),
                    waveform = safeBars,
                    mimeType = parsed.mimeType.ifBlank { MIME_OGG }
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    fun toJson(): String {
        val sanitized = copy(
            durationMs = durationMs.coerceAtLeast(0L),
            waveform = waveform.map { it.coerceIn(1, 100) },
            mimeType = mimeType.ifBlank { MIME_OGG },
            kind = KIND_VOICE_NOTE,
            replyTo = replyTo?.copy(preview = ChatReplyMetadata.sanitizePreview(replyTo.preview))
        )
        return jsonCodec.encodeToString(sanitized)
    }
}

object VoiceNoteWaveform {

    /**
     * Downsample and perceptually normalize raw MediaRecorder.maxAmplitude (0..32767)
     * samples into a fixed-width bar array (1..100) for compact chat bubble rendering.
     */
    fun normalize(
        rawSamples: List<Int>,
        targetBars: Int = VoiceNoteMetadata.TARGET_WAVEFORM_BARS
    ): List<Int> {
        require(targetBars > 0) { "targetBars must be positive" }
        if (rawSamples.isEmpty()) {
            return List(targetBars) { 12 }
        }

        val clamped = rawSamples.map { it.coerceAtLeast(0) }
        val peak = clamped.maxOrNull()?.coerceAtLeast(1800) ?: 1800

        return List(targetBars) { barIdx ->
            val start = (barIdx * clamped.size) / targetBars
            val end = (((barIdx + 1) * clamped.size) / targetBars).coerceAtLeast(start + 1).coerceAtMost(clamped.size)
            var bucketMax = 0
            for (i in start until end) {
                if (clamped[i] > bucketMax) bucketMax = clamped[i]
            }
            val linear = (bucketMax.toDouble() / peak.toDouble()).coerceIn(0.0, 1.0)
            // Square-root perceptual curve so soft speech stays legible like Telegram waveforms
            val perceptual = sqrt(linear)
            (10.0 + perceptual * 90.0).roundToInt().coerceIn(8, 100)
        }
    }

    /**
     * Normalize a single live maxAmplitude sample into 0f..1f for real-time recording halo/bars.
     */
    fun normalizeLiveLevel(maxAmplitude: Int): Float {
        val linear = (maxAmplitude.coerceAtLeast(0).toFloat() / 22_000f).coerceIn(0f, 1f)
        return sqrt(linear.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Format milliseconds into compact M:SS (e.g. 0:00, 0:09, 1:24, 3:00).
     */
    fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }
}
