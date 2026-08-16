package com.nexa.mobilebridge.dispatcher.handler

import android.content.Context
import com.nexa.mobilebridge.core.sensor.GeofenceManager
import com.nexa.mobilebridge.core.sensor.GeofenceZone
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import com.nexa.mobilebridge.protocol.ResultStatus
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GeofenceHandler(private val context: Context, private val geofenceManager: GeofenceManager) {

    suspend fun executeSet(command: NexaCommand): CommandResult {
        val params = command.params ?: return CommandResult.failure(command.command_id, "Missing geofence parameters.")
        val id = params["id"]?.toString()?.trim('"') ?: params["geofence_id"]?.toString()?.trim('"')
            ?: return CommandResult.failure(command.command_id, "Missing 'id' parameter.")
        val name = params["name"]?.toString()?.trim('"') ?: id
        val lat = params["latitude"]?.toString()?.toDoubleOrNull() ?: params["lat"]?.toString()?.toDoubleOrNull()
            ?: return CommandResult.failure(command.command_id, "Missing or invalid 'latitude'.")
        val lng = params["longitude"]?.toString()?.toDoubleOrNull() ?: params["lng"]?.toString()?.toDoubleOrNull()
            ?: return CommandResult.failure(command.command_id, "Missing or invalid 'longitude'.")
        val radius = params["radius"]?.toString()?.toFloatOrNull() ?: params["radius_meters"]?.toString()?.toFloatOrNull() ?: 100f

        val zone = GeofenceZone(
            id = id.uppercase(),
            name = name,
            latitude = lat,
            longitude = lng,
            radiusMeters = radius,
            createdAt = System.currentTimeMillis()
        )

        val success = geofenceManager.addOrUpdateGeofence(zone)
        return if (success) {
            CommandResult(
                command_id = command.command_id,
                status = ResultStatus.SUCCESS,
                message = "Geofence [$id] ($name) registered successfully at ($lat, $lng) with radius ${radius}m",
                data = buildJsonObject {
                    put("id", zone.id)
                    put("name", zone.name)
                    put("latitude", zone.latitude)
                    put("longitude", zone.longitude)
                    put("radius_meters", zone.radiusMeters)
                }
            )
        } else {
            CommandResult.failure(command.command_id, "Failed to register geofence. Ensure location permissions are granted.")
        }
    }

    suspend fun executeMarkCurrent(command: NexaCommand): CommandResult {
        val params = command.params ?: emptyMap()
        val id = params["id"]?.toString()?.trim('"') ?: params["geofence_id"]?.toString()?.trim('"') ?: "HOME"
        val name = params["name"]?.toString()?.trim('"') ?: id
        val radius = params["radius"]?.toString()?.toFloatOrNull() ?: params["radius_meters"]?.toString()?.toFloatOrNull() ?: 100f

        val zone = geofenceManager.markCurrentLocation(id, name, radius)
        return if (zone != null) {
            CommandResult(
                command_id = command.command_id,
                status = ResultStatus.SUCCESS,
                message = "📍 Lokasi saat ini berhasil ditandai sebagai Geofence [$id] (${zone.name}) pada (${zone.latitude}, ${zone.longitude})",
                data = buildJsonObject {
                    put("id", zone.id)
                    put("name", zone.name)
                    put("latitude", zone.latitude)
                    put("longitude", zone.longitude)
                    put("radius_meters", zone.radiusMeters)
                }
            )
        } else {
            CommandResult.failure(command.command_id, "Gagal menandai lokasi. Pastikan GPS aktif dan izin lokasi diberikan.")
        }
    }

    fun executeRemove(command: NexaCommand): CommandResult {
        val params = command.params ?: return CommandResult.failure(command.command_id, "Missing parameters.")
        val id = params["id"]?.toString()?.trim('"') ?: params["geofence_id"]?.toString()?.trim('"')
            ?: return CommandResult.failure(command.command_id, "Missing 'id' parameter.")

        val success = geofenceManager.removeGeofence(id)
        return if (success) {
            CommandResult(
                command_id = command.command_id,
                status = ResultStatus.SUCCESS,
                message = "Geofence [$id] removed successfully."
            )
        } else {
            CommandResult.failure(command.command_id, "Geofence [$id] not found.")
        }
    }

    fun executeList(command: NexaCommand): CommandResult {
        val zones = geofenceManager.getAllGeofences()
        return CommandResult(
            command_id = command.command_id,
            status = ResultStatus.SUCCESS,
            message = "Found ${zones.size} active geofence zone(s).",
            data = buildJsonObject {
                put("total", zones.size)
                put("zones", buildJsonArray {
                    zones.forEach { z ->
                        add(buildJsonObject {
                            put("id", z.id)
                            put("name", z.name)
                            put("latitude", z.latitude)
                            put("longitude", z.longitude)
                            put("radius_meters", z.radiusMeters)
                            put("created_at", z.createdAt)
                        })
                    }
                })
            }
        )
    }
}
