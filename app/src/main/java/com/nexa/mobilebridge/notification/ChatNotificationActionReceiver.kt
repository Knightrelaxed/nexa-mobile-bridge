package com.nexa.mobilebridge.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.nexa.mobilebridge.data.chat.ChatRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles notification shade actions: Inline Reply, Mark as Read, and swipe-dismiss.
 */
@AndroidEntryPoint
class ChatNotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var notificationManager: ChatNotificationManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ChatNotificationManager.ACTION_REPLY -> {
                val replyText = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(ChatNotificationManager.KEY_REPLY_TEXT)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                if (replyText.isEmpty()) return

                // Show the user's line instantly (clears the reply spinner), then send it like a normal chat message
                notificationManager.appendUserReply(replyText)
                val pending = goAsync()
                scope.launch {
                    try {
                        chatRepository.sendMessage(replyText)
                        chatRepository.markChatRead()
                    } catch (e: Exception) {
                        Log.w("ChatNotifAction", "Inline reply failed: ${e.message}")
                    } finally {
                        pending.finish()
                    }
                }
            }

            ChatNotificationManager.ACTION_MARK_READ -> {
                notificationManager.clearConversation()
                chatRepository.markChatRead()
            }

            ChatNotificationManager.ACTION_DISMISSED -> {
                notificationManager.onDismissedBySwipe()
            }
        }
    }
}
