package com.nexa.mobilebridge.dispatcher.handler

import android.content.Context
import android.content.Intent
import com.nexa.mobilebridge.TransparentCameraActivity
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import com.nexa.mobilebridge.protocol.ResultStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.resume

class CameraHandler(private val context: Context) {
    suspend fun execute(command: NexaCommand): CommandResult {
        val useFrontCamera = command.params?.let { p ->
            val frontVal = p["front_camera"] ?: p["front"]
            val facingVal = p["camera_facing"] ?: p["facing"]
            
            if (frontVal != null && frontVal is JsonPrimitive) {
                frontVal.content == "true" || frontVal.content == "1"
            } else if (facingVal != null && facingVal is JsonPrimitive) {
                facingVal.content.lowercase().contains("front")
            } else {
                false
            }
        } ?: false
        
        return suspendCancellableCoroutine { continuation ->
            TransparentCameraActivity.onPhotoTaken = { base64, error ->
                if (continuation.isActive) {
                    if (error != null || base64 == null) {
                        continuation.resume(CommandResult.failure(command.command_id, error ?: "Unknown camera error"))
                    } else {
                        continuation.resume(
                            CommandResult(
                                command_id = command.command_id,
                                status = ResultStatus.SUCCESS,
                                message = "Photo captured successfully",
                                data = kotlinx.serialization.json.buildJsonObject {
                                    put("image_base64", JsonPrimitive(base64))
                                }
                            )
                        )
                    }
                }
            }

            continuation.invokeOnCancellation {
                TransparentCameraActivity.onPhotoTaken = null
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
                    continuation.resume(CommandResult.failure(command.command_id, "Failed to launch camera activity: ${e.message}"))
                }
            }
        }
    }
}
