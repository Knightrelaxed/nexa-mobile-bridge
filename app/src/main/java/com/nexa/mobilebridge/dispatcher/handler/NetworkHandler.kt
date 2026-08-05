package com.nexa.mobilebridge.dispatcher.handler

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class NetworkHandler(private val context: Context) {
    fun execute(command: NexaCommand): CommandResult {
        return try {
            val enabled = command.params?.get("enabled")?.jsonPrimitive?.booleanOrNull ?: false
            
            // In Android 10+ (API 29+), changing WiFi state programmatically is restricted.
            // It requires WRITE_SECURE_SETTINGS (granted via ADB) or using a Settings panel.
            // Since this is for GodMode, we assume ADB permissions have been granted.
            
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            
            @Suppress("DEPRECATION")
            val success = wifiManager.setWifiEnabled(enabled)
            
            if (success) {
                CommandResult(
                    command_id = command.command_id,
                    status = com.nexa.mobilebridge.protocol.ResultStatus.SUCCESS,
                    message = "WiFi has been ${if (enabled) "enabled" else "disabled"}."
                )
            } else {
                CommandResult(
                    command_id = command.command_id,
                    status = com.nexa.mobilebridge.protocol.ResultStatus.FAILURE,
                    message = "Failed to change WiFi state. Check ADB permissions (WRITE_SECURE_SETTINGS) or API level restrictions."
                )
            }
        } catch (e: SecurityException) {
            CommandResult(
                command_id = command.command_id,
                status = com.nexa.mobilebridge.protocol.ResultStatus.FAILURE,
                message = "Permission denied: ${e.message}"
            )
        } catch (e: Exception) {
            CommandResult(
                command_id = command.command_id,
                status = com.nexa.mobilebridge.protocol.ResultStatus.FAILURE,
                message = "Error: ${e.message}"
            )
        }
    }
    
    fun getInfo(command: NexaCommand): CommandResult {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager.connectionInfo
            val ssid = wifiInfo?.ssid?.replace("\"", "") ?: "Disconnected"
            val rssi = wifiInfo?.rssi ?: 0

            CommandResult.success(
                command.command_id,
                "WiFi SSID: $ssid, Signal: $rssi dBm",
                data = kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("WIFI"))
                    put("ssid", kotlinx.serialization.json.JsonPrimitive(ssid))
                    put("rssi", kotlinx.serialization.json.JsonPrimitive(rssi))
                }
            )
        } catch (e: Exception) {
            CommandResult.failure(command.command_id, "Failed to get network info: ${e.message}")
        }
    }
}
