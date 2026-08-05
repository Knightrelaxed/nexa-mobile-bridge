package com.nexa.mobilebridge.dispatcher.handler

import android.content.Context
import android.content.Intent
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import kotlinx.serialization.json.jsonPrimitive

class AppLauncherHandler(private val context: Context) {

    fun execute(command: NexaCommand): CommandResult {
        return try {
            val packageName = command.params?.get("package_name")?.jsonPrimitive?.content
                ?: return CommandResult.failure(command.command_id, "Missing 'package_name' parameter")

            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(launchIntent)
                CommandResult.success(command.command_id, "App launched: $packageName")
            } else {
                CommandResult.failure(command.command_id, "App not found on device: $packageName")
            }
        } catch (e: Exception) {
            CommandResult.failure(command.command_id, "Failed to launch app: ${e.message}")
        }
    }
}
