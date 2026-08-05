package com.nexa.mobilebridge.dispatcher.handler

import android.content.Context
import android.hardware.camera2.CameraManager
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

class FlashlightHandler(private val context: Context) {
    fun execute(command: NexaCommand): CommandResult {
        return try {
            val enabled = command.params?.get("enabled")?.jsonPrimitive?.booleanOrNull ?: true
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: return CommandResult.failure(command.command_id, "No camera found on device.")
            cameraManager.setTorchMode(cameraId, enabled)

            CommandResult.success(command.command_id, "Flashlight turned ${if (enabled) "ON" else "OFF"}")
        } catch (e: Exception) {
            CommandResult.failure(command.command_id, "Failed to toggle flashlight: ${e.message}")
        }
    }
}
