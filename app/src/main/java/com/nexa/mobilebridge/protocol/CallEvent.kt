package com.nexa.mobilebridge.protocol

import kotlinx.serialization.Serializable

/**
 * Event payload sent from the App (HP) back to the N.E.X.A Server.
 * Used for bidirectional call interaction (Accept, Reject, Audio Reply).
 */
@Serializable
data class CallEvent(
    val type: String, // Explicitly require type so kotlinx.serialization always includes it
    val event: String,           // NexaActions.CALL_ACCEPTED / CALL_REJECTED / CALL_AUDIO_REPLY
    val command_id: String,
    val caller_name: String? = null,
    val rejection_count: Int? = null,      // For CALL_REJECTED: how many times user has rejected
    val audio_base64: String? = null,      // For CALL_AUDIO_REPLY: base64-encoded PCM audio
    val audio_format: String? = "PCM_16BIT_16KHZ_MONO", // Audio encoding info for server STT
    val timestamp: Long = System.currentTimeMillis(),
    val device_name: String = "Samsung_A33_5G"
)
