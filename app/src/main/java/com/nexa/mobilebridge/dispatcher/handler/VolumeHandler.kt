package com.nexa.mobilebridge.dispatcher.handler

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class VolumeHandler(private val context: Context) {

    fun execute(command: NexaCommand): CommandResult {
        return try {
            val rawLevel = command.params?.get("level")?.jsonPrimitive?.intOrNull
                ?: return CommandResult.failure(command.command_id, "Missing 'level' parameter (0–100)")
            val streamName = command.params?.get("stream")?.jsonPrimitive?.content ?: "MUSIC"

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val streamType = when (streamName.uppercase()) {
                "RING", "RINGTONE" -> AudioManager.STREAM_RING
                "NOTIFICATION" -> AudioManager.STREAM_NOTIFICATION
                "SYSTEM" -> AudioManager.STREAM_SYSTEM
                "ALARM" -> AudioManager.STREAM_ALARM
                else -> AudioManager.STREAM_MUSIC
            }

            val maxVolume = audioManager.getStreamMaxVolume(streamType)
            // If level <= 15 treat as absolute index, else treat as percentage 0-100
            val targetVolume = if (rawLevel <= maxVolume) {
                rawLevel
            } else {
                (rawLevel.toFloat() / 100f * maxVolume).toInt().coerceIn(0, maxVolume)
            }

            audioManager.setStreamVolume(streamType, targetVolume, AudioManager.FLAG_SHOW_UI)

            CommandResult.success(command.command_id, "Volume [$streamName] set to $targetVolume / $maxVolume ($rawLevel%)")
        } catch (e: Exception) {
            CommandResult.failure(command.command_id, "Failed to set volume: ${e.message}")
        }
    }

    fun executeDnd(command: NexaCommand): CommandResult {
        return try {
            val enabled = command.params?.get("enabled")?.jsonPrimitive?.booleanOrNull ?: true
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (!notificationManager.isNotificationPolicyAccessGranted) {
                return CommandResult.failure(
                    command.command_id,
                    "DND permission not granted. Please enable Notification Policy Access for N.E.X.A Bridge in Settings."
                )
            }

            val filter = if (enabled) {
                NotificationManager.INTERRUPTION_FILTER_NONE
            } else {
                NotificationManager.INTERRUPTION_FILTER_ALL
            }
            notificationManager.setInterruptionFilter(filter)

            CommandResult.success(command.command_id, "DND mode ${if (enabled) "ENABLED" else "DISABLED"}")
        } catch (e: Exception) {
            CommandResult.failure(command.command_id, "Failed to set DND: ${e.message}")
        }
    }
}
