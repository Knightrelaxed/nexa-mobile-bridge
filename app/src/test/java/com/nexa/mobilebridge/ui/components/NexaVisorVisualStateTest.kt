package com.nexa.mobilebridge.ui.components

import com.nexa.mobilebridge.core.util.ConnectionState
import com.nexa.mobilebridge.data.chat.NexaAvatarState
import org.junit.Assert.assertEquals
import org.junit.Test

class NexaVisorVisualStateTest {

    @Test
    fun `disconnected and auth failed always resolve to OFFLINE sleep pose`() {
        for (avatar in NexaAvatarState.entries) {
            assertEquals(
                NexaVisorVisualState.OFFLINE,
                resolveVisorVisualState(avatar, ConnectionState.Disconnected("offline"))
            )
            assertEquals(
                NexaVisorVisualState.OFFLINE,
                resolveVisorVisualState(avatar, ConnectionState.AuthFailed("bad token"))
            )
        }
    }

    @Test
    fun `connecting and reconnecting always resolve to RECONNECTING scan pose`() {
        for (avatar in NexaAvatarState.entries) {
            assertEquals(
                NexaVisorVisualState.RECONNECTING,
                resolveVisorVisualState(avatar, ConnectionState.Connecting)
            )
            assertEquals(
                NexaVisorVisualState.RECONNECTING,
                resolveVisorVisualState(avatar, ConnectionState.Reconnecting(attempt = 2, nextRetryMs = 2000L))
            )
        }
    }

    @Test
    fun `connected maps every avatar state 1 to 1 with Live Call eye language`() {
        val connected = ConnectionState.Connected

        assertEquals(NexaVisorVisualState.IDLE, resolveVisorVisualState(NexaAvatarState.IDLE, connected))
        assertEquals(NexaVisorVisualState.LISTENING, resolveVisorVisualState(NexaAvatarState.LISTENING, connected))
        assertEquals(NexaVisorVisualState.THINKING, resolveVisorVisualState(NexaAvatarState.THINKING, connected))
        assertEquals(NexaVisorVisualState.STREAMING, resolveVisorVisualState(NexaAvatarState.STREAMING, connected))
        assertEquals(NexaVisorVisualState.STEP_UP, resolveVisorVisualState(NexaAvatarState.STEP_UP, connected))
        assertEquals(NexaVisorVisualState.ERROR, resolveVisorVisualState(NexaAvatarState.ALERT, connected))
    }
}
