package com.nexa.mobilebridge.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.nexa.mobilebridge.MainActivity
import com.nexa.mobilebridge.NexaMainTab
import com.nexa.mobilebridge.R
import com.nexa.mobilebridge.data.chat.ChatVisibilityTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat notification engine: Android Conversation (MessagingStyle) notifications with Inline Reply
 * and Mark-as-Read, 3 priority channels, private lockscreen version, coalesced alerts, and a
 * 30 s Finance Step-Up alert. All notifications are suppressed while the CHAT tab is visible.
 * Lockscreen/sound behaviour beyond these defaults is controlled from Android system settings.
 */
@Singleton
class ChatNotificationManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tracker: ChatVisibilityTracker
) {
    companion object {
        private const val TAG = "ChatNotif"

        const val CHANNEL_URGENT = "nexa_chat_urgent"
        const val CHANNEL_CHAT = "nexa_chat_messages"
        const val CHANNEL_SILENT = "nexa_chat_silent"
        private const val LEGACY_PROACTIVE_CHANNEL = "nexa_bridge_proactive_01"

        const val NOTIF_ID_CONVERSATION = 2001
        const val NOTIF_ID_STEPUP = 2002

        const val ACTION_REPLY = "com.nexa.mobilebridge.notif.REPLY"
        const val ACTION_MARK_READ = "com.nexa.mobilebridge.notif.MARK_READ"
        const val ACTION_DISMISSED = "com.nexa.mobilebridge.notif.DISMISSED"
        const val KEY_REPLY_TEXT = "key_nexa_reply_text"
        const val EXTRA_OPEN_TAB = "com.nexa.mobilebridge.EXTRA_OPEN_TAB"

        private const val SHORTCUT_ID = "nexa_chat"
        private const val MAX_HISTORY = 8
        private const val MAX_MESSAGE_CHARS = 700
        private const val ALERT_COALESCE_WINDOW_MS = 3_000L
        private const val STEPUP_TTL_MS = 30_000L

        fun channelForPriority(priority: String, isProactive: Boolean): String = when {
            !isProactive -> CHANNEL_CHAT
            priority.equals("P0", ignoreCase = true) -> CHANNEL_URGENT
            priority.equals("P2", ignoreCase = true) -> CHANNEL_SILENT
            else -> CHANNEL_CHAT
        }
    }

    private data class Entry(val text: String, val timestamp: Long, val fromUser: Boolean)

    private val lock = Any()
    private val history = ArrayDeque<Entry>()
    private var lastAlertAtMs = 0L

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val userPerson: Person = Person.Builder().setName("Tuan").setKey("nexa_user").build()
    private val nexaPerson: Person = Person.Builder()
        .setName("N.E.X.A")
        .setKey("nexa_assistant")
        .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
        .build()

    init {
        ensureChannels()
        publishConversationShortcut()
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * An assistant message arrived (streamed reply or proactive push).
     * @param isProactive true for server-initiated pushes (priority decides the channel)
     * @param silent true while a Live Voice call is active (never ring into the call)
     */
    fun onIncomingMessage(text: String, priority: String, isProactive: Boolean, silent: Boolean) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (tracker.isChatVisible()) return // user is already reading the chat

        val channel = channelForPriority(priority, isProactive)
        val unread: Int
        synchronized(lock) {
            history.addLast(Entry(clean.take(MAX_MESSAGE_CHARS), System.currentTimeMillis(), fromUser = false))
            while (history.size > MAX_HISTORY) history.removeFirst()
            unread = history.count { !it.fromUser }
        }
        tracker.incrementUnread()

        val now = System.currentTimeMillis()
        val withinBurst = now - lastAlertAtMs < ALERT_COALESCE_WINDOW_MS
        if (!withinBurst) lastAlertAtMs = now
        postConversation(channel = channel, unread = unread, silent = silent, alertOnce = withinBurst)
    }

    /** Called after an inline reply: shows the user's own line and clears the reply spinner silently. */
    fun appendUserReply(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val unread: Int
        synchronized(lock) {
            history.addLast(Entry(clean.take(MAX_MESSAGE_CHARS), System.currentTimeMillis(), fromUser = true))
            while (history.size > MAX_HISTORY) history.removeFirst()
            unread = history.count { !it.fromUser }
        }
        postConversation(channel = CHANNEL_CHAT, unread = unread, silent = true, alertOnce = true)
    }

    /** Finance Step-Up needs a fingerprint within 30 s: urgent heads-up that auto-expires. */
    fun showStepUpRequired(actionLabel: String, formattedAmount: String, recipient: String) {
        if (tracker.isChatVisible()) return
        try {
            val body = "$actionLabel $formattedAmount to $recipient. Verify with fingerprint within 30s."
            val publicVersion = NotificationCompat.Builder(context, CHANNEL_URGENT)
                .setSmallIcon(R.drawable.ic_notif_nexa)
                .setContentTitle("N.E.X.A")
                .setContentText("Approval required")
                .build()

            val open = openChatPendingIntent(requestCode = NOTIF_ID_STEPUP)
            val notification = NotificationCompat.Builder(context, CHANNEL_URGENT)
                .setSmallIcon(R.drawable.ic_notif_nexa)
                .setColor(ACCENT_COLOR)
                .setContentTitle("Fingerprint approval required")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setContentIntent(open)
                .addAction(R.drawable.ic_notif_nexa, "Verify", open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setTimeoutAfter(STEPUP_TTL_MS)
                .build()
            notificationManager.notify(NOTIF_ID_STEPUP, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed posting step-up notification: ${e.message}")
        }
    }

    fun cancelStepUp() {
        notificationManager.cancel(NOTIF_ID_STEPUP)
    }

    /** Clears the conversation + unread counter. @return true if there was something to clear. */
    fun clearConversation(): Boolean {
        val hadContent = synchronized(lock) {
            val had = history.isNotEmpty()
            history.clear()
            had
        } || tracker.unreadCount.value > 0
        tracker.clearUnread()
        notificationManager.cancel(NOTIF_ID_CONVERSATION)
        return hadContent
    }

    fun clearAll(): Boolean {
        val had = clearConversation()
        cancelStepUp()
        return had
    }

    /** User swiped the notification away: forget the shown history (does NOT mark read on server). */
    fun onDismissedBySwipe() {
        synchronized(lock) { history.clear() }
        tracker.clearUnread()
    }

    // Internals

    private fun postConversation(channel: String, unread: Int, silent: Boolean, alertOnce: Boolean) {
        try {
            val style = NotificationCompat.MessagingStyle(userPerson)
            val snapshot = synchronized(lock) { history.toList() }
            for (entry in snapshot) {
                style.addMessage(entry.text, entry.timestamp, if (entry.fromUser) null else nexaPerson)
            }

            val countLabel = if (unread <= 1) "1 new message" else "$unread new messages"
            val publicVersion = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notif_nexa)
                .setContentTitle("N.E.X.A")
                .setContentText(countLabel)
                .build()

            val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT).setLabel("Write a reply...").build()
            val replyAction = NotificationCompat.Action.Builder(
                R.drawable.ic_notif_nexa,
                "Reply",
                actionPendingIntent(ACTION_REPLY, mutable = true)
            )
                .addRemoteInput(remoteInput)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .setAllowGeneratedReplies(false)
                .build()

            val markReadAction = NotificationCompat.Action.Builder(
                R.drawable.ic_notif_nexa,
                "Mark as read",
                actionPendingIntent(ACTION_MARK_READ, mutable = false)
            )
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build()

            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notif_nexa)
                .setColor(ACCENT_COLOR)
                .setStyle(style)
                .setShortcutId(SHORTCUT_ID)
                .setLocusId(LocusIdCompat(SHORTCUT_ID))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(
                    when (channel) {
                        CHANNEL_SILENT -> NotificationCompat.PRIORITY_LOW
                        else -> NotificationCompat.PRIORITY_HIGH
                    }
                )
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setNumber(unread)
                .setContentIntent(openChatPendingIntent(requestCode = NOTIF_ID_CONVERSATION))
                .setDeleteIntent(actionPendingIntent(ACTION_DISMISSED, mutable = false))
                .addAction(replyAction)
                .addAction(markReadAction)
                .setAutoCancel(true)
                .setOnlyAlertOnce(alertOnce)
                .setSilent(silent)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build()

            notificationManager.notify(NOTIF_ID_CONVERSATION, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed posting chat notification: ${e.message}")
        }
    }

    private fun openChatPendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_OPEN_TAB, NexaMainTab.CHAT.name)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun actionPendingIntent(action: String, mutable: Boolean): PendingIntent {
        val intent = Intent(context, ChatNotificationActionReceiver::class.java).apply {
            this.action = action
            setPackage(context.packageName)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, flags)
    }

    private fun ensureChannels() {
        try {
            notificationManager.deleteNotificationChannel(LEGACY_PROACTIVE_CHANNEL)

            NotificationChannel(CHANNEL_URGENT, "Urgent", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Fingerprint approvals and high-priority alerts"
                enableVibration(true)
                enableLights(true)
                notificationManager.createNotificationChannel(this)
            }
            NotificationChannel(CHANNEL_CHAT, "Chat Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Replies, briefings, and proactive reminders"
                enableVibration(true)
                notificationManager.createNotificationChannel(this)
            }
            NotificationChannel(CHANNEL_SILENT, "Silent Digest", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Periodic updates without sound or vibration"
                notificationManager.createNotificationChannel(this)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed creating notification channels: ${e.message}")
        }
    }

    private fun publishConversationShortcut() {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_OPEN_TAB, NexaMainTab.CHAT.name)
            }
            val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
                .setShortLabel("N.E.X.A")
                .setLongLabel("Chat with N.E.X.A")
                .setLongLived(true)
                .setPerson(nexaPerson)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setLocusId(LocusIdCompat(SHORTCUT_ID))
                .setIntent(intent)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        } catch (e: Exception) {
            Log.w(TAG, "Failed publishing conversation shortcut: ${e.message}")
        }
    }
}

private const val ACCENT_COLOR = 0xFF10B981.toInt()
