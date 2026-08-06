package com.nexa.mobilebridge.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ContextReport(
    val type: String = "CONTEXT_UPDATE",
    val event: String,
    val summary: String,
    val details: JsonObject? = null,
    val timestamp: Long = System.currentTimeMillis()
)
