package com.nexa.mobilebridge.dispatcher.handler

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/**
 * On-Demand Light Sensor Query Handler.
 * Only activates the hardware sensor for a fraction of a second when requested by Nexa Server,
 * then immediately shuts it off (0% continuous battery drain in background).
 */
class LightSensorHandler(private val context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    suspend fun executeGetLightStatus(command: NexaCommand): CommandResult {
        if (lightSensor == null) {
            return CommandResult.failure(command.command_id, "Light sensor not available on this device.")
        }

        val lux = withTimeoutOrNull(1500L) {
            suspendCancellableCoroutine<Float> { continuation ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        val reading = event?.values?.getOrNull(0) ?: 0f
                        try {
                            sensorManager.unregisterListener(this)
                        } catch (_: Exception) {}
                        if (continuation.isActive) continuation.resume(reading)
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_FASTEST)
                continuation.invokeOnCancellation {
                    try {
                        sensorManager.unregisterListener(listener)
                    } catch (_: Exception) {}
                }
            }
        } ?: 0f

        val state = when {
            lux < 3f -> "DARK"
            lux >= 80f -> "BRIGHT"
            else -> "NORMAL"
        }

        val data = buildJsonObject {
            put("lux", lux.toDouble())
            put("state", state)
        }
        return CommandResult.success(command.command_id, "Light sensor reading: $lux lux ($state)", data)
    }
}
