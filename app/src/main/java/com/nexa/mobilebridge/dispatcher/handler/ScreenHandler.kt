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
        return try {
            val service = com.nexa.mobilebridge.NexaAccessibilityService.instance
            if (service != null) {
                val success = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                if (success) {
                    CommandResult.success(command.command_id, "Screen locked successfully.")
                } else {
                    CommandResult.failure(command.command_id, "Failed to lock screen.")
                }
            } else {
                CommandResult.failure(
                    command.command_id,
                    "Nexa Accessibility Service is not running. Please enable it in Settings > Accessibility."
                )
            }
        } catch (e: Exception) {
            CommandResult.failure(command.command_id, "Error locking screen: ${e.message}")
        }
    }

    fun executeBack(command: NexaCommand): CommandResult {
        return try {
            val service = com.nexa.mobilebridge.NexaAccessibilityService.instance
            if (service != null) {
                val success = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                if (success) {
                    CommandResult.success(command.command_id, "Navigated BACK successfully.")
                } else {
                    CommandResult.failure(command.command_id, "Failed to navigate BACK. Ensure Nexa Accessibility Service is enabled.")
                }
            } else {
                CommandResult.failure(command.command_id, "Nexa Accessibility Service is not running. Please enable it in Settings -> Accessibility.")
            }
        } catch (e: Exception) {
            CommandResult.failure(command.command_id, "Error: ${e.message}")
        }
    }

    fun executeRecents(command: NexaCommand): CommandResult {
        return try {
            val service = com.nexa.mobilebridge.NexaAccessibilityService.instance
            if (service != null) {
                val success = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                if (success) {
                    CommandResult.success(command.command_id, "Opened RECENT APPS successfully.")
                } else {
                    CommandResult.failure(command.command_id, "Failed to open RECENT APPS. Ensure Nexa Accessibility Service is enabled.")
                }
            } else {
                CommandResult.failure(command.command_id, "Nexa Accessibility Service is not running. Please enable it in Settings -> Accessibility.")
            }
        } catch (e: Exception) {
            CommandResult.failure(command.command_id, "Error: ${e.message}")
        }
    }
}
