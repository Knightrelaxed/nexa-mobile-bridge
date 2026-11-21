package com.nexa.mobilebridge.dispatcher.handler

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ScreenHandler(private val context: Context) {

    fun executeGetScreenStatus(command: NexaCommand): CommandResult {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isInteractive = powerManager?.isInteractive ?: false
            val data = buildJsonObject {
                put("screen_on", isInteractive)
                put("screen_state", if (isInteractive) "ON" else "OFF")
                put("is_interactive", isInteractive)
            }
            CommandResult.success(command.command_id, "Screen is currently ${if (isInteractive) "ON" else "OFF"}", data)
        } catch (e: Exception) {
            CommandResult.failure(command.command_id, "Failed to read screen status: ${e.message}")
        }
    }

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
        return CommandResult.failure(
            command.command_id,
            "Command LOCK_SCREEN is deprecated: AccessibilityService removed for m-banking security (FR-CORE-03)."
        )
    }

    fun executeBack(command: NexaCommand): CommandResult {
        return CommandResult.failure(
            command.command_id,
            "Command GO_BACK is deprecated: AccessibilityService removed for m-banking security (FR-CORE-03)."
        )
    }

    fun executeRecents(command: NexaCommand): CommandResult {
        return CommandResult.failure(
            command.command_id,
            "Command SHOW_RECENTS is deprecated: AccessibilityService removed for m-banking security (FR-CORE-03)."
        )
    }
}
