package com.nexa.mobilebridge.dispatcher.handler

import android.content.Context
import com.nexa.mobilebridge.NexaAccessibilityService
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import com.nexa.mobilebridge.protocol.ResultStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ScreenshotHandler(private val context: Context) {
    suspend fun execute(command: NexaCommand): CommandResult {
        val service = NexaAccessibilityService.instance
        if (service == null) {
            return CommandResult.failure(
                command.command_id,
                "Accessibility Service is not enabled. Please enable it in Settings > Accessibility."
            )
        }

        return suspendCancellableCoroutine { continuation ->
            service.takeScreenshotBase64 { base64, error ->
                if (continuation.isActive) {
                    if (error != null || base64 == null) {
                        continuation.resume(CommandResult.failure(command.command_id, error ?: "Unknown screenshot error"))
                    } else {
                        continuation.resume(
                            CommandResult(
                                command_id = command.command_id,
                                status = ResultStatus.SUCCESS,
                                message = "Screenshot captured successfully",
                                data = kotlinx.serialization.json.buildJsonObject {
                                    put("image_base64", kotlinx.serialization.json.JsonPrimitive(base64))
                                }
                            )
                        )
                    }
                }
            }
        }
    }
}
