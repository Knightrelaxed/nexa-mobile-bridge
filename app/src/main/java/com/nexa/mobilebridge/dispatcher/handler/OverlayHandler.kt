package com.nexa.mobilebridge.dispatcher.handler

import android.content.Context
import android.content.Intent
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import com.nexa.mobilebridge.ui.overlay.OverlayActivity
import kotlinx.serialization.json.buildJsonObject

class OverlayHandler(private val context: Context) {
    fun execute(command: NexaCommand): CommandResult {
        return try {
            val rawMessage = command.params?.get("message")?.let {
                if (it is kotlinx.serialization.json.JsonPrimitive) it.content else it.toString().replace("\"", "")
            } ?: "Konfirmasi dari N.E.X.A"
            val message = rawMessage.replace("\\n", "\n")
            if (!android.provider.Settings.canDrawOverlays(context)) {
                return CommandResult.failure(
                    command.command_id,
                    "Izin 'Tampilkan di atas aplikasi lain' (Appear on top / Draw Overlays) belum diaktifkan di Pengaturan HP!"
                )
            }
            
            val optionsJson = command.params?.get("options")?.toString()
            
            val intent = Intent(context, OverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("COMMAND_ID", command.command_id)
                putExtra("MESSAGE", message)
                if (optionsJson != null) {
                    putExtra("OPTIONS_JSON", optionsJson)
                }
            }
            context.startActivity(intent)
            
            // Result is sent asynchronously from OverlayActivity
            CommandResult.success(
                commandId = command.command_id,
                message = "Overlay diluncurkan, menunggu respon user...",
                data = buildJsonObject { }
            )
        } catch (e: Exception) {
            CommandResult.failure(command.command_id, "Gagal meluncurkan overlay: \${e.message}")
        }
    }
}
