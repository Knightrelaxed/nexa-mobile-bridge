package com.nexa.mobilebridge.dispatcher.handler

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ClipboardHandler(private val context: Context) {

    private val clipboardManager: ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun executeGet(command: NexaCommand): CommandResult {
        return try {
            val clip = clipboardManager.primaryClip
            val text = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).text?.toString() ?: ""
            } else {
                ""
            }
            CommandResult.success(
                command.command_id,
                if (text.isNotEmpty()) "Clipboard read successfully." else "Clipboard is empty.",
                buildJsonObject { put("text", text) }
            )
        } catch (e: Exception) {
            CommandResult.failure(command.command_id, "Failed to read clipboard: ${e.message}")
        }
    }

    fun executeSet(command: NexaCommand): CommandResult {
        val textParam = (command.params?.get("text") as? JsonPrimitive)?.content
            ?: return CommandResult.failure(command.command_id, "Missing required parameter 'text'.")

        return try {
            Handler(Looper.getMainLooper()).post {
                val clipData = ClipData.newPlainText("NEXA_CLIPBOARD", textParam)
                clipboardManager.setPrimaryClip(clipData)
            }

            CommandResult.success(
                command.command_id,
                "Clipboard updated successfully.",
                buildJsonObject { put("text", textParam) }
            )
        } catch (e: Exception) {
            CommandResult.failure(command.command_id, "Failed to set clipboard: ${e.message}")
        }
    }
}
