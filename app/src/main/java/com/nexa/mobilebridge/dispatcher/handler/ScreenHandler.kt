package com.nexa.mobilebridge.dispatcher.handler

import android.content.Context
import android.content.Intent
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand

class ScreenHandler(private val context: Context) {

    fun execute(command: NexaCommand): CommandResult {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            CommandResult.success(command.command_id, "Go Home Screen executed.")
        } catch (e: Exception) {
            CommandResult.failure(command.command_id, "Failed to go home: ${e.message}")
        }
    }

    fun executeLock(command: NexaCommand): CommandResult {
        // LOCK_SCREEN requires DevicePolicyManager with BIND_DEVICE_ADMIN — complex ADB setup.
        // Intentionally not implemented in Phase 1.
        return CommandResult.failure(
            command.command_id,
            "LOCK_SCREEN requires BIND_DEVICE_ADMIN permission. Not implemented in Phase 1."
        )
    }
}
