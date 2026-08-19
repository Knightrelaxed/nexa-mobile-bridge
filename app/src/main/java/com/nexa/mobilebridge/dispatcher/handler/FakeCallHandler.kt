package com.nexa.mobilebridge.dispatcher.handler

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeCallHandler @Inject constructor(
    private val context: Context,
    private val audioPlayerHandler: AudioPlayerHandler
) {
    private val TAG = "FakeCallHandler"

    fun execute(command: NexaCommand): CommandResult {
        val callerName = (command.params?.get("caller_name") as? JsonPrimitive)?.content ?: "N.E.X.A Assistant"
        val message = (command.params?.get("message") as? JsonPrimitive)?.content ?: "Tuan Faqih, ada interupsi penting dari N.E.X.A!"
        val playRingtoneStr = (command.params?.get("play_ringtone") as? JsonPrimitive)?.content
        val playRingtone = playRingtoneStr?.toBooleanStrictOrNull() ?: true

        return try {
            // 1. Wake up device screen from sleep / lock state immediately
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                @Suppress("DEPRECATION")
                val wakeLock = powerManager?.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "NexaBridge:IncomingCallWakeLock"
                )
                wakeLock?.acquire(10000L) // 10s bright wake lock
                Log.i(TAG, "💡 Screen WakeLock acquired for incoming call")
            } catch (e: Exception) {
                Log.w(TAG, "WakeLock acquisition skipped: ${e.message}")
            }

            // 2. Launch FakeCallActivity in an isolated single-instance task
            val intent = Intent(context, com.nexa.mobilebridge.ui.call.FakeCallActivity::class.java).apply {
                putExtra("command_id", command.command_id)
                putExtra("caller_name", callerName)
                putExtra("message", message)
                putExtra("play_ringtone", playRingtone)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }

            context.startActivity(intent)
            Log.i(TAG, "📞 FakeCallActivity launched over lockscreen for caller: $callerName")
            CommandResult.success(command.command_id, "Simulated incoming call displayed successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch FakeCallActivity", e)
            CommandResult.failure(command.command_id, "Failed to launch FakeCallActivity: ${e.localizedMessage}")
        }
    }
}
