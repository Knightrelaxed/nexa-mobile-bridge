package com.nexa.mobilebridge.dispatcher.handler

import android.content.Context
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand

class ScreenshotHandler(@Suppress("UNUSED_PARAMETER") private val context: Context) {
    suspend fun execute(command: NexaCommand): CommandResult {
        return CommandResult.failure(
            command.command_id,
            "Command TAKE_SCREENSHOT is deprecated: AccessibilityService removed for m-banking security (FR-CORE-03)."
        )
    }
}
