package com.nexa.mobilebridge.core.context

import android.util.Log
import com.nexa.mobilebridge.core.sensor.LightState
import com.nexa.mobilebridge.core.sensor.SensorObservedEvent
import com.nexa.mobilebridge.protocol.ContextReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Calendar

class ContextEngine(private val scope: CoroutineScope) {

    private val TAG = "ContextEngine"

    private val _contextReports = MutableSharedFlow<ContextReport>(extraBufferCapacity = 16)
    val contextReports: SharedFlow<ContextReport> = _contextReports.asSharedFlow()

    private var currentLightState: String = "NORMAL"
    private var currentMotionState: String = "STATIONARY"

    fun processSensorEvent(event: SensorObservedEvent) {
        val eventName = event.eventName
        Log.d(TAG, "Menganalisis sensor event ➔ $eventName: ${event.summary}")

        when (eventName) {
            "LIGHT_CHANGED" -> {
                val state = event.data["state"]?.toString() ?: "NORMAL"
                currentLightState = state
                val isNightTime = isNightTime()

                if (state == "DARK" && isNightTime) {
                    emitContext(
                        event = "ROOM_DARK_NIGHT",
                        summary = "Ruangan dalam kondisi gelap di malam hari",
                        details = mapOf("lux" to (event.data["lux"] ?: 0))
                    )
                } else {
                    emitContext(
                        event = "LIGHT_CHANGED",
                        summary = event.summary,
                        details = event.data
                    )
                }
            }

            "MOTION_DETECTED" -> {
                currentMotionState = "MOVING"
                if (currentLightState == "DARK" && isNightTime()) {
                    emitContext(
                        event = "WALKING_AT_NIGHT",
                        summary = "Pengguna terdeteksi bergerak di lingkungan gelap pada malam hari",
                        details = mapOf("light_state" to currentLightState, "motion" to currentMotionState)
                    )
                } else {
                    emitContext(
                        event = "USER_MOVING",
                        summary = "Pergerakan signifikan terdeteksi",
                        details = event.data
                    )
                }
            }

            "PHONE_PICKUP" -> {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val isMorning = hour in 5..9
                val ctxEvent = if (isMorning) "PICKUP_MORNING" else "PHONE_PICKUP"
                val summaryStr = if (isMorning) "HP diangkat pada pagi hari (Potensi Morning Briefing)" else "HP diangkat oleh pengguna"

                emitContext(
                    event = ctxEvent,
                    summary = summaryStr,
                    details = event.data
                )
            }

            "STEP_MILESTONE" -> {
                emitContext(
                    event = "STEP_MILESTONE",
                    summary = event.summary,
                    details = event.data
                )
            }

            else -> {
                emitContext(
                    event = eventName,
                    summary = event.summary,
                    details = event.data
                )
            }
        }
    }

    fun processGeofenceEvent(geofenceId: String, transition: String) {
        val isEnter = transition == "ENTER"
        
        val eventName = when (geofenceId) {
            "HOME" -> if (isEnter) "USER_ARRIVED_HOME" else "USER_LEFT_HOME"
            "WORK" -> if (isEnter) "USER_ARRIVED_WORK" else "USER_LEFT_WORK"
            else -> if (isEnter) "GEOFENCE_ENTER_$geofenceId" else "GEOFENCE_EXIT_$geofenceId"
        }
        
        val summaryStr = when (geofenceId) {
            "HOME" -> if (isEnter) "Pengguna telah tiba di rumah" else "Pengguna telah meninggalkan rumah"
            "WORK" -> if (isEnter) "Pengguna telah tiba di kantor" else "Pengguna telah meninggalkan kantor"
            else -> if (isEnter) "Pengguna memasuki area $geofenceId" else "Pengguna meninggalkan area $geofenceId"
        }

        emitContext(
            event = eventName,
            summary = summaryStr,
            details = mapOf(
                "geofence_id" to geofenceId,
                "transition" to transition
            )
        )
    }

    private fun isNightTime(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 18 || hour < 6
    }

    private fun emitContext(event: String, summary: String, details: Map<String, Any>) {
        val detailsJson = buildJsonObject {
            details.forEach { (key, value) ->
                when (value) {
                    is Number -> put(key, JsonPrimitive(value))
                    is Boolean -> put(key, JsonPrimitive(value))
                    else -> put(key, JsonPrimitive(value.toString()))
                }
            }
        }

        val report = ContextReport(
            type = "CONTEXT_UPDATE",
            event = event,
            summary = summary,
            details = detailsJson
        )

        Log.i(TAG, "⚡ CONTEXT EMITTED: [$event] — $summary")
        scope.launch {
            _contextReports.emit(report)
        }
    }
}
