package com.nexa.mobilebridge.dispatcher.handler

import android.content.Context
import android.content.Intent
import com.nexa.mobilebridge.TransparentCameraActivity
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import com.nexa.mobilebridge.protocol.ResultStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class CameraHandler(private val context: Context) {
    suspend fun execute(command: NexaCommand): CommandResult {
        val useFrontCamera = command.params?.get("front_camera")?.let {
            if (it is kotlinx.serialization.json.JsonPrimitive) it.content == "true" || it.content == "1" || (it.isString && it.content.lowercase() == "true") else false
        } ?: false
        
        return suspendCancellableCoroutine { continuation ->
            TransparentCameraActivity.onPhotoTaken = { base64, error ->
                if (continuation.isActive) {
                    if (error != null || base64 == null) {
                        continuation.resume(CommandResult.failure(command.command_id, error ?: "Unknown error"))
                    } else {
                        continuation.resume(
                            CommandResult(
                                command_id = command.command_id,
                                status = ResultStatus.SUCCESS,
                                message = "Photo captured successfully",
                                data = kotlinx.serialization.json.buildJsonObject {
                                    put("image_base64", kotlinx.serialization.json.JsonPrimitive(base64))
                                }
                            )
                        )
                    }
                }
            }

            val intent = Intent(context, TransparentCameraActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra("USE_FRONT_CAMERA", useFrontCamera)
            }
            
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                TransparentCameraActivity.onPhotoTaken = null
                if (continuation.isActive) {
                    continuation.resume(CommandResult.failure(command.command_id, "Failed to launch camera: ${e.message}"))
                }
            }
        }
    }
}
