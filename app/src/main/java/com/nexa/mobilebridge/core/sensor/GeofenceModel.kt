package com.nexa.mobilebridge.core.sensor

import kotlinx.serialization.Serializable

@Serializable
data class GeofenceZone(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 100f,
    val createdAt: Long = System.currentTimeMillis()
)
