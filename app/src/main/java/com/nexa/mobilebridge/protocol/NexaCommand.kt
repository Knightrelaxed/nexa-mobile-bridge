package com.nexa.mobilebridge.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class NexaCommand(
    val type: String,
    val command_id: String,
    val action: String,
    val params: JsonObject? = null,
    val signature: String? = null,
    val timestamp: Long = 0L
)
