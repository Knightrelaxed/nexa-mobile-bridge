package com.nexa.mobilebridge.notification

import com.nexa.mobilebridge.NexaMainTab
import com.nexa.mobilebridge.data.chat.ChatVisibilityTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNotificationPolicyTest {

    @Test
    fun `channelForPriority routes proactive P0 P1 P2 and replies to correct channels`() {
        assertEquals(
            ChatNotificationManager.CHANNEL_URGENT,
            ChatNotificationManager.channelForPriority(priority = "P0", isProactive = true)
        )
        assertEquals(
            ChatNotificationManager.CHANNEL_URGENT,
            ChatNotificationManager.channelForPriority(priority = "p0", isProactive = true)
        )
        assertEquals(
            ChatNotificationManager.CHANNEL_CHAT,
            ChatNotificationManager.channelForPriority(priority = "P1", isProactive = true)
        )
        assertEquals(
            ChatNotificationManager.CHANNEL_SILENT,
            ChatNotificationManager.channelForPriority(priority = "P2", isProactive = true)
        )
        // Direct assistant replies always route to CHANNEL_CHAT regardless of priority string
        assertEquals(
            ChatNotificationManager.CHANNEL_CHAT,
            ChatNotificationManager.channelForPriority(priority = "P0", isProactive = false)
        )
    }

    @Test
    fun `visibility tracker reports chatVisible only when app foreground and CHAT tab active`() {
        val tracker = ChatVisibilityTracker()

        assertFalse(tracker.isChatVisible())

        tracker.setVisibleTab(NexaMainTab.CHAT)
        assertFalse(tracker.isChatVisible()) // still background

        tracker.setAppForeground(true)
        assertTrue(tracker.isChatVisible())

        tracker.setVisibleTab(NexaMainTab.HUD)
        assertFalse(tracker.isChatVisible())

        tracker.setVisibleTab(null) // e.g., Settings screen open
        assertFalse(tracker.isChatVisible())

        tracker.setVisibleTab(NexaMainTab.CHAT)
        assertTrue(tracker.isChatVisible())

        tracker.setAppForeground(false)
        assertFalse(tracker.isChatVisible())
    }

    @Test
    fun `visibility tracker increments unread up to 99 and clears on clearUnread`() {
        val tracker = ChatVisibilityTracker()

        assertEquals(0, tracker.unreadCount.value)
        tracker.incrementUnread()
        tracker.incrementUnread()
        assertEquals(2, tracker.unreadCount.value)

        repeat(120) { tracker.incrementUnread() }
        assertEquals(99, tracker.unreadCount.value)

        tracker.clearUnread()
        assertEquals(0, tracker.unreadCount.value)
    }

    @Test
    fun `visibility tracker handles deep link openTabRequest consume lifecycle`() {
        val tracker = ChatVisibilityTracker()

        assertNull(tracker.openTabRequest.value)
        tracker.requestOpenTab(NexaMainTab.CHAT)
        assertEquals(NexaMainTab.CHAT, tracker.openTabRequest.value)

        tracker.consumeOpenTabRequest()
        assertNull(tracker.openTabRequest.value)
    }
}
