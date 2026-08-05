package com.nexa.mobilebridge.dispatcher.handler

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BatteryHandler(private val context: Context) {
    fun execute(command: NexaCommand): CommandResult {
        return try {
            val batteryStatus: Intent? = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )

            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else -1

            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL

            val data = buildJsonObject {
                put("battery_level", batteryPct)
                put("charging", isCharging)
            }

            CommandResult.success(
                commandId = command.command_id,
                message = "Battery: ${batteryPct}%, Charging: $isCharging",
                data = data
            )
        } catch (e: Exception) {
            CommandResult.failure(command.command_id, "Failed to get battery status: ${e.message}")
        }
    }
}
