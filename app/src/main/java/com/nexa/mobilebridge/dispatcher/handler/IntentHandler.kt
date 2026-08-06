package com.nexa.mobilebridge.dispatcher.handler

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

class IntentHandler(private val context: Context) {

    fun execute(command: NexaCommand): CommandResult {
        val intentType = (command.params?.get("type") as? JsonPrimitive)?.content
            ?: (command.params?.get("action_type") as? JsonPrimitive)?.content
            ?: "OPEN_URL"

        return try {
            when (intentType.uppercase()) {
                "OPEN_URL" -> openUrl(command)
                "OPEN_MAPS" -> openMaps(command)
                "SHARE_TEXT" -> shareText(command)
                else -> openUrl(command)
            }
        } catch (e: Exception) {
            CommandResult.failure(command.command_id, "Intent execution error: ${e.message}")
        }
    }

    private fun openUrl(command: NexaCommand): CommandResult {
        val url = (command.params?.get("url") as? JsonPrimitive)?.content
            ?: return CommandResult.failure(command.command_id, "Missing required parameter 'url'.")

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return CommandResult.success(command.command_id, "URL opened successfully: $url")
    }

    private fun openMaps(command: NexaCommand): CommandResult {
        val lat = (command.params?.get("lat") as? JsonPrimitive)?.doubleOrNull
        val lng = (command.params?.get("lng") as? JsonPrimitive)?.doubleOrNull
        val query = (command.params?.get("query") as? JsonPrimitive)?.content

        val uriString = if (lat != null && lng != null) {
            if (query != null) "geo:$lat,$lng?q=${Uri.encode(query)}" else "geo:$lat,$lng"
        } else if (query != null) {
            "geo:0,0?q=${Uri.encode(query)}"
        } else {
            return CommandResult.failure(command.command_id, "Provide 'lat'/'lng' or 'query' for OPEN_MAPS.")
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return CommandResult.success(command.command_id, "Maps intent launched: $uriString")
    }

    private fun shareText(command: NexaCommand): CommandResult {
        val text = (command.params?.get("text") as? JsonPrimitive)?.content
            ?: return CommandResult.failure(command.command_id, "Missing required parameter 'text'.")

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val chooserIntent = Intent.createChooser(intent, "Share via NEXA").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(chooserIntent)
        return CommandResult.success(command.command_id, "Share text intent launched.")
    }
}
