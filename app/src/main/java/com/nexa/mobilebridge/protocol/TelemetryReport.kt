package com.nexa.mobilebridge.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class TelemetryReport(
    val type: String = "TELEMETRY_REPORT",
    val battery_level: Int,
    val battery_charging: Boolean,
    val network_type: String,   // "5G" | "4G" | "WIFI" | "NONE"
    val network_strength: Int,  // dBm
    val motion_state: String,   // "IDLE" | "MOVING"
    val screen_on: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class TaskerEvent(
    val type: String,           // "ALARM_DISMISSED" | "SCREEN_TIME_VIOLATION"
    val data: JsonObject
)
